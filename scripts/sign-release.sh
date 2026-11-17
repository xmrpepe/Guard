#!/usr/bin/env bash
# Aligns and signs the unsigned release APK.
#
# Permanent key (recommended): set RELEASE_KEYSTORE_B64 (base64 of a .jks/.p12),
# RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD.
#
# Without them, a one-off key is generated for this build only and deleted
# afterwards. Nobody holds that key, so the APK cannot be impersonated, but a
# later build signed with a different key cannot update it in place.
#
# Usage: sign-release.sh <unsigned.apk> <output.apk>
set -euo pipefail

IN="$1"
OUT="$2"
BT="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ -n "${RELEASE_KEYSTORE_B64:-}" ]]; then
  echo "Signing with the permanent release key."
  echo "$RELEASE_KEYSTORE_B64" | base64 -d > "$WORK/release.ks"
  KS_PASS="${RELEASE_KEYSTORE_PASSWORD:?missing RELEASE_KEYSTORE_PASSWORD}"
  ALIAS="${RELEASE_KEY_ALIAS:?missing RELEASE_KEY_ALIAS}"
  KEY_PASS="${RELEASE_KEY_PASSWORD:-$KS_PASS}"
  SIGNING="permanent"
else
  echo "No permanent key configured: generating a one-off signing key for this build."
  KS_PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  KEY_PASS="$KS_PASS"
  ALIAS="rocksguard-oneoff"
  keytool -genkeypair -noprompt -keystore "$WORK/release.ks" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=RocksGuard one-off build key, O=RocksGuard" >/dev/null 2>&1
  SIGNING="one-off"
fi

"$BT/zipalign" -p -f 4 "$IN" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$WORK/release.ks" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$ALIAS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT" | grep -E "Verified using v[23]"
CERT="$("$BT/apksigner" verify --print-certs "$OUT" | grep -m1 'SHA-256 digest' | awk '{print $NF}')"
echo "signing=$SIGNING" > "$OUT.signing"
echo "cert_sha256=$CERT" >> "$OUT.signing"
echo "Signed ($SIGNING key). Certificate SHA-256: $CERT"
