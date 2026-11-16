# ArchiveSift handoff

The Android MVP and mobile UI are transferred from source snapshot d149b7226694904ad37139b581ee1af8982c980e to the GitHub branch feature/android-mvp. The source snapshot is not the new GitHub commit ID.

- Linux/JVM: eight local independent checks passed before the transfer; they do not substitute for the full RE2/J test suite.
- GitHub Actions: Android tests, lint, APK build, and signing must be independently verified on their exact commit before marking the delivery complete.
- Emulator and physical device: not tested.
- Release: requires the repository's existing long-lived signing keystore and its four Actions secrets. The workflow does not generate a key or reuse the debug key. Manual dispatch is supported on main. A main push that adds or changes the Android app version configuration also triggers the signed workflow.
