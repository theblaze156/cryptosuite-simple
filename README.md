# CryptoSuite Simple

Simple Russian-language Android client for your own self-hosted crypto_suite backend (watch-only).

- Screens: Баланс, Адреса, Аудит, Статус, Настройки.
- No private keys, seed phrases, signing or sweeping in the app.
- No hardcoded server URL or keys: on first launch you enter the server URL, the `X-Crypto-Auth` value and a backend API key (`bsk_…`). They are stored only in the app's private (Keystore-backed) storage.
- Add only addresses you own. Adding/removing needs an API key with `build` scope; read-only keys can view, audit and check status.
- Cleartext HTTP is allowed because the backend may run without TLS; use HTTPS when available.

## Build
Create your own `keystore.properties` and keystore (they are git-ignored), then `./gradlew assembleRelease`.
