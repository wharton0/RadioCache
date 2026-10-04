# RadioCache

**Offline caching for radio audio, built for the car.**

RadioCache downloads audio over Wi-Fi so it is available when you are out of
coverage — then plays it back offline with full transport controls and
resume-from-position.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-purple.svg)
![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)

---

## What it does

| | |
|---|---|
| **Scheduled catch-up** | Syncs on Wi-Fi on an interval or at a daily time you choose |
| **Resumable downloads** | Interrupted transfers continue via HTTP `Range` instead of restarting |
| **Offline playback** | Media3 with background service, lock-screen and notification controls, resume position |
| **Retention** | Age-based cleanup of cached audio; abandoned `.part` files swept too |
| **Live-capture fallback** | Generic HLS/direct-stream recorder, used only when a show has no catch-up audio |
| **Open catalogue** | Ships with public-domain sources only (see below) |

## Content and licensing

This build ships **no commercial broadcaster content**.

The catch-up engine is source-agnostic, but the bundled sources are restricted
to material that is verifiably redistributable:

| Station | Source | Licence evidence |
|---|---|---|
| Commons Radio | Wikimedia Commons audio | Per-file licence metadata is **mandatory** on upload |
| LibriVox | Librivox book feeds | Project-level public-domain dedication |

### How the licence gate works

`LicencePolicy` requires **both**:

1. The host is allow-listed (`TRUSTED_HOSTS`), each with a recorded rationale.
2. No item-level licence forbids us — **NonCommercial** and **NoDerivatives**
   are hard vetoes, checked *before* any permissive substring is considered
   (so `CC BY-NC-SA` can never match on "cc by").

| Host | Item licence | Verdict |
|---|---|---|
| Commons | Public domain / CC0 / CC BY | `VERIFIED_LICENCE` — ship |
| Commons | blank | `REFUSED` — cannot prove provenance |
| Commons | CC BY-NC-*, CC BY-ND-* | `REFUSED` — veto |
| LibriVox | *(no field)* | `ATTESTED_HOST` — ship on the site's documented pledge |
| anything else | anything | `REFUSED` |

### Why not archive.org

Measurement, not assumption. An `advancedsearch` for
`usage:"Public Domain Mark 1.0" AND mediatype:audio` returns **0** items, and
3 of 5 candidate OTR items had **no `usage` field at all**. A gate that depends
on that field rejects essentially everything, so archive.org was dropped as the
primary source in favour of Commons, where the field is mandatory.

## Architecture

```
UI (Compose)          Home · Player · Stations · Downloads
      │
ViewModels            one per screen, StateFlow-only
      │
RadioRepository       interface; Room-backed implementation
      │
      ├── SyncWorker (WorkManager) ── Catch-up engine
      │                                   ├── CommonsProvider   (mandatory licence)
      │                                   ├── LibrivoxProvider  (host-attested)
      │                                   └── RssCatchUpProvider
      │                              └── DownloadPlanner → AudioDownloader
      │
      └── CaptureWorker (WorkManager) ── Live Capture engine
                                          └── HttpStreamCaptureEngine
                                                   ├── direct streams
                                                   └── HLS (.m3u8)
      │
MediaSessionService   background playback + notification
```

Design rules the code holds to:

- **No station logic in the UI.** Screens render state; providers own sources.
- **Never fabricate a download URL.** If audio needs a login, DRM or a signed
  URL, the provider returns metadata with a null audio URL and the planner
  degrades to live capture or an honest failure. Access controls are never
  bypassed.
- **One bad source never fails a sync.** Per-show failures are logged and
  skipped; the sync reports `ok` / `failed` counts.
- **Providers are unit-testable.** URL and metadata mapping lives in pure
  objects with no I/O (`ArchiveMetadataParser`, `RssFeedParser`,
  `DownloadPlanner`, `RetentionPolicy`, `HlsPlaylist`).

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36, minSdk 26).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Release builds read signing material from `keystore.properties` in the repo
root. It is **not** committed; the build falls back to debug signing when
absent so a fresh clone still produces an installable APK.

## Tests

Unit tests run on the JVM and cover the parts that can silently rot:

```
CommonsMetadataParserTest  Commons JSON → programmes, NC/ND vetoing
LicencePolicyTest          licence gating incl. NC/ND veto precedence
LibrivoxFeedParserTest     book-feed parsing
RssFeedParserTest          feed parsing, incl. Android-DOM pitfalls
DownloadPlannerTest        catch-up vs capture decisions
RetentionPolicyTest        keep-days / keep-N rules
HlsPlaylistTest            master→variant, segment lists
SyncTimeTest               daily sync anchor maths
RecordingFilesTest         on-disk layout + sanitisation
AudioDownloaderTest        Content-Range parsing
MappersTest                Room entity ↔ domain mapping
```

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | fetching metadata and audio |
| `ACCESS_NETWORK_STATE` | Wi-Fi-only scheduling |
| `POST_NOTIFICATIONS` | sync progress (Android 13+) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | long transfers |
| `WAKE_LOCK` | transfers that outlive a screen-off |
| `MODIFY_AUDIO_SETTINGS` | Media3 audio focus |

## Adding a source

Implement `CatchUpProvider`:

```kotlin
class MyProvider(private val http: OkHttpClient) : CatchUpProvider {
    override val providerId = "mine"
    override fun supports(show: Show) = /* … */
    override suspend fun fetchProgrammes(show: Show, limit: Int) =
        /* Result.success(listOf(BroadcastProgramme(…, resolvedAudioUrl = url))) */
}
```

Then register it in `AppContainer.catchUpProviders` ahead of the generic RSS
fallback. Keep the mapping logic in a pure object so it stays testable.

## Licence

GPL-3.0-or-later — see [LICENSE](LICENSE).

Only interaction with it is through its documented/public HTTP interfaces; no
access control, DRM, or geo-restriction is circumvented.
