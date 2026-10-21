# Screen Translator v0.2 (Android 10 / Redmi Note 8 Pro)

Experimental source project. Chinese OCR -> Russian translation, on-demand floating TR button, two display modes. No ads. The translation model is downloaded at first use (internet required).

## Build without Android Studio

1. Create an empty public/private GitHub repository.
2. Upload the CONTENTS of this folder to the repository root, including `.github/workflows/build-apk.yml` (do not upload the enclosing folder as an extra level).
3. Open **Actions** -> **Build Android APK** -> **Run workflow**.
4. When the build succeeds, download the `ScreenTranslator-v0.2-debug-apk` artifact and extract `app-debug.apk`.
5. Install APK on Android 10, permit overlay windows and screen capture.

This code has not been compiled or device-tested here. If GitHub Actions reports errors, share the build log for corrections. Overlay mode is experimental and does not remove original text background. Some apps prohibit screen capture. MIUI may require enabling background/autostart permission.
