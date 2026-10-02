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

The catch-up engine is source-agnostic, but the sources bundled here are
deliberately restricted to material that is unambiguously redistributable:

| Station | Source | Licence |
|---|---|---|
| Archive Radio | Old-time radio collections on the Internet Archive | Public Domain |
| Archive Radio | LibriVox recordings | Public Domain |
| Gutenberg Audio | Project Gutenberg audio books | Public Domain |

The archive provider **refuses** any item whose archive.org licence is
unknown, NonCommercial, or NoDerivs — see
`InternetArchiveProvider` / `ArchiveMetadataParser`. Unknown provenance is
treated as "do not ship", never as "probably fine".

## Architecture

```
UI (Compose)          Home · Player · Stations · Downloads
      │
ViewModels            one per screen, StateFlow-only
      │
RadioRepository       interface; Room-backed implementation
      │
      ├── SyncWorker (WorkManager) ── Catch-up engine
      │                                   ├── InternetArchiveProvider
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
ArchiveMetadataParserTest   archive.org JSON → programmes, licence gating
RssFeedParserTest           feed parsing, incl. Android-DOM pitfalls
DownloadPlannerTest         catch-up vs capture decisions
RetentionPolicyTest         keep-days / keep-N rules
HlsPlaylistTest             master→variant, segment lists
SyncTimeTest                daily sync anchor maths
RecordingFilesTest          on-disk layout + sanitisation
AudioDownloaderTest         Content-Range parsing
MappersTest                 Room entity ↔ domain mapping
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
