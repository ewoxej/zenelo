plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The version lives in /VERSION (read by the Release workflow too); `-PversionName=1.2.3` overrides it.
// versionCode follows it (1.2.3 → 10203) so it only grows.
val releaseVersion = ((findProperty("versionName") as String?) ?: rootProject.file("VERSION").readText()).trim().removePrefix("v")
val releaseVersionCode = releaseVersion.split('.').map { it.toInt() }.let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

// Release key from the environment (CI secrets); without it the release build is signed with the debug key.
val releaseKeystore = System.getenv("ZENELO_KEYSTORE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "app.zenelo"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.zenelo"
        // FiiO JM21 ships Android 13.
        minSdk = 33
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
        // `-PappIdSuffix=.debug`: a build (debug or release) that installs beside the real app, for
        // trying things on a phone.
        (findProperty("appIdSuffix") as String?)?.let { suffix ->
            applicationIdSuffix = suffix
            versionNameSuffix = "-test"
            resValue("string", "app_name", "Zenelo test")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ZENELO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZENELO_KEY_ALIAS")
                keyPassword = System.getenv("ZENELO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Sideload-only: the real key in CI, the debug key locally so the release build installs directly.
            // Compose is several times faster in release (R8 + no debug instrumentation).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // TODO: FFmpeg decoder for ALAC / DSD / APE / WavPack.
    // Prebuilt: org.jellyfin.media3:media3-ffmpeg-decoder (check that DSD decoders are enabled),
    // otherwise build media3's decoder_ffmpeg module with the NDK.

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    // Tag reading/writing (cover embedding). Pure Java; runs in Android mode, see TagReader.
    implementation(libs.jaudiotagger)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)

    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests (BackupFormat).
    testImplementation("org.json:json:20240303")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
}
