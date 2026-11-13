# License Audit: Open Video Editor

**Project license:** Apache-2.0 (`LICENSE` header: "Apache License Version 2.0, January 2004"; `NOTICE`: "Copyright 2026 the Open Video Editor contributors").
**Overall risk:** LOW. No GPL, LGPL, AGPL, or ffmpeg artifact was found in the `:app` release runtime tree or in the declared catalog. Items marked "verify" are permissive under every reading the files support.

Scope: every entry in `gradle/libs.versions.toml` (five unreferenced entries removed on 2026-11-14), every plugin id and dependency in the nine `build.gradle.kts` files, the Anthropic SDK and OkHttp tree added to `:core:ai`, and the `:app:releaseRuntimeClasspath` tree (343 unique group:artifact:version coordinates as of 2026-11-14). Licenses are quoted from POM `<licenses>` blocks or LICENSE files as they state them.

Source base URLs used below:
- Google Maven: `https://dl.google.com/dl/android/maven2/<group path>/<artifact>/<version>/<artifact>-<version>.pom`
- Maven Central: `https://repo1.maven.org/maven2/<group path>/<artifact>/<version>/<artifact>-<version>.pom`

## Declared dependencies and plugins

Usage key: **runtime** = shipped in the app or in a module the app depends on; **debug** = `debugImplementation`; **test** = `testImplementation`/`testRuntimeOnly`; **build** = Gradle plugin or build tooling; **not used** = no module references it.

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
| androidx.media3:media3-common:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-common/1.11.1` POM | core:media, core:render, app, runtime | none |
| androidx.media3:media3-exoplayer:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-exoplayer/1.11.1` POM | core:media, runtime | none |
| androidx.media3:media3-transformer:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-transformer/1.11.1` POM | core:media, runtime | none |
| androidx.media3:media3-effect:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-effect/1.11.1` POM | core:media, core:render, runtime | none |
| androidx.media3:media3-ui:1.11.1 | "The Apache Software License, Version 2.0" | Google Maven `androidx/media3/media3-ui/1.11.1` POM | core:media, app, runtime | none |
| org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-serialization-json/1.13.0` POM | core:timeline, core:ai, core:mcp, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-core/1.11.0` POM | core:timeline, core:ai, core:mcp, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-android/1.11.0` POM | core:media, core:render, ui:shell, ui:timeline, runtime | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-test/1.11.0` POM | app, testImplementation | none |
| io.ktor:ktor-client-core:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-core/3.6.1` POM | removed 2026-11-14 (unused; anthropic-java uses OkHttp) | none |
| io.ktor:ktor-client-cio:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-cio/3.6.1` POM | removed 2026-11-14 (unused; anthropic-java uses OkHttp) | none |
| io.ktor:ktor-client-content-negotiation:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-client-content-negotiation/3.6.1` POM | removed 2026-11-14 (unused; anthropic-java uses OkHttp) | none |
| io.ktor:ktor-serialization-kotlinx-json:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central `io/ktor/ktor-serialization-kotlinx-json/3.6.1` POM | removed 2026-11-14 (unused; anthropic-java uses OkHttp) | none |
| com.anthropic:anthropic-java:3.8.0 (latest 3.x per `maven-metadata.xml`) | "Apache-2.0" | Maven Central `com/anthropic/anthropic-java/3.8.0` POM; GitHub `anthropics/anthropic-sdk-java` LICENSE is "Apache License, Version 2.0" | core:ai, runtime | none |
| com.anthropic:anthropic-java-core:3.8.0 | "Apache-2.0" | Maven Central `com/anthropic/anthropic-java-core/3.8.0` POM | core:ai, runtime (transitive of anthropic-java) | none |
| com.anthropic:anthropic-java-client-okhttp:3.8.0 | "Apache-2.0" | Maven Central `com/anthropic/anthropic-java-client-okhttp/3.8.0` POM | core:ai, runtime (transitive of anthropic-java) | none |
| com.squareup.okhttp3:okhttp:4.12.0 | "The Apache Software License, Version 2.0" (link `apache.org/licenses/LICENSE-2.0.txt`) | Maven Central `com/squareup/okhttp3/okhttp/4.12.0` POM | runtime (transitive of anthropic-java-client-okhttp) | none |
| com.squareup.okhttp3:mockwebserver:4.12.0 | "The Apache Software License, Version 2.0" (link `apache.org/licenses/LICENSE-2.0.txt`) | Maven Central `com/squareup/okhttp3/mockwebserver/4.12.0` POM | core:ai, test-only | low (test-only, not shipped) |
| junit:junit:4.13.2 | "Eclipse Public License 1.0" | Maven Central `junit/junit/4.13.2` POM | test (app, core:media, core:render, ui:shell, ui:timeline) | low (EPL is weak copyleft; test-only, not shipped) |
| org.junit:junit-bom:6.1.3 | "Eclipse Public License v2.0" | Maven Central `org/junit/junit-bom/6.1.3` POM | test (core:ai, core:timeline, core:mcp) | low (test-only, not shipped) |
| org.junit.jupiter:junit-jupiter:6.1.3 (BOM-resolved) | "Eclipse Public License v2.0" | Maven Central `org/junit/jupiter/junit-jupiter/6.1.3` POM | test | low (test-only, not shipped) |
| org.junit.platform:junit-platform-launcher:6.1.3 (BOM-resolved) | "Eclipse Public License v2.0" | Maven Central `org/junit/platform/junit-platform-launcher/6.1.3` POM | test runtime | low (test-only, not shipped) |
| io.kotest:kotest-property:6.2.7 | "Apache-2.0" | Maven Central `io/kotest/kotest-property/6.2.7` POM | test (core:timeline) | none |
| com.android.tools.build:gradle:9.5.1 (AGP; plugin ids `com.android.application`, `com.android.library`) | "The Apache Software License, Version 2.0" | Google Maven `com/android/tools/build/gradle/9.5.1` POM | build | none |
| org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21 (plugin ids `org.jetbrains.kotlin.jvm`, `.plugin.compose`, `.plugin.serialization`) | "Apache-2.0" | Maven Central `org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21` POM | build | none |
| org.jlleitschuh.gradle:ktlint-gradle:14.2.0 (plugin id `org.jlleitschuh.gradle.ktlint`) | "MIT License" (from LICENSE.txt on GitHub `JLLeitschuh/ktlint-gradle`, branch `master`; no `<licenses>` block read from a POM, see Uncertain items) | `https://raw.githubusercontent.com/JLLeitschuh/ktlint-gradle/master/LICENSE.txt`; cached POM in `~/.gradle` | build | low (verify) |
| com.pinterest.ktlint:ktlint-* 1.5.0 (ktlint engine, pulled in by the plugin) | "MIT" (reporter POMs); GitHub `pinterest/ktlint` repo license reported by GitHub API as "MIT License" (SPDX MIT) | Maven Central POMs cached in `~/.gradle` (`ktlint-cli-reporter-checkstyle-1.5.0.pom` and others); `https://api.github.com/repos/pinterest/ktlint/license` | build | none |
| io.github.takahirom.roborazzi:roborazzi, roborazzi-compose, plugin io.github.takahirom.roborazzi:1.79.0 | "Apache-2.0" | Maven Central POM `roborazzi/1.79.0` | :app, test-only (screenshots) | low |
| org.robolectric:robolectric:4.18 | "The MIT License" | Maven Central POM `robolectric/4.18` | :app, test-only | low |
| androidx.compose.ui:ui-test-junit4, ui-test-manifest (BOM 2026.09.00) | "The Apache Software License, Version 2.0" | Google Maven POM (ui-test-junit4) | :app, test / debug only | low |
| io.modelcontextprotocol:kotlin-sdk:0.16.0 | POM: "MIT License". Repo LICENSE at tag `0.16.0` says the project is moving from MIT to Apache-2.0 | Maven Central POM; `https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/0.16.0/LICENSE` | **not used** (MCP server hand-written in `:core:mcp`; no catalog entry, no build file) | none (not shipped). Re-audit if ever added (see Uncertain items) |
| io.ktor:ktor-client-okhttp, ktor-server-core, ktor-server-cio, ktor-server-websockets, androidx.compose.ui:ui-tooling-preview | removed from `gradle/libs.versions.toml` on 2026-11-14 (no module referenced them) | n/a | removed | n/a |
| androidx.security:security-crypto | removed in an earlier phase (deprecated upstream, unused) | n/a | removed | n/a |

## Transitive dependencies (`:app:releaseRuntimeClasspath`)

The Gradle run (`:app:dependencies --configuration releaseRuntimeClasspath`) resolved 343 unique group:artifact:version coordinates (309 before the Anthropic SDK was added to `:core:ai`). A scan of the tree text for `gpl`, `lgpl`, `agpl`, and `ffmpeg` (case-insensitive) found **0 matches**. No ffmpeg artifact is present. OkHttp 4.12.0 is now present, as a transitive of `anthropic-java-client-okhttp`. Per-POM license reading was done for the artifacts below. The other coordinates were checked by group name only, not by POM, so the "no GPL" result rests on that scan plus these spot-checks.

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
| com.squareup.okio:okio-jvm:3.12.0 (3.6.0 and 3.10.2 also resolved, upgraded to 3.12.0) | "The Apache Software License, Version 2.0" | Maven Central `com/squareup/okio/okio-jvm/3.12.0` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1 | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.9.1` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.8.0-0.6.x-compat | "Apache-2.0" | Maven Central `org/jetbrains/kotlinx/kotlinx-datetime-jvm/0.8.0-0.6.x-compat` POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.13.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0 | "Apache-2.0" | Maven Central POM | runtime (transitive) | none |
| io.ktor:ktor-openapi-schema-jvm:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central POM | runtime (transitive) | none |
| io.ktor:ktor-network-tls-jvm:3.6.1 | "The Apache Software License, Version 2.0" | Maven Central POM | runtime (transitive) | none |
| com.fasterxml.jackson.core:jackson-core, jackson-databind, jackson-annotations:2.19.4 (jackson-core and databind also at 2.17.2 via other pulls) | "The Apache Software License, Version 2.0" | Maven Central POMs `com/fasterxml/jackson/core/*/2.19.4` | runtime (transitive of anthropic-java-core) | none |
| com.fasterxml.jackson.module:jackson-module-kotlin:2.19.4 | "The Apache Software License, Version 2.0" | Maven Central POM | runtime (transitive of anthropic-java-core) | none |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8, jackson-datatype-jsr310:2.19.4 | POM has no `<licenses>` block; parent `jackson-modules-java8:2.19.4` also has none. Jackson project is Apache-2.0 per the other Jackson POMs | Maven Central POMs | runtime (transitive) | low (verify) |
| com.fasterxml:classmate:1.7.0 | "Apache License, Version 2.0" | Maven Central POM | runtime (transitive) | none |
| com.github.victools:jsonschema-generator, jsonschema-module-jackson, jsonschema-module-swagger-2:4.38.0 | "The Apache License, Version 2.0" (on parent `jsonschema-generator-parent:4.38.0` POM; the children have no own block) | Maven Central parent POM | runtime (transitive of anthropic-java-core, schema generation) | none |
| io.swagger.core.v3:swagger-annotations:2.2.50 | "Apache License 2.0" (on parent `swagger-project:2.2.50` POM) | Maven Central parent POM | runtime (transitive) | none |
| io.ktor:ktor-client-core, ktor-client-cio, ktor-client-content-negotiation, ktor-serialization-kotlinx-json, ktor-http/-cio, ktor-network, ktor-websockets, ktor-sse, ktor-events, ktor-io, ktor-utils (3.6.1) | "The Apache Software License, Version 2.0" (checked via `ktor-client-core` and `ktor-openapi-schema-jvm`; the rest by group) | Maven Central POMs | runtime (transitive) | none |
| org.slf4j:slf4j-api:2.0.19 | POM has no `<licenses>` block. LICENSE text at `qos-ch/slf4j` master begins "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)" (MIT-style permission grant) | Maven Central POM; `https://raw.githubusercontent.com/qos-ch/slf4j/master/LICENSE.txt` | runtime (transitive) | low (verify) |
| com.google.guava:guava:33.3.1-android | POM has no `<licenses>` block. Guava repo LICENSE (master) is "Apache License, Version 2.0" | Maven Central POM; `https://raw.githubusercontent.com/google/guava/master/LICENSE` | runtime (transitive) | low (verify) |
| com.google.guava:failureaccess:1.0.2 | POM has no `<licenses>` block; same Guava project as above | Maven Central POM | runtime (transitive) | low (verify) |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Placeholder artifact; POM has no `<licenses>` block | Maven Central POM | runtime (transitive) | low (verify) |

## Uncertain items (verify)

1. **com.fasterxml.jackson.datatype:jackson-datatype-jdk8 and jackson-datatype-jsr310 (2.19.4).** Their POMs and the parent `jackson-modules-java8` POM have no `<licenses>` block. The license reading is inferred from the other Jackson artifacts (Apache-2.0). Confirm against the Jackson parent POM for 2.19.4 if the list is re-audited.
2. **org.jlleitschuh.gradle:ktlint-gradle:14.2.0.** The Maven Central POM path tried returned HTTP 404. The cached POM from the Gradle run has no `<licenses>` block. The "MIT License" reading comes from `LICENSE.txt` on GitHub `master`, not from the 14.2.0 tag. Build-only, not shipped.
3. **slf4j-api 2.0.19, guava 33.3.1-android, failureaccess 1.0.2, listenablefuture placeholder.** These POMs have no `<licenses>` block. The license reading comes from the upstream repo's master LICENSE, not from the exact release tag. Transitive, likely permissive.
4. **io.modelcontextprotocol:kotlin-sdk (not used).** Not in any build file. If it is ever added, the POM ("MIT License") and the repo LICENSE (MIT-to-Apache-2.0 transition) need a decision. Both readings are permissive.
5. **com.github.victools:* (4.38.0).** The upstream `master` LICENSE.txt URL returned 404 when checked, so the Apache-2.0 reading rests on the Maven parent POM only. Permissive; verify if the upstream license file is needed.

## Surprising findings

- The anthropic-java SDK is Apache-2.0 in both its POM and its GitHub LICENSE. It is not MIT. Its transitive tree (OkHttp 4.12.0, Okio, Jackson 2.19.4, victools, classmate, swagger-annotations) is all Apache-2.0 or permissive.
- `androidx.security:security-crypto` (deprecated upstream, unused) was removed from the catalog in an earlier phase. On 2026-11-14 five more unreferenced entries were removed: `androidx-compose-ui-tooling-preview`, `ktor-client-okhttp`, `ktor-server-core`, `ktor-server-cio`, `ktor-server-websockets`. No catalog entry is unused now. `ktor` version is still in use by the `ktor-client-*` entries.
- kotlin-stdlib resolves to 14 different versions in the release tree (1.6.21 to 2.4.21). This is a version-alignment issue, not a license issue.
- No ffmpeg or other GPL-family codec artifact is in the tree. Media3 is Apache-2.0 and uses the platform codecs.
- `:core:mcp` has no third-party runtime dependency beyond kotlinx; its MCP server is hand-written, so the earlier concern about its ktor-server tree no longer applies.

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

For the Anthropic SDK and other Maven Central artifacts, read `maven-metadata.xml` for the version, then the POM for `<licenses>`. Check the GitHub LICENSE on the matching tag, not only `main`.


> 2026-11-14 orchestrator: Ktor client artifacts were declared in `:core:ai` but no code imported `io.ktor`; removed from the module and catalog. Ktor rows above are historical. Re-run `:app:dependencies` to confirm the coordinate count.
