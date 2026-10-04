# Contributing

## Content licensing (the rule that matters)

RadioCache may only download audio the user is **allowed to redistribute**.
This is enforced in code by `LicencePolicy`, not by convention.

Adding a source means answering two questions:

1. **Is the host reviewed?** Add it to `LicencePolicy.TRUSTED_HOSTS` together
   with a `rationale` string explaining what the host publishes and what its
   licence commitment is. Provenance comes from the publisher, because
   per-item licence fields are frequently absent in practice.
2. **Does the item permit redistribution?** Every candidate must pass
   `LicencePolicy.evaluate`. NonCommercial and NoDerivatives are hard vetoes
   and are checked *before* any permissive substring — `CC BY-NC-SA` contains
   `"cc by"` and must still be refused.

Never bypass the gate. `RssLicenceGateTest` exists because the generic RSS
adapter once produced `resolvedAudioUrl` with no licence check at all.

### Do not add

- Commercial broadcaster catch-up/live streams
- Anything behind a login, DRM, signed URL, or geo-restriction
- Sources whose licence you cannot state precisely

## Verifying without network access

Licence and mapping logic is deliberately pure and unit-tested against
captured fixtures:

```bash
./gradlew :app:testDebugUnitTest
```

`CommonsFixtures`, `LibrivoxFeedParserTest` and the `ArchiveFixtures` lineage
embed real API responses. Extend those rather than writing live-network tests —
CI has no guaranteed access to Commons or LibriVox.

## Style

- No station/source logic in the UI layer.
- Never fabricate a download URL; return metadata with a null audio URL and
  let `DownloadPlanner` degrade honestly.
- One bad source must never fail a whole sync.
