# ArchiveSift

ArchiveSift is an Android app for finding files in ZIP archives and folders and exporting only what you need. It works offline and accesses only the files and folders you pick.

## Quick start

1. Tap **Open ZIP** or **Open Folder**.
2. Search by name or path, or open **Filters** to add include and exclude rules.
3. Select individual files or all matches. Tap **Preview export** to save or share a ZIP, export to a folder, or save a CSV file list.

## Regex and presets

On the home screen, enter a **Quick regex**, tap **Paste**, or tap **Choose file** to load a single regex from a UTF-8 text file. The rule applies to the next ZIP or folder you open. Tap **+** to save it as a named preset. A quick regex takes priority over a queued preset.

In **Filters**, you can paste or load a regex into each regex rule, mix glob and regex include/exclude rules, or save the entire set as a preset. Use **Presets → Import JSON** to load a preset with multiple rules.

Rules match relative paths with `/` separators. Regex uses RE2/J: `(^|/)(stone|grass_block)\.json$` matches either filename at any depth. Globs match the whole path: `**/*.properties` finds properties files at any depth. Includes are combined with OR; excludes always win. Regex accepts up to 32,768 characters and glob up to 1,024; oversized input is rejected, not shortened. A text file may end in a single line break, which is removed before matching.

## Build

Use JDK 17, Android SDK 35, and Gradle 8.9:

```sh
gradle :core:test :app:lintDebug :app:assembleDebug
```

GitHub Actions publishes debug APKs as workflow artifacts. The **Publish signed APK** workflow publishes to Releases and requires `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
