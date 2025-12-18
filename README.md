WiseWalk (WebView) v4
=================

Android Studio project that wraps wisewalk.html in a WebView.

- INTERNET permission included (Google Fonts in HTML)
- Back navigation via OnBackPressedDispatcher (non-deprecated)

Run
- Open folder in Android Studio
- Let Gradle sync
- Run on a device/emulator


Notes
- Step tracking uses Android TYPE_STEP_COUNTER and sends stats to the WebView.
- It counts while the app is open (simple MVP).
