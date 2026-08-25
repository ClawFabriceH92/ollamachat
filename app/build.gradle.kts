import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release keystore from CI secrets (OLLAMACHAT_KEYSTORE_B64) or local backup.
fun releaseKeystore(): File? {
    System.getenv("OLLAMACHAT_KEYSTORE_B64")?.let { b64 ->
        val tmp = File(System.getenv("RUNNER_TEMP") ?: "/tmp", "ollamachat-release.keystore")
        tmp.writeBytes(Base64.getDecoder().decode(b64))
        return tmp
    }
    val candidates = listOf(
        File(System.getProperty("user.home"), ".secrets/keystores-android/ollamachat-release.keystore"),
        File("/root/.secrets/keystores-android/ollamachat-release.keystore"),
    )
    return candidates.firstOrNull { it.exists() }
}

android {
    namespace = "com.trucdecomptable.ollamachat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trucdecomptable.ollamachat"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ks = releaseKeystore()
            if (ks != null) {
                storeFile = ks
                storePassword = System.getenv("OLLAMACHAT_KEYSTORE_PASSWORD") ?: "CHANGE_ME"
                keyAlias = System.getenv("OLLAMACHAT_KEY_ALIAS") ?: "ollamachat"
                keyPassword = System.getenv("OLLAMACHAT_KEY_PASSWORD")
                    ?: System.getenv("OLLAMACHAT_KEYSTORE_PASSWORD")
                    ?: "CHANGE_ME"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // --- Room ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- DataStore (settings) ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Network ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- PDF text extraction ---
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // --- Biometric unlock ---
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
