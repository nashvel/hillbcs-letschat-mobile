#!/usr/bin/env bash
#
# Creates the release signing keystore and prints what to paste into GitHub.
#
# Run this yourself rather than having anyone run it for you: it asks for a
# password, and the password plus the resulting key are the two things that must
# never end up in a transcript, a chat, or the repository.
#
# The key it produces is permanent. Android identifies an app by package name plus
# signing certificate, so every future release has to be signed with this exact
# key or the upgrade is rejected as a different app. Losing it means no existing
# install can ever be updated again — back it up somewhere durable before you
# ship anything to a real user.
#
# Usage:  ./scripts/make-release-keystore.sh [output-path]

set -euo pipefail

OUT="${1:-release.keystore}"
ALIAS="letschat"

if [ -e "$OUT" ]; then
    echo "Refusing to overwrite existing $OUT." >&2
    echo "If you replace a keystore already used for a release, every installed" >&2
    echo "copy of the app becomes un-upgradable. Move the old one aside first." >&2
    exit 1
fi

command -v keytool >/dev/null || { echo "keytool not found; install a JDK." >&2; exit 1; }

echo "Creating $OUT with alias '$ALIAS' (RSA 2048, ~27 year validity)."
echo "You will be asked for a keystore password; use the same one for the key."
echo

# -validity 10000 days: long enough that the key does not expire before the app
# is retired. A cert that expires mid-life cannot be renewed for an already
# published app.
keytool -genkeypair -v \
    -keystore "$OUT" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000

chmod 600 "$OUT"

echo
echo "Done. Fingerprint of the key you must keep forever:"
keytool -list -v -keystore "$OUT" -alias "$ALIAS" 2>/dev/null | grep -m1 "SHA256:" || true

# Written to a file rather than echoed, so the key material does not end up in
# terminal scrollback or get pasted somewhere by accident. Same ignore rules cover
# it as the keystore itself.
B64="${OUT}.base64.txt"
base64 -w0 "$OUT" > "$B64"
chmod 600 "$B64"

cat <<NOTES

Add five repository secrets at:
  https://github.com/nashvel/hillbcs-letschat-mobile/settings/secrets/actions

  ANDROID_KEYSTORE_BASE64     paste the whole contents of $B64
  ANDROID_KEYSTORE_PASSWORD   the password you just chose
  ANDROID_KEY_ALIAS           $ALIAS
  ANDROID_KEY_PASSWORD        the key password (same, unless you set a different one)
  GOOGLE_SERVICES_JSON        contents of android/app/google-services.json
                              (Firebase console -> add Android app com.hillbcs.letschat)

Then: Actions tab -> "Release APK" -> Run workflow.

Keep these two files out of git and back them up somewhere durable:
  $OUT
  $B64
NOTES

git check-ignore -q "$OUT" && echo "$OUT is gitignored ✓" || echo "WARNING: $OUT is NOT gitignored"
git check-ignore -q "$B64" && echo "$B64 is gitignored ✓" || echo "WARNING: $B64 is NOT gitignored"
