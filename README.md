# TVDrop — drop an APK onto your Android TV from any browser

TVDrop runs a tiny web server on the TV. Open the app; it shows `http://<tv-ip>:8766`. Open that
on a phone or laptop on the same Wi-Fi and:

- **drop an APK** (or tap to choose) — it is pushed straight to the TV, and by default the
  installer opens on the TV as soon as the upload finishes ("Open the installer on the TV after
  upload" checkbox);
- or **paste a download link** — the TV downloads it itself (redirects and
  `Content-Disposition` names are honoured);
- the list shows progress, and has Install / Open / Delete / Cancel buttons per file.

On the TV: OK on a file → Install (APK) or Open (anything else) / Delete. There is also a text box
for typing a link with the remote, for when nothing else is at hand.

After a successful install the app asks **"Delete <file>?"** — Delete / Keep / Always delete
(the last one remembers the choice). Success is detected from the installer's result, with the
`PACKAGE_ADDED` broadcast as a fallback.

First install ever: Android opens "Install unknown apps" for TVDrop. Turn it on, press Back,
and the install continues by itself.

Clicking an `.apk` link in a browser on the TV also offers TVDrop as a handler.

Built for a Mi Box; should work on any Android TV / Google TV box (minSdk 24).

## Build / install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug -q     # app/build/outputs/apk/debug/app-debug.apk
./install.sh <tv-ip>           # adb over Wi-Fi, if debugging is on
```

To get the very first copy onto the TV you need another route once (serve the APK from your
computer and type the URL in the TV browser, or adb). After that, updates of TVDrop itself can be
dropped into TVDrop.

## Layout

- `App.kt` — starts `Server` with the process, so the phone can push files while the activity is
  in the background.
- `Server.kt` — plain `ServerSocket` HTTP server on port 8766 (no dependencies). Serves the
  single-page UI at `/`, plus the small JSON API the page uses: list, download-by-link, upload,
  install, open, delete, cancel.
- `Downloads.kt` — folder = `getExternalFilesDir(null)`; in-flight items (status, progress,
  error) plus files already on disk. Handles both URL downloads and uploads from the browser.
- `MainActivity.kt` — the TV screen: shows the address, lists files, Install/Open/Delete with the
  remote, the "Delete after install?" prompt, and the `PACKAGE_ADDED` fallback.
- `res/xml/paths.xml` — FileProvider paths so the package installer can read the APK.
