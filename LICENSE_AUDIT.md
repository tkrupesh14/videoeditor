# License Audit: Open Video Editor

**Project license:** Apache-2.0 (`LICENSE` header: "Apache License Version 2.0, January 2004"; `NOTICE`: "Copyright 2026 the Open Video Editor contributors").
**Overall risk:** LOW. No GPL, AGPL, or LGPL artifact was found in the `:app` release runtime tree or in the declared catalog. Two items need a human check (see "Uncertain items"), and both are permissive under every reading the files support.

Scope: every entry in `gradle/libs.versions.toml`, every plugin id and dependency in the nine `build.gradle.kts` files, the two planned additions, and the `:app:releaseRuntimeClasspath` tree (309 unique group:artifact:version coordinates). Licenses are quoted from POM `<licenses>` blocks or LICENSE files as they state them.

Source base URLs used below:
- Google Maven: `https://dl.google.com/dl/android/maven2/<group path>/<artifact>/<version>/<artifact>-<version>.pom`
- Maven Central: `https://repo1.maven.org/maven2/<group path>/<artifact>/<version>/<artifact>-<version>.pom`

## Declared and planned dependencies and plugins

Usage key: **runtime** = shipped in the app or in a module the app depends on; **debug** = `debugImplementation`; **test** = `testImplementation`/`testRuntimeOnly`; **build** = Gradle plugin or build tooling; **catalog-only** = declared in the TOML but referenced by no module; **planned** = not yet in any build file.

| Dependency (group:artifact:version) | License (as stated) | Source read | Usage | Risk |
|---|---|---|---|---|
| androidx.core:core-ktx:1.19.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/core/core-ktx/1.19.1` POM | app, runtime | none |
| androidx.activity:activity-compose:1.13.0 | "The Apache Software License, Version 2.0" | Google Maven `androidx/activity/activity-compose/1.13.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.lifecycle:lifecycle-runtime-compose:2.11.0 | "The Apache Software License, Version 2.0" | Google Maven `androidx/lifecycle/lifecycle-runtime-compose/2.11.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0 | "The Apache Software License, Version 2.0" | Google Maven `androidx/lifecycle/lifecycle-viewmodel-compose/2.11.0` POM | ui:shell, ui:timeline, app, runtime | none |
| androidx.compose:compose-bom:2026.09.00 | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/compose-bom/2026.09.00` POM | app, ui:shell, ui:timeline, BOM only | none |
| androidx.compose.ui:ui (BOM-resolved 1.12.1) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/ui/ui/1.12.1` POM | app, ui:shell, ui:timeline, runtime | none |
| androidx.compose.foundation:foundation (BOM-resolved 1.12.1) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/foundation/foundation/1.12.1` POM | app, ui:shell, ui:timeline, runtime | none |
| androidx.compose.material3:material3 (BOM-resolved 1.4.0) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/material3/material3/1.4.0` POM | app, ui:shell, ui:timeline, runtime | none |
| androidx.compose.material3:material3-window-size-class (BOM-resolved 1.4.0) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/material3/material3-window-size-class/1.4.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.compose.material3.adaptive:adaptive:1.3.0 | "The Apache Software License, Version 2.0" | Google Maven `.../material3/adaptive/adaptive/1.3.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.compose.material3.adaptive:adaptive-layout:1.3.0 | "The Apache Software License, Version 2.0" | Google Maven `.../material3/adaptive/adaptive-layout/1.3.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.compose.material3.adaptive:adaptive-navigation:1.3.0 | "The Apache Software License, Version 2.0" | Google Maven `.../material3/adaptive/adaptive-navigation/1.3.0` POM | ui:shell, ui:timeline, runtime | none |
| androidx.compose.ui:ui-tooling (BOM-resolved 1.12.1) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/ui/ui-tooling/1.12.1` POM | app, debugImplementation | none |
| androidx.compose.ui:ui-tooling-preview (BOM-resolved 1.12.1) | "The Apache Software License, Version 2.0" | Google Maven `androidx/compose/ui/ui-tooling-preview/1.12.1` POM | catalog-only (unused) | none |
| androidx.media3:media3-common:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-common/1.11.1` POM | core:media, core:render, app, runtime | none |
| androidx.media3:media3-exoplayer:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-exoplayer/1.11.1` POM | core:media, runtime | none |
| androidx.media3:media3-transformer:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-transformer/1.11.1` POM | core:media, runtime | none |
| androidx.media3:media3-effect:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-effect/1.11.1` POM | core:media, core:render, runtime | none |
| androidx.media3:media3-ui:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-ui/1.11.1` POM | core:media, app, runtime | none |
| org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-serialization-json/1.13.0` POM | core:timeline, core:ai, core:mcp, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-core/1.11.0` POM | core:timeline, core:ai, core:mcp, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-android/1.11.0` POM | core:media, core:render, ui:shell, ui:timeline, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-test/1.11.0` POM | app, testImplementation | none |
| io.ktor:ktor-client-core:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-core/3.6.1` POM | core:ai, runtime | none |
| io.ktor:ktor-client-cio:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-cio/3.6.1` POM | core:ai, runtime | none |
| io.ktor:ktor-client-content-negotiation:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-content-negotiation/3.6.1` POM | core:ai, runtime | none |
| io.ktor:ktor-serialization-kotlinx-json:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-serialization-kotlinx-json/3.6.1` POM | core:ai, runtime | none |
| io.ktor:ktor-client-okhttp:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-okhttp/3.6.1` POM | catalog-only (unused) | none |
| io.ktor:ktor-server-core, ktor-server-cio, ktor-server-websockets:3.6.1 | "The Apache Software License, Version 2.0" (core checked; cio and websockets POMs also checked) | Maven Central `io/ktor/ktor-server-*/3.6.1` POMs | core:mcp only (not in `:app` tree) | none |
| junit:junit:4.13.2 | "Eclipse Public License 1.0" | Maven Central `junit/junit/4.13.2` POM | test (app, core:media, core:render, ui:shell, ui:timeline) | low (EPL is weak copyleft; test-only, not shipped) |
| org.junit:junit-bom:6.1.3 | "Eclipse Public License v2.0" | Maven Central `org/junit/junit-bom/6.1.3` POM | test (core:ai, core:timeline, core:mcp) | low (test-only, not shipped) |
| org.junit.jupiter:junit-jupiter:6.1.3 (BOM-resolved) | "Eclipse Public License v2.0" | Maven Central `org/junit/jupiter/junit-jupiter/6.1.3` POM | test | low (test-only, not shipped) |
| org.junit.platform:junit-platform-launcher:6.1.3 (BOM-resolved) | "Eclipse Public License v2.0" | Maven Central `org/junit/platform/junit-platform-launcher/6.1.3` POM | test runtime | low (test-only, not shipped) |
| io.kotest:kotest-property:6.2.7 | "Apache-2.0" | Maven Central `io/kotest/kotest-property/6.2.7` POM | test (core:timeline) | none |
| com.android.tools.build:gradle:9.5.1 (AGP; plugin ids `com.android.application`, `com.android.library`) | "The Apache Software License, Version 2.0" | Google Maven `com/android/tools/build/gradle/9.5.1` POM | build | none |
| org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21 (plugin ids `org.jetbrains.kotlin.jvm`, `.plugin.compose`, `.plugin.serialization`) | "Apache-2.0" | Maven Central `org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21` POM | build | none |
| org.jlleitschuh.gradle:ktlint-gradle:14.2.0 (plugin id `org.jlleitschuh.gradle.ktlint`) | "MIT License" (from LICENSE.txt on GitHub `JLLeitschuh/ktlint-gradle`, branch `master`; no `<licenses>` block read from a POM, see Uncertain items) | `https://raw.githubusercontent.com/JLLeitschuh/ktlint-gradle/master/LICENSE.txt`; cached POM in `~/.gradle` | build | low (verify) |
| com.pinterest.ktlint:ktlint-* 1.5.0 (ktlint engine, pulled in by the plugin) | "MIT" (reporter POMs); GitHub `pinterest/ktlint` repo license reported by GitHub API as "MIT License" (SPDX MIT) | Maven Central POMs cached in `~/.gradle` (`ktlint-cli-reporter-checkstyle-1.5.0.pom` and others); `https://api.github.com/repos/pinterest/ktlint/license` | build | none |
| com.anthropic:anthropic-java:3.8.0 (latest 3.x per `maven-metadata.xml`) | "Apache-2.0" | Maven Central `com/anthropic/anthropic-java/3.8.0` POM; GitHub `anthropics/anthropic-sdk-java` LICENSE (main) is "Apache License, Version 2.0" | planned (not in any build file yet) | none (its own transitive tree not audited) |
| io.modelcontextprotocol:kotlin-sdk:0.16.0 | POM: "MIT License". Repo LICENSE at tag `0.16.0` opens: "The MCP project is undergoing a licensing transition from the MIT License to the Apache License, Version 2.0" and says legacy contributions "remain licensed under the MIT License", followed by Apache-2.0 text | Maven Central `io/modelcontextprotocol/kotlin-sdk/0.16.0` POM; `https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/0.16.0/LICENSE` | planned | low (verify; permissive under either reading) |
| io.github.takahirom.roborazzi:roborazzi, roborazzi-compose, plugin io.github.takahirom.roborazzi:1.79.0 | "Apache-2.0" | Maven Central POM `roborazzi/1.79.0` | :app, test-only (screenshots) | low |
| org.robolectric:robolectric:4.18 | "The MIT License" | Maven Central POM `robolectric/4.18` | :app, test-only | low |
| androidx.compose.ui:ui-test-junit4, ui-test-manifest (BOM 2026.09.00) | "The Apache Software License, Version 2.0" | Google Maven POM (ui-test-junit4) | :app, test / debug only | low |

## Transitive dependencies (`:app:releaseRuntimeClasspath`)

The Gradle run (`:app:dependencies --configuration releaseRuntimeClasspath`) resolved 309 unique group:artifact:version coordinates. A scan of the tree text for `gpl`, `lgpl`, `agpl`, and `ffmpeg` found no match. No ffmpeg artifact is present, and no `okhttp` artifact is present (`ktor-client-okhttp` is catalog-only). Per-POM license reading was done for the 22 transitive artifacts below. The other 287 coordinates were checked by group name only, not by POM, so the "no GPL" result rests on that scan plus these spot-checks.

| Dependency (group:artifact:version) | License (as stated) | Source read | Usage | Risk |
|---|---|---|---|---|
| org.jetbrains.kotlin:kotlin-stdlib:2.4.21 (14 versions resolved, 1.6.21 to 2.4.21, from transitive pulls) | "Apache-2.0" | Maven Central `org/jetbrains/kotlin/kotlin-stdlib/2.4.21` POM | runtime (transitive) | none |
| org.jetbrains:annotations:23.0.0 | "The Apache Software License, Version 2.0" | Maven Central `org/jetbrains/annotations/23.0.0` POM | runtime (transitive) | none |
| com.charleskorn.kaml:kaml-jvm:0.79.0 | "The Apache License, Version 2.0" | Maven Central `com/charleskorn/kaml/kaml-jvm/0.79.0` POM | runtime (transitive) | none |
| it.krzeminski:snakeyaml-engine-kmp-jvm:3.1.1 | "Apache-2.0" | Maven Central `it/krzeminski/snakeyaml-engine-kmp-jvm/3.1.1` POM | runtime (transitive) | none |
| net.thauvin.erik.urlencoder:urlencoder-lib-jvm:1.6.0 | "The Apache License, Version 2.0" | Maven Central `net/thauvin/erik/urlencoder/urlencoder-lib-jvm/1.6.0` POM | runtime (transitive) | none |
| com.google.code.findbugs:jsr305:3.0.2 | "The Apache Software License, Version 2.0" | Maven Central `com/google/code/findbugs/jsr305/3.0.2` POM | runtime (transitive) | none |
| org.checkerframework:checker-qual:3.43.0 | "The MIT License" | Maven Central `org/checkerframework/checker-qual/3.43.0` POM | runtime (transitive) | none |
| org.jspecify:jspecify:1.0.0 | "The Apache License, Version 2.0" | Maven Central `org/jspecify/jspecify/1.0.0` POM | runtime (transitive) | none |
| com.google.j2objc:j2objc-annotations:3.0.0 | "Apache License, Version 2.0" | Maven Central `com/google/j2objc/j2objc-annotations/3.0.0` POM | runtime (transitive) | none |
| com.google.errorprone:error_prone_annotations:2.28.0 | "Apache 2.0" | Maven Central `com/google/errorprone/error_prone_annotations/2.28.0` POM | runtime (transitive) | none |
| com.squareup.okio:okio-jvm:3.12.0 | "The Apache Software License, Version 2.0" | Maven Central `com/squareup/okio/okio-jvm/3.12.0` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.9.1` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.8.0-0.6.x-compat | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-datetime-jvm/0.8.0-0.6.x-compat` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.13.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| io.ktor:ktor-openapi-schema-jvm:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central POM | runtime (transitive) | none |
| io.ktor:ktor-network-tls-jvm:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central POM | runtime (transitive) | none |
| org.slf4j:slf4j-api:2.0.19 | POM has no `<licenses>` block. LICENSE text at `qos-ch/slf4j` master begins "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)" (MIT-style permission grant) | Maven Central POM; `https://raw.githubusercontent.com/qos-ch/slf4j/master/LICENSE.txt` | runtime (transitive) | low (verify) |
| com.google.guava:guava:33.3.1-android | POM has no `<licenses>` block. Guava repo LICENSE (master) is "Apache License, Version 2.0" | Maven Central POM; `https://raw.githubusercontent.com/google/guava/master/LICENSE` | runtime (transitive) | low (verify) |
| com.google.guava:failureaccess:1.0.2 | POM has no `<licenses>` block; same Guava project as above | Maven Central POM | runtime (transitive) | low (verify) |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Placeholder artifact; POM has no `<licenses>` block | Maven Central POM | runtime (transitive) | low (verify) |

## Uncertain items (verify)

1. **io.modelcontextprotocol:kotlin-sdk:0.16.0.** The POM says "MIT License". The tag's LICENSE file says the project is moving from MIT to Apache-2.0, with new contributions under Apache-2.0 and legacy contributions still under MIT. The files do not say which license applies to the released artifact. `docs/design/security.md` (line 23) says Apache-2.0 for `main` and states that GitHub reports SPDX `NOASSERTION`. Both readings are permissive. Confirm with the upstream maintainers before the dependency is added, and record the chosen reading.
2. **org.jlleitschuh.gradle:ktlint-gradle:14.2.0.** The Maven Central POM path tried returned HTTP 404. The cached POM from the Gradle run has no `<licenses>` block. The "MIT License" reading comes from `LICENSE.txt` on GitHub `master`, not from the 14.2.0 tag. Build-only, not shipped.
3. **slf4j-api 2.0.19, guava 33.3.1-android, failureaccess 1.0.2, listenablefuture placeholder.** These POMs have no `<licenses>` block. The license reading comes from the upstream repo's master LICENSE, not from the exact release tag. Transitive, likely permissive.
4. **Planned com.anthropic:anthropic-java 3.8.0.** Its transitive tree has not been resolved. Re-audit when it is added to a catalog or module.
5. **core:mcp runtime tree.** `core:mcp` (ktor-server-*) is not a dependency of `:app`, so its runtime tree is not in the audited `:app` tree. Only the three top-level ktor-server POMs were read. Run the `core:mcp` dependency report if it ships.

## Surprising findings

- The MCP kotlin-sdk license statements conflict: POM "MIT License" versus the repo's own design doc claiming Apache-2.0 for `main`. See item 1.
- The anthropic-java SDK is Apache-2.0 in both its POM and its GitHub LICENSE. It is not MIT.
- `androidx.security:security-crypto` (deprecated upstream, unused) was removed from the catalog in Phase 4.
- Two catalog entries are unused by any module: `androidx-compose-ui-tooling-preview`, `ktor-client-okhttp`.
- kotlin-stdlib resolves to 14 different versions in the release tree (1.6.21 to 2.4.21). This is a version-alignment issue, not a license issue.
- No ffmpeg or other GPL-family codec artifact is in the tree. Media3 is Apache-2.0 and uses the platform codecs.

## How to re-run

```bash
cd /home/shubham/Documents/krupesh
JAVA_HOME=$HOME/.local/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/Android/Sdk \
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain -q > deptree.txt

# unique resolved coordinates
grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+' deptree.txt | sed -E 's/ .*//' | sort -u

# GPL-family scan
grep -iE 'gpl|ffmpeg' deptree.txt

# License block of one POM (Google Maven example)
curl -s https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.19.1/core-ktx-1.19.1.pom \
  | tr -d '\r' | awk '/<licenses>/,/<\/licenses>/'
```

For the planned SDKs, read `maven-metadata.xml` for the version, then the POM for `<licenses>`. Check the GitHub LICENSE on the matching tag, not only `main`.
