# Screen Translator v0.5

Android 10+; Redmi Note 8 Pro; no ads.

Changes:
- Default: translations directly over original text.
- Full screen or drag-to-select region.
- Auto language ID (Chinese, English, Japanese, Korean OCR); output Russian.
- Font size 10–30sp, default 14sp.
- Black on white or white on black.
- Separate window retained as secondary mode.
- Language models downloaded at runtime, not during APK installation. First translation in a new language needs Internet and may take time.

## Updating via GitHub Desktop
Copy all contents over the cloned repository, commit, push. Download successful APK artifact from GitHub Actions.

## Known limitations
- Android screen-capture permission is required when starting the service.
- Overlays approximate original line positions and do not reconstruct image backgrounds.
- Auto language ID supports OCR for Chinese, Latin, Japanese and Korean scripts, not all languages.
- Initial downloads may be slow. OCR of multiple scripts may return imperfect duplicate or wrong text.
- Not compiled or device-tested in this environment.
