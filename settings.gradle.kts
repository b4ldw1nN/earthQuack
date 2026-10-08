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
        // Locally built AARs (the gomobile/rclone binding). Declared here
        // rather than in app/build.gradle.kts because FAIL_ON_PROJECT_REPOS
        // forbids per-project repositories.
        //
        // The AAR must resolve as a real dependency, not a file() one, so that
        // AGP unpacks jniLibs/*.so into the APK. A plain file() dependency
        // silently drops libgojni.so and the app fails at runtime with an
        // UnsatisfiedLinkError that looks nothing like a packaging mistake.
        flatDir { dirs("$rootDir/app/libs") }
    }
}

rootProject.name = "ClipboardSync"
include(":app")
