# ArchiveSift handoff

- Repository: `TheOsmanYILDIRIM/ArchiveSift`. Default branch: `main`.
- MVP merged in PR #1. Source commit tested on main: `2203a786233a0181083e332aa64e809ed9a66a6b`. Feature head: `a62d3856c91f27192f02adf759b7d05a56cc81e8`; its source tree is identical to the merge commit.
- CI: [Main debug run 43943136770](https://github.com/TheOsmanYILDIRIM/ArchiveSift/actions/runs/43943136770) passed core tests, Android lint, APK assembly, and the APK manifest permission check. The matching feature run reported 10 tests, zero failures, zero skipped.
- Debug APK: [GitHub Actions artifact](https://github.com/TheOsmanYILDIRIM/ArchiveSift/actions/runs/43943136770/artifacts/14263212784). Extracted APK SHA-256: `1da6e3b8714a9e5450f651db53c41e5d8c1e9b05f1d7cbeb96ef3e0a17b07962`. This is not a signed release APK.
- [Release run 43943136519](https://github.com/TheOsmanYILDIRIM/ArchiveSift/actions/runs/43943136519) passed core tests, release lint and unsigned release assembly. Signing stopped because `ANDROID_KEYSTORE_BASE64` was unavailable. No release was published.
- Add the long-lived ArchiveSift signing keystore and the corresponding four Actions secrets, then rerun the failed signing job or dispatch **Publish signed APK** on main with tag `v0.1.0`. Do not replace the key with a debug or per-run key. See README for the secret names.
- Emulator and physical-device tests: not performed.

## Launcher and startup branding

- The generated purple archive mark is stored once as a 192 × 192 WebP (5,294 bytes) and shared by the adaptive launcher and launch screen.
- Android 12+ uses the native splash screen with a deep-purple background. Android 8–11 uses a dark gradient launch window and the same centered mark. No artificial delay or simulated progress bar is added; the Compose screens still follow system theme settings.
- This change does not modify release signing. Verify the GitHub Actions result for this commit separately from cold-start behavior on a device.
- Branding CI run [43950502471](https://github.com/TheOsmanYILDIRIM/ArchiveSift/actions/runs/43950502471) failed Android lint: the base theme used `windowLightNavigationBar` (API 27+) with minSdk 26. Removed the redundant `false` override; the dark parent theme keeps light navigation icons by default. The dark launch backgrounds and icon are unchanged. Verify the replacement CI and device startup separately.

## Regex input

- Home and browser regex rules now accept clipboard text and a single UTF-8 regex file. The 1,024-character truncation was replaced with explicit validation (regex: 32,768; glob: 1,024).
- A long-pattern core regression test checks that alternatives near the end still match. Check this commit's Android CI and test paste/file selection on a device separately. Signing configuration is unchanged.
