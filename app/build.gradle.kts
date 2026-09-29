import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One fixed key, so every new APK installs over the previous one and keeps its data. CI
// decodes it from the repository secrets; without them (forks, local builds) the usual
// throwaway debug key signs instead.
val stableKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
val stableKeyPassword = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() }

// CI builds count up so each one is an update; local builds stay at 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.banyawa.sitescanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.banyawa.sitescanner"
        // ARCore requires API 24+.
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (stableKeystore != null && stableKeyPassword != null) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = stableKeyPassword
                keyAlias = "sitescanner"
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.arcore)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
