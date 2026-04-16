# Sonos UPnP Cast — Roadmap & Technical Debt

Living document tracking what works, what's hacky, and what's missing in the
Sonos cast integration. Update as we go.

Status: **functional MVP** as of phase5+proxy. Audio plays, transport
controls work, but the integration leaks ExoPlayer state in places.

---

## What's done (release/phase5)

| # | Feature | File(s) |
|---|---------|---------|
| 1 | UPnP discovery (SSDP + manual IP) | `upnp/UpnpDiscovery.kt` |
| 2 | AVTransport / RenderingControl SOAP wrappers | `upnp/AVTransport.kt`, `upnp/RenderingControl.kt` |
| 3 | UpnpCastController orchestration | `upnp/UpnpCastController.kt` |
| 4 | Media3 SimpleBasePlayer adapter | `playback/UpnpPlayer.kt` |
| 5 | MusicService swap on connect/disconnect | `playback/MusicService.kt` (~L625) |
| 6 | Cast button in mini & full player | `ui/component/upnp/UpnpCastButton.kt` |
| 7 | Settings toggle to enable/disable feature | `ui/screens/settings/CastSettings.kt` |
| 8 | Phase 3a developer test screen | `ui/screens/settings/SonosTestScreen.kt` |
| 9 | Known-devices persistence + reconnect | `upnp/UpnpCastController.kt` |
| 10 | Network change → re-verify connection | `playback/MusicService.kt` (~L788) |
| 11 | Local HTTP proxy (Content-Type override) | `upnp/SonosHttpProxy.kt` |
| 12 | Force AAC/MP4 format selection in cast | `utils/YTPlayerUtils.kt` |
| 13 | Seek-lock window to suppress polling jitter | `upnp/UpnpCastController.kt` |
| 14 | Volume sync with Sonos device on connect | `playback/MusicService.kt` |
| 15 | Verify-after-Play (SOAP 200 != PLAYING) | `upnp/UpnpCastController.kt` |
| 16 | x-rincon experiments + wildcard MIME tests | (rolled into proxy approach) |

---

## Known issues (open)

### P0 — would block a clean PR

#### **D1. Direct ExoPlayer references bypass mediaSession.player**

Many call sites in `MusicService` still reference the lateinit ExoPlayer
field `player` directly instead of going through the active player. Any
operation in cast mode that lands on the local ExoPlayer either:
- starts audio on the phone (when it shouldn't), or
- silently fails to reach the Sonos.

Patched so far: `playQueue` (~L1586), `playNext` (~L1805), volume listener
(~L877). Still using direct `player` calls (sample, ~30 total):

```
~L457   player.play()                  (notification action handler)
~L762   player.seekTo(currentPosition) (EQ profile change)
~L855   player.seekTo + prepare        (autoresume)
~L1265  player.play()                  (idk — needs reading)
~L1385  player.seekTo / prepare        (queue position adjust?)
~L1404  player.seekTo(nextWindowIndex) (skip-next handling)
~L1556  player.setMediaItem(preload)   (preload path)
~L1649  player.removeMediaItems        (radio refresh)
~L1827  player.removeMediaItem(index)  (queue manipulation)
~L1835  player.addMediaItems           (queue manipulation)
…
```

**Symptoms**: "if I select a new song, audio comes back to the phone";
"Sonos keeps playing the old track while the phone shows the new one";
overlaid stale artwork during transition.

**Fix shape**: introduce `private val activePlayer: Player` as the canonical
accessor (already partially done) and replace `player.X()` → `activePlayer.X()`
everywhere a *user-facing* playback command is issued. Keep direct `player`
access only for ExoPlayer-specific operations (audio sink, equalizer,
loudness, sleep timer, hardware crossfade).

Acceptance criteria:
- Selecting a song in cast mode → Sonos transitions to it, phone stays muted
- Skip next/previous → Sonos does it, phone stays silent
- Reordering queue → no audio leak to phone
- All existing local-playback paths still work when not casting

Estimate: 2-3 hours of focused refactoring + manual regression test.

#### **D2. Proxy token registry never garbage-collected**

`SonosHttpProxy.tokens` (a `ConcurrentHashMap<String, Entry>`) accumulates
one entry per track loaded. Never cleared until disconnect. Memory leak is
small (~200 B per entry) but real, especially for long sessions.

**Fix shape**: TTL-based eviction (e.g. drop entries older than 10 min) or
LRU cap (last 32 tracks). Run from a coroutine scheduled at registration
time, or piggyback on the existing 1Hz polling tick.

Estimate: 30 min.

#### **D3. No tests at all**

Everything is verified by manual ADB testing against a real Sonos. Zero
unit tests, zero instrumentation tests. Risky for a feature that touches
playback, network, and lifecycle.

**Fix shape**:
- Unit: `DidlBuilder` (XML escaping, duration formatting), `SonosHttpProxy`
  (range forwarding, token registration/expiry).
- Integration: a fake `AVTransport` that mimics Sonos state transitions, fed
  into `UpnpCastController` to verify the polling loop, primeSonos flow,
  swap behavior.
- End-to-end with a real device is fine to keep manual; add a checklist.

Estimate: 1 day for a meaningful first pass.

### P1 — UX polish, ship without if needed

#### **U1. Volume slider is laggy (200-400 ms response)**

Inevitable: every move is a SOAP `RenderingControl.SetVolume` round-trip
across the LAN. Mitigation options:
- Debounce: only send the final value after the user releases the slider
  (already partially the case via `onValueChangeFinished` in test screen, but
  the main-player slider sends every intermediate value).
- Local optimistic update + reconcile from polling.

#### **U2. Transient stutter when seeking near track end**

If you seek to >95% of the track, `maybePreloadNext` may decide to push
SetNextAVTransportURI mid-seek, which can confuse Sonos's transport state.

**Fix**: skip preload while the seek lock is active.

#### **U3. Polling continues during pause**

We poll AVTransport at 1 Hz even when the user has paused. Wastes a tiny
bit of LAN traffic. Could throttle to 5 s when paused, restore to 1 s on
play.

#### **U4. Cast button always visible — no hint that Sonos is unavailable**

If the user is offline or no Sonos is on the LAN, tapping the button shows
an empty picker. Better: grey out the button or show a tooltip.

#### **U5. Sonos native app shows "Metrolist" as the source name with no track metadata**

We pass title/artist/album in DIDL but Sonos's audioBroadcast/musicTrack
handler ignores them when the URI scheme is `http://` (our proxy URL).
Workaround: serve a sidecar `/sonos/{token}/meta.xml` and reference it as
DIDL `albumArtURI`, or use Sonos's RINCON SMAPI scheme (huge effort, not
worth it).

### P2 — nice-to-have

#### **N1. Multi-room (group play)**

Right now we connect to one device. Sonos supports zone groups — joining
multiple speakers and playing in sync. Would need `Zone Group Topology`
SOAP service + UI to pick the group instead of a single device.

#### **N2. AirPlay / Chromecast Audio receiver discovery**

The UPnP discovery is generic enough that it could surface other DLNA
renderers (AirPlay-via-Shairport, Linn, BlueOS). Worth a single test before
claiming generic UPnP support.

#### **N3. Background-safe proxy lifecycle**

When the user puts the phone in pocket and Android starts to evict the
service, the embedded HTTP server may get killed. Audio on the Sonos
stops. Same applies to Wi-Fi sleep / VPN reconnects.

Mitigations: keep the proxy bound to the foreground service, request
WiFi lock while casting, document limitations.

#### **N4. PR upstream to Metrolist**

Currently a fork. Once D1-D3 are done, open a PR upstream — but only after
discussing scope with Metrolist maintainers (they may not want a new HTTP
server in the binary, and we should respect that).

---

## Architecture notes (for future-us)

### Why proxy + Content-Type override?

YouTube `googlevideo.com` URLs return `Content-Type: video/mp4` even for
audio-only itag-140 streams. Sonos does a strict MIME check on
SetAVTransportURI and returns SOAP 714 "Illegal MIME-Type" on anything that
isn't `audio/*`. Re-serving the same bytes through our proxy with
`audio/mp4` is the minimum-viable hack. Alternatives we rejected:
- **Transcode to MP3** (YouSonos approach): needs FFmpeg, +15 MB binary,
  CPU/battery cost, audio quality loss.
- **MP4→ADTS bit-by-bit repack** (deadf00d approach): zero re-encoding but
  ~600 LOC of MP4 atom parsing + ADTS header construction. Bug-prone.
- **`x-rincon-mp3radio:` prefix**: tested, doesn't help — Sonos still
  rejects on MIME, just with different code paths.
- **Empty DIDL metadata**: tested, also rejected with 714.
- **Wildcard MIME `*` in DIDL**: tested, rejected with 714.

### Why Media3 SimpleBasePlayer instead of a custom Player?

`SimpleBasePlayer` gives us state diffing, listener fan-out, and lifecycle
for free. We only implement `getState()` + `handleX()` overrides. Same
pattern Google uses for `CastPlayer` — battle-tested.

### Why polling instead of GENA eventing?

UPnP's GenericEventNotificationArchitecture (GENA) requires:
- A second HTTP server on the phone (for the NOTIFY callbacks)
- SUBSCRIBE/UNSUBSCRIBE lifecycle (Sonos times out subscriptions in 30 min)
- Renewal logic
- Garbage collection of stale subscriptions
- More NAT/firewall consideration on the LAN

Polling at 1 Hz is ~120 B/s of LAN traffic per cast session. Trivial. Skip
GENA unless we hit a real reason.

---

## Suggested next-week ordering

Day 1 morning: **D1** (the player-routing refactor) — eliminates almost all
the user-visible weirdness and is the foundation for everything else.

Day 1 afternoon: **D2** + **U2** + **U3** — small focused fixes.

Day 2: **D3** (tests). Start with `SonosHttpProxy` since it's pure-IO and
testable without a real device.

Then evaluate whether to do **U1**, **U4**, **U5**, **N1** based on how
much you actually use the feature day-to-day.

PR upstream after D1+D3 are done.
