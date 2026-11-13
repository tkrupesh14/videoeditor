pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "open-video-editor"

include(
    ":app",
    ":core:timeline",
    ":core:ai",
    ":core:mcp",
    ":core:render",
    ":core:media",
    ":ui:shell",
    ":ui:timeline",
)
