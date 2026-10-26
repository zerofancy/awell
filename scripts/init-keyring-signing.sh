#!/usr/bin/env bash
# Read passwords locally and pass them to Gradle without command-line arguments.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

trap 'unset signing_store_password signing_key_password' EXIT
read -r -s -p 'Keystore password: ' signing_store_password
printf '\n'
if [[ -z "$signing_store_password" ]]; then
    printf 'Keystore password must not be empty.\n' >&2
    exit 1
fi
read -r -s -p 'Private key password (Enter to reuse keystore password): ' signing_key_password
printf '\n'
signing_key_password=${signing_key_password:-$signing_store_password}

SIGN_STORE_PASSWORD="$signing_store_password" ./gradlew \
    --no-daemon --no-configuration-cache --console=plain -PkeyringSigning=false \
    :setSecretValue --name=android_store_password
SIGN_KEY_PASSWORD="$signing_key_password" ./gradlew \
    --no-daemon --no-configuration-cache --console=plain -PkeyringSigning=false \
    :setSecretValue --name=android_key_password

printf 'Passwords saved. Configure keyringStoreFile and keyringKeyAlias in local.properties, then run:\n'
printf './gradlew :app:verifyReleaseSigning :app:assembleRelease\n'
