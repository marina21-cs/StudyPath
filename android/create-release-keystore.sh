#!/usr/bin/env bash
set -euo pipefail

# Creates the ignored release keystore and keystore.properties once, then prints the certificate
# fingerprints to register in Firebase (App Check) and on the API key's Android restriction.
# Passwords are prompted by keytool and never echoed, logged or committed.
# Usage: ./create-release-keystore.sh [--fingerprints-only]
studyPathRoot="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
studyPathKeystore="${STUDYPATH_RELEASE_KEYSTORE:-$studyPathRoot/release.jks}"
studyPathProperties="$studyPathRoot/keystore.properties"
studyPathAlias="studypath-release"
studyPathKeytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$studyPathKeytool" >/dev/null 2>&1 || studyPathKeytool=keytool
command -v "$studyPathKeytool" >/dev/null 2>&1 || { echo "keytool was not found. Set JAVA_HOME to a JDK." >&2; exit 1; }
for path in "$studyPathKeystore" "$studyPathProperties"; do
    git -C "$studyPathRoot" check-ignore -q "$path" ||
        { echo "$path must be git-ignored before a keystore is created." >&2; exit 1; }
done

if [[ "${1:-}" != "--fingerprints-only" ]]; then
    [[ $# -eq 0 ]] || { echo "Usage: ./create-release-keystore.sh [--fingerprints-only]" >&2; exit 1; }
    if [[ -e "$studyPathKeystore" || -e "$studyPathProperties" ]]; then
        echo "A release keystore or keystore.properties already exists. Keep it; losing the key breaks future updates." >&2
        exit 1
    fi
    umask 077
    "$studyPathKeytool" -genkeypair -v -keystore "$studyPathKeystore" -alias "$studyPathAlias" -keyalg RSA -keysize 4096 \
        -validity 10000 -dname "CN=StudyPath, OU=Course Project, O=StudyPath, C=PH"
    read -r -s -p "Re-enter the keystore password to write keystore.properties (mode 0600): " studyPathPassword; echo
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
        "$(realpath --relative-to="$studyPathRoot" "$studyPathKeystore")" "$studyPathPassword" "$studyPathAlias" "$studyPathPassword" > "$studyPathProperties"
    unset studyPathPassword
    echo "Wrote $studyPathProperties (0600). Back up $studyPathKeystore and its password outside this repository."
fi

[[ -f "$studyPathKeystore" ]] || { echo "No release keystore at $studyPathKeystore. Run ./create-release-keystore.sh without arguments first." >&2; exit 1; }
echo
echo "Register these fingerprints for com.studypath.app (Firebase project settings; API key Android restriction uses SHA-1):"
"$studyPathKeytool" -list -v -keystore "$studyPathKeystore" -alias "$studyPathAlias" | grep -E 'SHA1:|SHA256:' | sed 's/^[[:space:]]*//'
debugKeystore="$HOME/.android/debug.keystore"
if [[ -f "$debugKeystore" ]]; then
    echo
    echo "Debug signing certificate (for debug cloud candidates only):"
    "$studyPathKeytool" -list -v -keystore "$debugKeystore" -alias androiddebugkey -storepass android | grep -E 'SHA1:|SHA256:' | sed 's/^[[:space:]]*//'
fi
