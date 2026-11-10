# Releasing DuoBeam

## GitHub release assets

Every release (`vX.Y.Z` tag) ships these files:

| File | What |
|---|---|
| `DuoBeam-androidtv-X.Y.Z.apk` | Android TV receiver |
| `DuoBeam-handheld-X.Y.Z.apk` | Thor (handheld) app |
| `DuoBeam-pc-X.Y.Z.exe` | Windows PC receiver |
| `DuoBeam-tizen-X.Y.Z.wgt` | Samsung Tizen receiver |
| `SHA256SUMS.txt` | checksums |

**Keep the Tizen file name pattern `DuoBeam-tizen-<version>.wgt`.** The Apps2Samsung
community catalog finds the Tizen build with the regex `^DuoBeam-tizen-.*[.]wgt$`.
If you rename the file, the catalog skips DuoBeam (or keeps an old build) until its manifest is updated.

Also:

- Exactly one `.wgt` per release. A second one, for example a debug build that also
  matches the pattern, makes the catalog pick whichever is listed first.
- Use versioned tags on a real commit, and don't mark Tizen releases as drafts.
  The catalog reads `releases/latest`, which skips drafts and prereleases.
- Keep the Tizen package ID `DuoBeamRcv` / app ID `DuoBeamRcv.DuoBeam` in
  `tizen-receiver/config.xml`. TV-Store and Apps2Samsung match installed apps
  on that ID, and changing it turns DuoBeam into a different app on every TV.
- Bump the `version` in `config.xml` with every release. The catalog shows that version,
  and the TV only updates when it is higher.

## Apps2Samsung community catalog (TV-Store)

- Manifest: `packages/Dysprohol__duobeam.json` in
  [Apps2Samsung/tizen-community-packages](https://github.com/Apps2Samsung/tizen-community-packages).
  It was submitted in [PR #250](https://github.com/Apps2Samsung/tizen-community-packages/pull/250) on 2026-11-10.
  The required checks (Validate Package Manifests: changes + validate) passed, and it is waiting
  for a maintainer to merge it.
- Category `casting` ("Casting & cameras"), source `release`, with this asset entry:

  ```json
  "assets": [{ "match": "^DuoBeam-tizen-.*[.]wgt$", "output_name": "DuoBeam.wgt" }]
  ```

  `assets[]` with `match` is required because the release has several assets. A plain
  `output_name` always takes the first asset, which is the Android TV APK, and the PR check rejects it.
  `output_name` (`DuoBeam.wgt`) is the app's file name inside the catalog bundle. The catalog
  identifies the app by it from one release to the next, so don't change it.
- The catalog re-signs the `.wgt` for each TV, so releases need no Samsung certificate.
  A TV that already has DuoBeam signed with another certificate (sideloaded with the
  developer's own profile) can refuse the update from TV-Store with a signature mismatch.
  Uninstall it once, or arm TV-Store with that same certificate.
- To change the description, category or asset pattern, open a PR against that manifest in the catalog repo.
