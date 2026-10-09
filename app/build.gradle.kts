plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.earthquack"
    compileSdk = 34   // Android 14 (SDK 34 already installed)

    defaultConfig {
        applicationId = "com.example.earthquack"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // The gomobile/rclone binding currently ships arm64 only. Declared
        // explicitly so an APK for another ABI fails at build time with a
        // clear message rather than at runtime with UnsatisfiedLinkError.
        // Widen this in step with rclone-android/README.md.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Required by connectedAndroidTest.
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // ── SSH / SFTP ──────────────────────────────────────────────────────────
    // Apache MINA SSHD. sshd-core carries both the client (SshClient) and the
    // server (SshServer); sshd-sftp carries the SFTP subsystem for the server
    // and SftpClient for the client. No native code, so this adds no ABI and
    // no NDK requirement beyond the rclone AAR that is already arm64-only.
    implementation(libs.sshd.common)
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    // Optional in sshd; needed only for ed25519 host keys / client keys.
    implementation(libs.eddsa)
    // slf4j binding so sshd's own diagnostics reach logcat instead of being
    // discarded as "no binding".
    implementation(libs.slf4j.simple)

    // The gomobile/rclone binding, built by rclone-android/ (see its README).
    // Resolved via the flatDir repository in settings.gradle.kts so that AGP
    // unpacks jniLibs/arm64-v8a/libgojni.so into the APK. A plain
    // files("libs/rclone.aar") compiles but silently drops the .so.
    implementation("rclone:rclone@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    // runTest for the JVM-side RcloneEngine tests.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // On-device verification of the native rclone boundary.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
