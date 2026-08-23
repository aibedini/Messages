import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 10
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Production backend URL — override in local.properties via gradle.properties if needed
        val backendUrl = project.findProperty("GATEWAY_BACKEND_URL")?.toString()
            ?: "https://gaitway.autonomousone.in"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
