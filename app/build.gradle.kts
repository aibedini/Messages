import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.autonomousone.messages"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // Prefer environment variables (CI); fall back to a gitignored
            // keystore.properties file for local release builds.
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: props.getProperty("storeFile")
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props.getProperty("storePassword")
                keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.autonomousone.messages"
        minSdk = 26
        targetSdk = 36
        versionCode = 69
        versionName = "2.6.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Production backend URL — override in local.properties via gradle.properties if needed.
        // v2.6.21 (PR-11): default now points at the deployed ADR-004 control
        // plane (GMweb) — the /api/gateways/* v1 backend is retired.
        val backendUrl = project.findProperty("GATEWAY_BACKEND_URL")?.toString()
            ?: "https://gmweb.46.31.76.103.nip.io"
        buildConfigField("String", "GATEWAY_BACKEND_URL", "\"$backendUrl\"")
        // Single source of truth for the app version.
        buildConfigField("String", "APP_VERSION", "\"$versionName\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val keystoreAvailable = System.getenv("KEYSTORE_FILE") != null ||
                    props.getProperty("storeFile")?.isNotBlank() == true
            if (keystoreAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Let JVM unit tests call android.util.Log etc. as harmless no-ops.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Export Room schemas to app/schemas/ so future versions can ship real
// Migrations instead of destructive wipes (see docs/room-migration-strategy.md).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests: android.jar ships a stub whose methods
    // return defaults under returnDefaultValues, which breaks JSON round-trips
    // (GatewayEventFactoryTest). Runtime still uses the platform's own class.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // ADR-007: QR scanning for linked-device pairing (ML Kit barcode, on-device).
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // MMS stack (Fossify fork of klinker android-smsmms): PushReceiver +
    // TransactionService download/parse incoming MMS into Telephony.Mms.
    // VENDORED: JitPack (its only remote publication) has repeated outages
    // that fail CI resolution, so the .aar is committed under app/libs.
    // The three runtime deps below are exactly what the published POM
    // (org.fossify:mmslib:1.0.0) declared — file deps carry no metadata.
    implementation(files("libs/mmslib-1.0.0.aar"))
    implementation(libs.klinker.logger)
    implementation(libs.okhttp.legacy)
    implementation(libs.okhttp.urlconnection)
    // Room: local read-SSOT for the UI (phase 2 architecture).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
}
