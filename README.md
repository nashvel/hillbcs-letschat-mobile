# Let's Chat — Android wrapper

A Capacitor shell that points an Android WebView at the deployed **Let's Chat**
React app (`let-s-chat-frontend`). It ships no UI of its own: `server.url` in
`capacitor.config.ts` loads the app over the network, so releasing a web change
needs no new build here.

Adapted from [`ckentdev/weburl-mobile-wrapper`](https://github.com/ckentdev/weburl-mobile-wrapper);
`origin` still points there.

## Identity

| | |
| --- | --- |
| Application id | `com.hillbcs.letschat` |
| App name | Let's Chat |

Distinct from the Laravel messenger's `com.hillbcs.chat` — they install as two
separate apps.

## Build and run

```bash
npm install
npm run build                 # builds the offline fallback page into dist/
npx cap sync android
npx cap open android          # or: cd android && ./gradlew assembleDebug
```

`JAVA_HOME` must be a JDK 21 or older toolchain; Gradle 8.13 does not support
JDK 25.

## Pointing at a different environment

`CAPACITOR_SERVER_URL` overrides the target, baked in at `cap sync` time:

```bash
CAPACITOR_SERVER_URL=http://192.168.1.10:8081 npx cap sync android
```

Use the machine's LAN address, not `localhost` — on a device or emulator
`localhost` is the handset. Plain HTTP flips `cleartext` on automatically.

Two caveats when testing over HTTP:

- it is not a secure context, so `crypto.subtle` is absent and end-to-end
  encryption silently no-ops. Fine for layout work, useless for testing
  encrypted messages;
- Vite must be reachable on the LAN (`--host`).

## What the shell adds beyond a plain WebView

Inherited from the upstream wrapper, in `MainActivity.java`:

- **`window.open` support** — popups open in a fullscreen dialog over the main
  WebView. The app relies on this: `openCallWindow()` in `callService.ts` opens
  the call shell in a named window, and the Pop out control moves a running call
  into one.
- **Downloads** — a `DownloadListener` hands `Content-Disposition: attachment`
  responses to the system `DownloadManager`, with the WebView's cookies attached.
- **Cookies** — third-party cookies are enabled and flushed on pause/stop.
  Relevant because the app is loaded from a different origin than the API, so
  cookies here are cross-site.

Configured in `capacitor.config.ts`:

- **`Keyboard.resize: 'native'`** — the composer sits at the bottom of the
  viewport, and Android's default is to pan the whole WebView, which pushes the
  input off-screen. Resizing keeps it directly above the keyboard.
- **`allowNavigation`** — the chats, workspace, and meet hosts. Without them the
  SSO exchange and the call shell would be handed to Chrome with no route back.

The manifest already declares camera, microphone, Bluetooth, and storage
permissions for WebRTC calls and file pickers.

## The app knows it is wrapped

`callService.ts` checks `window.Capacitor?.isNativePlatform?.()` (and a
`__HILLBCS_CAPACITOR_NATIVE` fallback) to decide how to open a call — same tab
rather than a popup. That detection predates this wrapper and needs confirming on
a device, since the bridge has to be injected into a remotely-loaded page for it
to fire.

## Not done

- iOS. Android only.
- Launcher icons and splash art are still upstream's.
- Push notifications. `@capacitor/push-notifications` is not installed and there
  is no `google-services.json`; `app/build.gradle` applies the Google Services
  plugin only when that file exists.
- No release signing config.

## Push notifications (Firebase Cloud Messaging)

The server side already exists and is shared with the Laravel messenger — there
is no new backend to build:

| Piece | Where |
| --- | --- |
| Sender | `hillbcs-chats/app/Services/FirebasePushService.php` (FCM HTTP v1) |
| Queue job | `hillbcs-chats/app/Jobs/DeliverNativePushNotificationJob.php` |
| Token store | `device_push_tokens` table |
| Register / withdraw | `POST` / `DELETE /api/react/v1/device-push-tokens` |
| Credentials | `FIREBASE_PROJECT_ID`, `FIREBASE_CREDENTIALS` in the chats `.env` |

This wrapper contributes the native half: `@capacitor/push-notifications`,
`firebase-messaging`, and the `POST_NOTIFICATIONS` permission. The web app
(`let-s-chat-frontend`) contributes registration, in
`src/features/notifications/services/nativePush.ts`, because only it holds the
bearer token that ties a device token to an account.

### One manual step is still outstanding

**Firebase has no Android app registered for `com.hillbcs.letschat` yet, so push
cannot work until this is done.** The existing `google-services.json` belongs to
`com.hillbcs.chat` and is not interchangeable — the package name is baked into it.

1. In the [Firebase console](https://console.firebase.google.com/), open the
   project already used by Hillbcs Chats (the one `FIREBASE_PROJECT_ID` names).
   Use the same project, not a new one, so the server's existing service-account
   credentials keep working.
2. Add app → Android → package name `com.hillbcs.letschat`.
3. Download the generated `google-services.json` into
   `letschat-mobile-wrapper/android/app/`.
4. Rebuild. `android/app/build.gradle` applies the Google Services plugin only
   when that file is present, which is why the project builds without it today
   and why push silently does nothing.

A debug build needs no SHA-1 fingerprint; FCM does not require one. Add one later
if you adopt Firebase Auth or Dynamic Links.

`google-services.json` is not secret — it ships inside every APK — but it is
environment-specific, so decide deliberately whether to commit it.

### Channels

`FirebasePushService` names a channel on every message, and Android drops a
notification whose channel does not exist. Two are created from the web layer on
first run, and the ids must stay in step with the server:

| Channel id | Used for |
| --- | --- |
| `hillbcs_chat_messages` | messages, mentions, chat updates |
| `hillbcs_chat_calls` | incoming voice and video calls |

### Verifying

Once `google-services.json` is in place:

```bash
npm run sync && cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -iE "push|fcm|firebase"
```

Sign in, accept the notification prompt, then confirm a row appeared:

```bash
cd ../../hillbcs-chats
php artisan tinker --execute="echo App\Models\DevicePushToken::latest()->first()?->toJson();"
```

No row means registration never reached the server; `registrationError` in
logcat almost always means a missing or mismatched `google-services.json`.

### Not wired up

- **iOS.** Would additionally need the `APNS_*` settings already stubbed in
  `config/services.php`.
- **Custom call notifications.** The Laravel app ships a
  `HillbcsMessagingService` that renders full-screen ringing UI with Join and
  Dismiss actions on its own `hillbcs_chat_calls_v2` channel. This wrapper leans
  on Capacitor's default handler, so an incoming call arrives as an ordinary
  notification. Port that service if you want it to ring.

## Releases and in-app updates

`.github/workflows/release.yml` builds a signed APK on every push to `main` and
publishes it as a GitHub Release. The running app compares itself against the
newest release and offers the upgrade.

### Versioning

`versionCode` comes from `BUILD_NUMBER`, which CI sets to `github.run_number`;
`versionName` is `1.0.<run_number>`. Nothing is committed back, so a release
cannot retrigger its own workflow. Locally, with neither variable set, the build
falls back to `1` / `1.0-dev`.

A monotonic `versionCode` is not cosmetic: Android refuses to install an APK whose
`versionCode` is not higher than the installed one.

### Required secrets

| Secret | Why |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | base64 of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | |
| `ANDROID_KEY_PASSWORD` | |
| `GOOGLE_SERVICES_JSON` | contents of `google-services.json`; without it the build succeeds but push is dead |

**The signing key must never change.** Android identifies an app by package name
plus signing certificate, so a build signed with a different key is a different
app to the installer and the upgrade is refused with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — users would have to uninstall first and
lose their session. The workflow fails deliberately rather than fall back to a
debug key. Keep a backup of the keystore somewhere you cannot lose it; losing it
means no existing install can ever be upgraded again.

Generate one with:

```bash
keytool -genkeypair -v -keystore release.keystore -alias letschat \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # paste into ANDROID_KEYSTORE_BASE64
```

### Forcing an update

`update-policy.json` holds `minimumBuild`. Clients below it get a block they
cannot dismiss; clients above it but behind the newest build get a dismissible
bar. Raise it only when an old shell genuinely cannot work against the current
backend, since it locks people out until they install.

The value is written into the release body as a `minimumBuild: N` line, which is
what the app parses.

### Why the app reads the GitHub API, not `update.json`

`api.github.com` sends `Access-Control-Allow-Origin: *`; release asset downloads
do not, so a `fetch` of the published `update.json` would be blocked by CORS. The
client therefore takes the build from the tag, the APK from the asset list, and
the policy from the release body — one CORS-clean request. `update.json` is still
published for anything server-side that wants it.

The APK download itself runs through Android's `DownloadManager`, where CORS does
not apply.

### What "auto update" means here

Off Play, there is no silent self-update, by design. The flow is:

1. the app notices it is behind and shows the prompt;
2. the user taps Update; `DownloadManager` fetches the APK;
3. the system installer opens and the user confirms.

On Android 8+ they must also grant "install unknown apps" once — the app detects
that and opens the right settings screen. The download URL is checked against a
host allowlist in `UpdateInstaller.java` before anything is fetched, so a
compromised page cannot point the installer at an arbitrary APK.

Client code lives in `let-s-chat-frontend`:
`src/features/updates/services/appUpdateService.ts` and
`components/UpdateGate.tsx`. It sits on the web side on purpose — update logic
shipped inside the APK could only be fixed by the update it is meant to deliver.
