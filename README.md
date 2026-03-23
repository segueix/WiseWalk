WiseWalk (WebView) v4
=================

Android Studio project that wraps wisewalk.html in a WebView.

- INTERNET permission included (Google Fonts in HTML)
- Back navigation via OnBackPressedDispatcher (non-deprecated)

Run
- Open folder in Android Studio
- Let Gradle sync
- Run on a device/emulator

GitHub Actions
- The repository includes `.github/workflows/android-apk.yml` to build a debug APK in GitHub Actions.
- The workflow runs on pull requests, on pushes to `main`/`master`, and can also be launched manually with `workflow_dispatch`.
- Each run uploads `app/build/outputs/apk/debug/app-debug.apk` as the `app-debug-apk` artifact.
- More setup details are documented in `docs/github-actions-apk.md`.

APK generation and change monitoring
- `./gradlew assembleDebug` builds a debug APK at `app/build/outputs/apk/debug/app-debug.apk`
- The repo does not commit `gradle-wrapper.jar`; if it is missing, `./gradlew` falls back to a local `gradle` installed in your PATH
- `scripts/watch-apk.sh` runs `./gradlew --continuous assembleDebug` to rebuild while you edit files
- Before using the terminal build, define `ANDROID_HOME` or `ANDROID_SDK_ROOT` to point to your Android SDK

Notes
- Step tracking uses Android TYPE_STEP_COUNTER and sends stats to the WebView.
- It counts while the app is open (simple MVP).
