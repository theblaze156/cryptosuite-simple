# Contributing

1. Open an issue first for anything larger than a small fix.
2. Fork, create a branch, keep changes focused.
3. Run `./gradlew testDebugUnitTest` before opening a pull request.
4. Rules for this project:
   - No private keys, seed phrases, signing or sweeping in the app.
   - No hardcoded URLs, keys, tokens or addresses; never commit keystores or `*.properties` with secrets.
   - UI text stays in Russian; error messages must be plain-language.
   - Never log secrets or full transaction hashes.
5. Describe what you changed and how you tested it in the PR.
