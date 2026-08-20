import type { CapacitorConfig } from '@capacitor/cli';

declare const process: {
    env: {
        CAPACITOR_SERVER_URL?: string;
    };
};

/**
 * This wrapper ships no web assets of its own: it points a WebView at the
 * deployed Let's Chat React app. `webDir` exists only to satisfy the CLI and to
 * provide the offline fallback page in `src/`.
 *
 * Override for local testing against the Vite dev server, e.g.
 *   CAPACITOR_SERVER_URL=http://192.168.1.10:8081 npx cap sync android
 * Use the machine's LAN IP rather than localhost — on a device or emulator
 * `localhost` resolves to the handset itself. Note that plain HTTP also means no
 * secure context, so WebCrypto is unavailable and end-to-end encryption
 * silently no-ops; use it for layout work, not for testing encrypted messages.
 */
const serverUrl = process.env.CAPACITOR_SERVER_URL?.trim() || 'https://letschat-hillbcs.vercel.app';

const config: CapacitorConfig = {
    // Distinct from the Laravel messenger's com.hillbcs.chat: they are separate
    // installable apps and Android keys the install by application id.
    appId: 'com.hillbcs.letschat',
    appName: "Let's Chat",
    webDir: 'dist',
    server: {
        url: serverUrl,
        cleartext: serverUrl.startsWith('http://'),
        /*
         * Hosts the app legitimately navigates to, which would otherwise be
         * handed to the external browser and break the flow mid-way:
         *
         *  - the chats backend owns the SSO exchange and serves the call shell
         *    (/calls/{room}), which the React app opens via window.open;
         *  - the workspace is where the Chat Mini plugin lives and where SSO
         *    launch URLs originate;
         *  - the meet domain is what the call shell hands to Jitsi.
         *
         * Anything not listed here leaves the app, so a call would open in
         * Chrome with no way back.
         */
        allowNavigation: [
            'chats.hillbcs.com',
            'workspace.hillbcs.com',
            'meet.hillbcs.com',
        ],
    },
    android: {
        allowMixedContent: false,
        // Lets the WebView keep focus and selection behaviour in the composer's
        // contenteditable rather than the native input overlay stealing it.
        captureInput: true,
    },
    plugins: {
        Keyboard: {
            /*
             * The composer is pinned to the bottom of the viewport. Without this
             * the soft keyboard covers it: Android's default is to pan the whole
             * WebView, which pushes the input off-screen and leaves the user
             * typing blind. `native` resizes the WebView instead, so the
             * composer comes to rest directly above the keyboard.
             */
            resize: 'native',
            resizeOnFullScreen: true,
        },
        SplashScreen: {
            launchAutoHide: true,
            launchShowDuration: 100,
            backgroundColor: '#ffffff',
            showSpinner: false,
        },
        StatusBar: {
            // The React app draws its own header and reads --app-inset-top, so
            // the status bar stays a separate strip instead of being drawn under.
            overlaysWebView: false,
            /*
             * Beware the naming: in Capacitor, `DARK` describes the *background*
             * the bar is sitting on, so it renders light (white) content. Pairing
             * it with a white backgroundColor gives white icons on white, which is
             * why the clock and battery were invisible. On the brand blue below,
             * light content is exactly right.
             */
            style: 'DARK',
            // The app's own declared theme colour, from
            // let-s-chat-frontend/public/manifest.webmanifest. Sits between the
            // light and dark values of the `--primary` token (#0079e9 / #1b90fe).
            backgroundColor: '#0b82ee',
        },
    },
};

export default config;
