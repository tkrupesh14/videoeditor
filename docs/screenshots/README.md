# Screenshots

JVM-rendered (Roborazzi + Robolectric native graphics, no emulator) from `app/src/test/.../screenshots/ScreenshotTest.kt`,
using fake state from `SampleProjects.twoClipDemo()`.

Regenerate:

    ./gradlew :app:recordRoborazziDebug

Plain `./gradlew test` runs the same tests but does not write or compare images (no goldens needed).
Robolectric runs at SDK 35 because SDK 36 needs Java 21.

AI screens: `phone-ai-panel-dark.png` (AI tab with a pending suggestion, fake state) and
`phone-ai-settings-no-key-dark.png` (settings with no key stored). On a device the settings screen sets
`FLAG_SECURE`, so it cannot be captured there; these are JVM renders with no key material.
