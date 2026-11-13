# :core:ai

Pure-JVM AI layer: API key handling (`key/`), spend control (`spend/`), the Claude edit assistant
(`assistant/`), and offline analysis helpers (`analysis/`). See `docs/design/security.md` Part A.

## Manual real-API test (spends real credits)

`ManualRealApiTest` is tagged `manual-real-api`, is excluded from `./gradlew :core:ai:test`, and only runs
when `ANTHROPIC_API_KEY` is set. It uses `claude-haiku-5-5`, `max_tokens` 1024 and stops at $0.05
(a single run costs well under one cent). Use a Console API key, ideally from a workspace with its own
spend limit:

```sh
export JAVA_HOME=$HOME/.local/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/Android/Sdk
ANTHROPIC_API_KEY=<your Console key> ./gradlew :core:ai:manualRealApiTest --console=plain
```

All other tests use a local mock server and never reach the network.
