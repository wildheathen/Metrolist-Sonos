# Sonos Cast — Piano consolidato post-audit

Documento operativo per portare l'integrazione Sonos da "MVP funzionante con
sintomi" a "esperienza seamless paragonabile a Google Cast nativo". Si basa
su tre audit eseguiti dopo il primo round di feedback utente:

- **Audit A — UI playback surfaces**: cataloga ogni componente UI che
  mostra stato playback e segna chi sopravvive allo swap.
- **Audit B — Settings Cast UI**: mappa la schermata Sonos attuale e i
  suoi pain point.
- **Audit C — Restart & persistence**: identifica le race condition tra
  caricamento queue persistita, swap player, observer UI.

I tre audit hanno trovato le **stesse cause radice** osservate da angolazioni
diverse — il piano sotto le tratta come tre manifestazioni di un unico
problema architetturale e le risolve in modo coordinato.

---

## Diagnosi unificata

L'integrazione Sonos ha tre debiti tecnici che si potenziano a vicenda:

### Debito 1 — "Direct player access" sparso ovunque
Mezzo MusicService e parti significative della UI (Player.kt, MiniPlayer.kt,
widget update, saveQueueToDisk) leggono/scrivono direttamente
`musicService.player` (l'ExoPlayer lateinit) invece di passare per
`mediaSession.player` (= player attivo). Quando il cast è attivo:

- L'utente comanda → arriva al player attivo via PlayerConnection ✅
- La UI legge stato → spesso direttamente da ExoPlayer ❌ → mostra valori
  stantii
- saveQueueToDisk salva la queue di ExoPlayer ❌ → al restart
  carichiamo la queue pre-cast invece di quella che l'utente sta sentendo

### Debito 2 — Track transitions Sonos non rilevate
Il Sonos avanza al next via SetNextAVTransportURI (gapless), ma noi non
intercettiamo il cambio di `currentUrl` nel polling per aggiornare
`currentIndex` del UpnpPlayer. La UI rimane sulla traccia precedente,
i bottoni skip ricaricano la "next" ma il Sonos sta già suonando quella,
risultato: tutto sembra "lag di 1 traccia".

### Debito 3 — Race conditions al boot
1. `PlayerConnection.mediaMetadata` viene letto subito dall'init, prima
   che la queue persistita sia caricata in ExoPlayer → primo frame ha
   `null`/stantio.
2. `MusicService.currentMediaMetadata` (consumato da MainActivity per
   tema, lyrics, ecc.) NON viene aggiornato da `updateAttachedPlayer` —
   solo da `onMediaItemTransition`. Quando swappiamo a UpnpPlayer e
   ritorniamo, il "current" resta agganciato all'ultima traccia
   ExoPlayer-side.
3. Restore di `playerState` ha un `delay(1000)` che spera che la queue
   sia caricata in 1 s. Se è più lenta, seek va su index inesistente.
4. La queue persistita è quella di ExoPlayer (Debito 1) → al riavvio
   carichiamo "altro album" rispetto a quello che era in cast.

**Conseguenza visibile per l'utente:**

- "Vedo altro album al riavvio" ← Debiti 1+3
- "Skip avanti riavvia la canzone corrente" ← Debito 2
- "Pausa/play impiega 5 s a rispondere" ← Debito 1 (UI legge stato dal
  player sbagliato e/o polling pause-throttled)
- "Slider non si muove" ← Debito 1 (Player.kt loop si ferma quando
  isCasting=true ma nessun loop alternativo legge da castPosition)

---

## Roadmap operativa

Sei fasi, ognuna ~30-90 min. Ordinate per impact/risk: i fix con più
impatto utente e meno rischio architetturale prima.

### F1 — Eliminare "direct player access" residui [P0, ~90 min]

**Obiettivo**: ogni accesso a `playerConnection.player.X()` per
operazioni *user-facing* (play, pause, seek, skip, queue mod) e ogni
accesso *read-only* per la UI in cast deve passare per il player
attivo.

**Lavoro**:

1. **MusicService.kt**:
   - `saveQueueToDisk` (~L3290): leggere items/index/position da
     `mediaSession.player ?: player` invece di `player`. Già introdotto
     `activePlayer` getter — usarlo qui.
   - `updateWidgetUI` (~L3551-3552): stessa cosa — duration/position dal
     player attivo (oppure, meglio: dal `castConnectionHandler` se
     casting, perché è già esposto come StateFlow).

2. **Player.kt**:
   - Position update loop (L713-731): aggiungere un secondo
     LaunchedEffect che, quando `isCasting=true`, copia
     `castPosition`/`castDuration` in `position`/`duration`. Senza
     questo, slider e duration label restano congelati (sintomo
     osservato).
   - Sleep timer remaining (L572): usare la `effectivePosition` e
     `effectiveDuration` (combina cast+local) invece di accesso raw.
   - Volume slider (~L1800): tracciare il flow del volume e capire se
     finisce su `player.setDeviceVolume` (ExoPlayer) o sul player
     attivo. Se necessario aggiungere routing via PlayerConnection.

3. **MiniPlayer.kt**:
   - Swipe skip logic (L324-357): sostituire
     `playerConnection.player.previousMediaItemIndex` con
     `playerConnection.canSkipPrevious.value` (che già è cast-aware) e
     i `seekToPrevious/Next()` con
     `playerConnection.seekToPrevious()/seekToNext()`.

4. **Verifica regressioni**: dopo F1, fare smoke test del local-only
   playback. Tutti i call site lasciati su `player` diretto sono
   intenzionali (error recovery, EQ rebind, audio sink — vedi audit per
   la lista).

**Acceptance criteria**:
- Riavvio app dopo cast → la queue caricata è quella che era in cast
  (non la pre-cast)
- Widget mostra position corrente del Sonos durante cast
- Slider del player principale si muove durante cast
- Smoke test local playback passa

---

### F2 — Track-transition detection [P0, ~60 min]

**Obiettivo**: quando il Sonos passa naturalmente al next (gapless),
`UpnpPlayer.currentIndex` si aggiorna e la UI segue.

**Lavoro**:

1. In `UpnpPlayer.init`, aggiungere un secondo collector su
   `controller.playbackState.map { it.currentUrl }.distinctUntilChanged()`.
2. Mantenere una `MutableMap<String, Int>` `urlToIndex` aggiornata da
   `tryLoadForCurrent` e `maybePreloadNext`: chiave = `playUrl`
   (proxy URL), valore = index in `items`.
3. Quando l'URL cambia: lookup nell'urlToIndex; se trovato e diverso da
   currentIndex → aggiornare currentIndex, resetNextPreload(),
   `withContext(Dispatchers.Main) { invalidateState() }`.
4. Edge case: se URL non è nella mappa (es. Sonos passato a una
   stazione "real radio" da app Sonos nativa), loggare e non swappare.

**Acceptance criteria**:
- Lasci finire una traccia → la UI Metrolist passa autonomamente
- Skip non "ricomincia la canzone già in play"
- Titolo/copertina/album sono allineati col Sonos in ogni momento

---

### F3 — Boot/restart sync [P0, ~75 min]

**Obiettivo**: alla riapertura app, la UI mostra subito la traccia
giusta (quella effettivamente in riproduzione) senza il flash "altro
album".

**Lavoro**:

1. **PlayerConnection.updateAttachedPlayer**: oltre a `mediaMetadata.value`,
   aggiornare anche `service.currentMediaMetadata.value` (vedi audit C
   FIX 3). Questo risolve il "flash altro album" alla riapertura.
2. **Restore queue**: trasformare `delay(1000)` in attesa attiva su
   `player.mediaItemCount > playerState.currentMediaItemIndex` con
   timeout 2 s. Più affidabile (audit C FIX 4).
3. **Reconnect cast a sessione esistente**: in `UpnpCastController.connect`,
   dopo il successful connect, leggere `getMediaInfo` e `getPositionInfo`
   per scoprire se il Sonos sta già suonando qualcosa di nostro (URL
   contiene il proxy host). Se sì, NON re-fare loadMedia in primeSonos
   — adottare lo stato corrente via T1-style URL→index lookup.
4. **Don't save during transient swap**: in `saveQueueToDisk`, evitare
   di salvare durante una swap in corso (mediaSession.player è in
   transizione). Aggiungere flag `isSwapping` settato vero per ~200ms
   in `swapActivePlayer`.

**Acceptance criteria**:
- Riapri app → "in alto" mostra subito la traccia giusta, niente
  flash di album diverso
- Riconnetti Sonos a una sessione cast esistente → non ricomincia da 0
- Stop/play dopo riapertura risponde entro 1 s

---

### F4 — Seamless play/pause [P1, ~45 min]

**Obiettivo**: il bottone play/pause risponde visivamente entro 100 ms
sempre, anche con polling throttled.

**Lavoro**:

1. **Optimistic state update**: in `UpnpCastController.play()` e
   `pause()`, aggiornare `_playbackState.value.isPlaying` PRIMA della
   SOAP call (è già così), ma anche **forzare un poll immediato** subito
   dopo la SOAP success per riportare la realtà. Senza questo, durante
   pause-throttled (5s), un play impiega fino a 5s per riflettersi se
   l'utente non guarda nient'altro.
2. **Polling immediato post-comando**: factor out `pollOnceLocked()` da
   `startPollingLocked()` e chiamarlo in coda a play/pause/seek (dentro
   commandMutex.withLock).
3. **Throttle wake-up**: se l'utente preme play durante il throttle a
   5s, il polling deve passare a 1s entro 200ms (non aspettare la fine
   del 5s). Aggiungere un `Channel<Unit>` `pollWakeup`: il polling fa
   `select { onTimeout(currentInterval); pollWakeup.onReceive(...) }`.
4. **UI**: l'icona play/pause già è bound al StateFlow, quindi il
   refresh è automatico — ma il delay percepito viene dalla SOAP
   round-trip (200-400 ms). Inevitabile, ma con optimistic update il
   comando "sembra" subito accettato.

**Acceptance criteria**:
- Stop → play in pausa lunga: icona cambia entro 100ms
- Slider riprende a muoversi entro 1s
- Niente "click - silence - click di nuovo - play parte" (sintomo
  "5 secondi" osservato)

---

### F5 — Settings UX refactor [P1, ~120 min]

**Obiettivo**: schermata Cast pulita, separazione netta tra "rapida
selezione device" (sheet dal player) e "gestione/diagnostica avanzata"
(settings full screen). Eliminare la confusione preferiti vs discovery.

**Lavoro**:

1. **Estrarre `SonosDeviceList` componente condiviso** usato sia da
   SonosTestScreen che da UpnpCastPickerSheet. Elimina duplicazione di
   `DeviceRow`/`KnownDeviceRow`/`SheetDeviceRow`/`SheetKnownDeviceRow`
   (4 → 2).
2. **Riorganizzare le sezioni**: invece di 3-4 card separate (Recenti,
   Discovery, Devices), un'unica lista che fonde recenti+devices con
   marcatori visivi (icona stella per recenti, "(connesso)" badge per
   il device attivo). Cerca SSDP / Manual IP in un singolo bottone
   "+" in alto.
3. **Rinominare**:
   - `SonosTestViewModel` → `SonosCastViewModel` (è usato in
     production, non solo test)
   - `SonosTestScreen` → `SonosCastSettingsScreen`
   - "Sonos UPnP (beta)" → "Sonos Cast"
4. **Hardcoded strings → R.string**: estrarre le 9 stringhe italiane
   in `UpnpCastPickerSheet.kt`.
5. **Material3SettingsGroup** in SonosTestScreen al posto delle Card
   custom. Coerenza con tutta l'app.
6. **Empty state**: se no devices and no known devices, placeholder
   con istruzioni semplici ("Assicurati che Sonos e telefono siano
   sulla stessa rete WiFi", "Premi cerca...").
7. **Eliminare playback test card** dal flow utente normale: mettere
   dietro un toggle "Strumenti sviluppatore" o un long-press sul
   titolo. Un utente comune non capisce a cosa serve "URL audio".
8. **Decisione su `UpnpCastCurrentTrackButton`**: oggi è orfano
   (nessuno lo importa). O integrarlo nel PickerSheet come "Cast il
   brano corrente", o rimuoverlo del tutto.

**Acceptance criteria**:
- Un utente comune apre Settings → Cast → vede subito i Sonos noti
  e quelli scoperti, sceglie e va.
- Test screen separato (developer-only) per debug.
- Picker sheet dal player è la stessa lista, niente shock visivo.

---

### F6 — Polish & robustness [P2, ~45 min]

**Obiettivo**: ultimi miglioramenti di affidabilità, senza
modifiche architetturali.

**Lavoro**:

1. **Sync volume slider lag**: già funziona ma laggy. Implementare
   `onValueChange` ottimistico (UI subito al valore nuovo) +
   `onValueChangeFinished` per la SOAP call. Già pattern usato altrove.
2. **Fallback duration da Sonos**: per tracce con `tag.duration = -1`,
   leggere la durata dal primo `getPositionInfo` post-load e
   pubblicarla (Sonos legge il Content-Length dal proxy). Già
   parzialmente coperto dal polling normale, ma il primo frame sarà
   ancora vuoto — aggiungere un poll immediato dopo loadMedia.
3. **Notification source**: oggi `MediaNotificationProvider` legge da
   `mediaSession.player` (già swappato). Verificare che durante cast
   la notification Sonos mostri cover/titolo giusti. Test manuale.
4. **WiFi lock during cast**: opzionale ma importante. Il proxy server
   ha bisogno della WiFi attiva. Su alcuni Android in doze la WiFi va
   in idle e il Sonos perde il flusso. Acquisire `WifiManager.WifiLock`
   in `UpnpCastController.connect` e rilasciarlo in `disconnect`.
5. **Token GC log**: log quando `evictStale` rimuove token (già
   implementato, manca il log).

**Acceptance criteria**:
- Volume slider feel più reattivo
- Tracce senza duration locale comunque scrubabili dopo 1-2s
- WiFi non scollega il Sonos in pocket-mode

---

## Ordering & checkpoints

```
F1 (~90 min) ──────┬── checkpoint: smoke test local + cast basic ──┐
F2 (~60 min) ──────┤                                                │
F3 (~75 min) ──────┴── checkpoint: restart 5x, no flash, no desync ┤
                                                                    │
F4 (~45 min) ────── checkpoint: stop/play <100ms feel ──────────────┤
                                                                    │
F5 (~120 min) ───── checkpoint: UX walkthrough (parente non-tech) ──┤
F6 (~45 min) ────── checkpoint: cast 30min stress test ─────────────┘
                                                            │
                                                            ▼
                                                   Final integration test
                                                   + commit/push/release
```

Tempo totale stimato: **7-8 ore** di lavoro effettivo. Si può fare in
una sessione mezza giornata se va liscio, due sessioni se ci sono
sorprese.

---

## Cosa NON fare

- **Refactor PlayerConnection in classe nuova / riscrittura**.
  L'architettura attuale è OK, mancano solo i forwarding corretti.
  Riscrivere causerebbe regressioni in 30+ feature non-cast.
- **GENA event subscriptions** per le track transitions. F2 con polling
  copre lo use case con 1s di latenza max — abbastanza.
- **Transcoding lato server** o ADTS repack. Il proxy passthrough
  funziona, è già la soluzione di minor sforzo.
- **Custom MediaNotificationProvider**. Il default Media3 fa già il
  giusto leggendo da `mediaSession.player`.

---

## Test plan finale (post-implementazione)

### Test automatici (futuri, fuori scope adesso)
- Unit: SonosHttpProxy (range, GC token, HEAD), DidlBuilder, format selection
- Integration: fake AVTransport per simulare transitions

### Test manuale
1. **Local-only smoke**: 5 min di playback locale, skip avanti/indietro,
   seek, queue add — verificare che niente è rotto da F1.
2. **Cast basic**: connetti, play, pause, skip, seek, volume — tutto
   responsive.
3. **Cast transition**: lascia finire una traccia → next parte
   automatico, UI segue.
4. **Boot/restart 5x**: chiudi+riapri app durante cast, verifica
   nessun flash di album sbagliato.
5. **Reconnect mid-session**: uccidi la WiFi e riconnetti, verifica
   recovery senza loop.
6. **Settings walkthrough**: chiama la nonna, dille "connetti la musica
   alla cassa Sonos in soggiorno". Se ci riesce, F5 è OK.
