# ArchiveSift

ArchiveSift is a native Android utility for finding and exporting selected files from ZIP archives and folders. It works offline with no account, analytics, internet permission, or broad storage permission. Android's Storage Access Framework grants access to the files and folders you explicitly pick. A document provider or a sharing target may use the network independently of ArchiveSift; choose local storage and a local target if needed.

## Use ArchiveSift

1. **Open ZIP** or **Open Folder**. Switch between a flat list of matching files and a folder tree of matching paths. Empty directories are not listed or exported.
2. Search the filename or path, add rules, and check individual files or use **Select all matches**. The text search ignores case. Changing filters does not clear existing selections; preview shows all selected paths, including hidden ones.
3. Use **Preview export**. **Export as ZIP** opens Android's Save picker. **Share ZIP** stages a ZIP in private cache and opens the Android Share Sheet. When exporting to a folder, review the resolved paths before confirming: reuse exact-name folders, skip conflicts, or rename them with numbered suffixes. Existing files are never intended to be replaced. The destination is checked again before writing.
4. **Save file list (CSV)** writes UTF-8 relative paths and sizes; unknown sizes are blank and file contents are not opened. Paths are preserved verbatim in CSV; open untrusted file lists as text rather than allowing spreadsheet formulas to execute.

## Filtering and presets

**Quick regex:** Paste a pattern on the home screen, then tap **Open ZIP** or **Open Folder**. It acts as an include rule for that source only and clears after a successful open. Tap **+** beside the field and enter a name if you want to reuse it. Home presets can be queued with **Use next**; a typed regex overrides the queued preset for that open. Use **Filters** in the browser to edit include/exclude rules or switch to glob. The browser shows either **Matches** or **Folders** on a phone; changing views does not change the selection.

Rules operate on normalized, relative paths separated by `/`. ZIP backslashes become `/`, redundant separators and `.` segments are removed, and Unicode text is not transliterated or folded. Includes are ORed. With no includes, all safe files start eligible. A matching exclude always wins. An invalid rule shows an inline error and blocks matching and export until it is fixed.

| Mode | Behavior | Example |
| --- | --- | --- |
| Glob | Case-sensitive full-path match. `*` matches zero or more characters within a segment; `?` matches one. `**` must be an entire segment and spans zero or more directory levels. Characters such as `[`, `]`, `{`, and `}` are literal. | `**/*.properties` matches root and nested properties files. |
| Regex | RE2/J searches the path for a match; use anchors for an exact match. Case-sensitive by default; `(?i)` can enable case-insensitive matching. Backreferences, lookahead, lookbehind, and other non-RE2 constructs are unsupported. | See the regex example below. |

For example, `(^|/)(stone|grass_block)\.json$` matches either filename at the root or in a directory.

Save named presets locally or import/export versioned JSON. Imports validate the name and every rule; existing names are not silently overwritten. The app permits 32 rules per preset and 1,024 characters per pattern. Example preset:

```json
{
  "version": 1,
  "name": "Configuration files",
  "rules": [
    { "exclude": false, "mode": "Glob", "pattern": "**/*.properties" },
    { "exclude": true, "mode": "Regex", "pattern": "(^|/)private/" }
  ]
}
```

## Safety and limits

- ZIP browsing reads the central directory, rather than decompressing entries. A non-seekable provider can use a streamed private cache copy capped at 512 MiB. Stored and DEFLATE entries are supported; encrypted and multipart ZIPs, RAR, 7z, and other compression methods are unsupported.
- The source is rejected if it contains absolute paths, drive prefixes, traversal, ambiguous normalized duplicates, or file/directory collisions. Symlink entries are never materialized as links. Paths are limited to 64 segments and 4,096 characters. Enumeration limits are 50,000 entries/paths and 64 MiB of ZIP central-directory metadata.
- Contents are streamed and counted while exporting: at most 512 MiB per file and 2 GiB across a content export. ZIP local headers and selected payload size/CRC are checked on export. The app can browse a ZIP whose unselected content is damaged, because browsing intentionally does not decompress it.
- Cancellation is cooperative at I/O boundaries. A slow provider may delay cancellation of a call already in progress. Interrupted output is removed where permitted; the app reports partial items a provider will not remove. A folder export is not an atomic transaction. ZIPs staged for sharing are cached privately and expired staging files are pruned on later shares.

## Build and verify

Use JDK 17, Gradle 8.9, and Android SDK 35 with Build Tools 35.0.0. The minimum supported Android version is API 26. This snapshot does not bundle a Gradle distribution or wrapper; CI provisions Gradle through `setup-gradle`.

```sh
LANG=C.UTF-8 gradle :core:test :app:lintDebug :app:assembleDebug
```

`core` is pure Kotlin/JVM: source enumeration, normalized paths, RE2/J rules, export streaming, and folder conflict planning. `app` provides SAF adapters, private preset storage, cancellation/progress coordination, and Compose screens. The JVM parity test checks the same rule-filtered relative paths and verifies the exact exported ZIP entry names and bytes for ZIP and local folder fixtures. It does not substitute for Android document-provider tests.

### CI and signed publication

A push to `feature/android-mvp` runs tests, Android lint and the debug build, with a debug APK artifact when successful. Debug APKs are not signed releases. The independent **Publish signed APK** workflow builds release, signs and verifies it, and publishes `ArchiveSift-release.apk` plus `SHA256SUMS.txt` to GitHub Releases. Configure repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`; run only on a trusted commit. No keys or secrets are included.

GitHub requires a `workflow_dispatch` workflow to exist on the default branch for manual activation. The feature branch contains the workflow implementation, but the owner must explicitly register the release workflow on the default branch before it is dispatchable. No merge into the default branch is required to run the feature-branch push CI. After workflow registration, select the intended trusted ref and use a new version tag:

```sh
gh workflow run android-release.yml --ref feature/android-mvp -f tag=v0.1.0
```
