# Piano: sincronizzazione transizioni di traccia Sonos↔app

## Sintomi osservati

1. Dopo che il Sonos passa naturalmente alla traccia successiva (gapless
   preload), la UI Metrolist mostra ancora titolo/album/copertina della
   precedente.
2. Premere "skip avanti" in quello stato fa ripartire da capo la traccia
   già in riproduzione sul Sonos (perché l'app pensa di "avanzare alla
   prossima" ma invece ricarica quella che il Sonos sta già suonando, da 0).
3. Chiudendo e riaprendo l'app: in alto compare il titolo del *vecchio*
   album (l'ultima traccia prima del cast), mentre la canzone effettivamente
   in riproduzione è un'altra. La banner "In riproduzione" punta a un terzo
   item ancora.
4. Pause/play dopo un periodo di silenzio impiega 5+ secondi per rispondere.
5. Una traccia con `duration = -1` nel tag locale (es. importate, risultati
   di ricerca senza metadata) non mostra la slider.

## Diagnosi confermata dai log

Sequenza catturata in `bxdqe61b3.output`:

```
23:39:21  loadMedia "Believer"
23:39:25-28  poll TRANSITIONING/OK (4 ticks)
23:39:36  preloaded next "Smells Like Teen Spirit" via SetNextAVTransportURI
…
23:40:38  loadMedia "Smells Like Teen Spirit"   <-- USER clicked skip
```

Tra il preload e la nuova loadMedia il Sonos ha **già transitato** sulla
nuova traccia da solo (è gapless, è il senso del preload). Ma la UI app non
se ne è accorta perché `UpnpPlayer.currentIndex` è restato a quella di
Believer. Quando l'utente clicca skip, il nostro `handleSeek` calcola
`mediaItemIndex = 1`, vede che è diverso da `currentIndex (0)`, fa una
nuova loadMedia su Smells da 0 → **ricomincia la canzone**.

Root cause: **non monitoriamo le transizioni naturali del Sonos**.
`controller.playbackState.currentUrl` cambia quando il Sonos avanza, ma
`UpnpPlayer` non confronta quel cambio con i suoi `items[]` per aggiornare
`currentIndex`.

## Piano in 5 task

### T1. Track-transition detection nel polling [P0]

In `UpnpCastController.startPollingLocked` esponiamo, oltre allo stato
corrente, l'evento "currentUrl is cambiato". `UpnpPlayer` lo ascolta:
quando cambia URL, mappa `playbackState.currentUrl` → token → `MediaItem`
in `items[]` → aggiorna `currentIndex` + `invalidateState()`.

Per fare la mappatura serve sapere quale token corrisponde a quale
mediaId. Due opzioni:

**A. Token registry esteso**: `SonosHttpProxy.register(upstreamUrl,
mediaId)` salva anche il mediaId nell'Entry. UpnpCastController espone un
metodo `mediaIdForUrl(currentUrl)` che parsea il token dalla URL e fa
lookup in proxy.tokens.

**B. Map locale in UpnpPlayer**: dopo `tryLoadForCurrent` salviamo
`currentUrl → mediaId` in una mappa nel UpnpPlayer.

Preferisco **B** — UpnpPlayer è già il proprietario di `items[]`, è il
posto naturale per la mappa, e non sporcheremo l'API del proxy.

Implementazione:

1. In UpnpPlayer aggiungere `private val urlToIndex = mutableMapOf<String, Int>()`.
2. Dopo ogni `loadCurrentOnRemote(idx)` riuscita, salvare il `playUrl`
   (quello che è effettivamente arrivato al Sonos — bisogna farselo
   restituire dal controller; ora la URL è opaca al UpnpPlayer).
3. Nel `init` block del UpnpPlayer aggiungere un secondo collector su
   `controller.playbackState.map { it.currentUrl }.distinctUntilChanged()`.
4. Quando l'URL cambia: `urlToIndex[newUrl]?.let { newIdx ->
   if (newIdx != currentIndex) { currentIndex = newIdx;
   invalidateState(on main); resetNextPreload() } }`.

Alternativa piú pulita: invece di una mappa URL→idx, usiamo
`controller.playbackState.currentTitle` confrontato con `items[].mediaMetadata.title`.
Più fragile (titoli duplicati) ma evita di passare URL strane in giro.

### T2. setNextMedia deve usare il vero "next" del UpnpPlayer [P1]

Oggi `maybePreloadNext` legge `items[currentIndex + 1]`. Se durante il
preload l'utente riordina la coda (player.moveMediaItem) o aggiunge una
canzone "play next", il preload può puntare alla traccia sbagliata. Fix:
ricalcolare il next al momento del preload usando `getMediaItemAt`.

Bonus: passare anche `durationMs` a `setNextMedia` (oggi non lo fa) —
risolve in parte il caso "Save Your Tears senza durata".

### T3. Race riapertura app [P0]

Quando l'app viene chiusa e riaperta:
- L'ExoPlayer locale ricarica il `persistent_queue` da disco e prepara
  l'ultima traccia non-cast.
- Il `UpnpCastController` parte Disconnected. Il MusicService observer
  vede Disconnected e chiama `swapActivePlayer(player)` → ExoPlayer torna
  attivo (giusto).
- Quando l'utente riconnette il Sonos, parte `primeSonos` ma usa la
  **queue dell'ExoPlayer locale** (che è la traccia restaurata da disco).
  Se nel frattempo il Sonos sta ancora suonando un'altra cosa (perché
  l'utente ha lasciato il cast attivo dalla sessione precedente), abbiamo
  due verità diverse.

Fix: in `connect()`, prima di restituire Connected, chiamare
`getPositionInfo` e `getMediaInfo` per leggere se il Sonos sta già
suonando qualcosa e settare `playbackState.currentUrl` di conseguenza.
Il MusicService observer deve poi decidere se:
- Sonos suona qualcosa **del nostro** (URL contiene il nostro proxy host) →
  non rifare primeSonos, lasciare quel che c'è e adottare via T1.
- Sonos suona altro / nulla → primeSonos normale.

Sospetto sia anche da qui che viene la "in alto vedo altro album": al
boot, prima che il MediaSession decida chi è il player attivo, c'è una
finestra in cui PlayerConnection legge dall'ExoPlayer (queue restaurata =
"altro album") e poi viene swappato.

Mitigazione minima senza rifare il flusso boot: salvare in DataStore un
flag `lastSession.wasSonosCasting` e, all'avvio, ritardare di 1-2s
l'inizializzazione della UI di "now playing" se il flag è true e
l'utente sta provando a riconnettere.

Soluzione pulita: rendere il "current item" della UI un campo derivato
da `mediaSession.player.currentMediaItem`, così cambia atomicamente con
lo swap. Questo dovrebbe essere già il caso ma c'è probabilmente un altro
flow che legge dall'ExoPlayer direttamente.

### T4. Pause/play lento dopo silenzio [P1]

Probabile causa: la prima HTTP request del Sonos al risveglio fallisce
(connessione TCP scaduta) e il device fa retry dopo timeout. Conferma
necessaria con i log.

Hypothesis B: dopo lunga pausa, il polling viene throttato a 5s (T-U3
appena fatta!). L'utente preme play ma l'optimistic update arriva dopo
fino a 5s (next polling tick). Vedere log dovrebbe confermare.

Fix se confermata B: forzare un poll immediato sul lato controller dopo
ogni play()/pause() per riportare lo stato in sync subito senza aspettare
il prossimo tick.

### T5. Fallback duration quando il tag è -1 [P2]

Per le tracce con `tagDurationSec = -1`, leggere la durata dal
`getPositionInfo` del Sonos appena prima della prima poll. Sonos sa la
durata reale dello stream (la legge dal Content-Length del proxy). Una
volta nota, alimentarla in `pb.duration`. Già parzialmente coperto dal
polling normale, ma il primo frame sarà ancora vuoto — accettabile.

## Cosa NON fare

- **Non riscrivere lo swap come "shadow player"** (sostituire il source
  dell'ExoPlayer invece del player intero). Cambia troppo, rischio alto.
- **Non aggiungere GENA event subscriptions** per le track transitions.
  Polling 1Hz è più che sufficiente per "rilevare cambio URL".
- **Non passare al transcoding lato server**. Il proxy passthrough
  funziona, è già la soluzione di minor sforzo.

## Ordine di lavoro proposto

1. T1 (1.5h) — il fix più impattante, sblocca T2 e T3.
2. T3 (45 min se T1 funziona) — risolve la confusione boot/restart.
3. T4 — diagnosi via log, fix se confermata B (15 min).
4. T2 (30 min) — robustness gapless.
5. T5 — opzionale, low-priority.

Test plan minimo:
- Skip → la UI segue
- Lascia finire una traccia → la UI passa da sola
- Chiudi app, riapri, riconnetti Sonos → niente "altro album"
- Pause 30s, premi play → risponde entro <1s
