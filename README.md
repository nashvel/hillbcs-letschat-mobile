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
