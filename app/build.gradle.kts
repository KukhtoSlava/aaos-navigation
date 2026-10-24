/*
Copyright 2026 TomTom International BV.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The key lives next to the AutoBox product (vendor/autobox/tomtom.key, gitignored),
// so one key serves every app in the tree. -PtomtomApiKey=... overrides it.
val tomtomApiKey: String = (findProperty("tomtomApiKey") as String?)
    ?: rootProject.file("../../tomtom.key").takeIf { it.isFile }?.readText()?.trim()
    ?: throw GradleException("TomTom API key missing: put it into vendor/autobox/tomtom.key")

// ABIs to package. The emulator product only needs x86_64; each TomTom ABI adds ~200 MB.
val abis = (findProperty("abis") as String? ?: "x86_64").split(",").map { it.trim() }

android {
    namespace = "com.autobox.navigation"
    compileSdk = 35

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.autobox.navigation"
        versionCode = 1
        versionName = "1.0"

        minSdk = 29
        targetSdk = 35

        // Only 64-bit architectures are supported in TomTom Navigation SDK.
        ndk.abiFilters += abis

        missingDimensionStrategy("tomtom-sdk-version", "complete")

        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomApiKey\"")
    }

    buildTypes {
        release {
            // Soong re-signs the APK with the platform key; the debug key only
            // lets the release APK be installed with adb for a quick test.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes.add("META-INF/*")
        // Native libraries stay uncompressed and page-aligned, so the system
        // loads them straight from the APK in /system_ext.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(libs.tomtomSdk.init)
    implementation(libs.tomtomSdkLocation.default)
    implementation(libs.tomtomSdkLocation.simulation)
    implementation(libs.tomtomSdkMaps.compose)
    implementation(libs.tomtomSdkMaps.visualization)

    implementation(platform(libs.androidxCompose.bom))
    implementation(libs.androidxActivity.compose)
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxCar.app)
    implementation(libs.androidxCar.appAutomotive)
    implementation(libs.androidxComposeFoundation)
    implementation(libs.androidxComposeMaterial3)
    implementation(libs.androidxComposeUi)
    implementation(libs.androidxCore.ktx)
    implementation(libs.androidxLifecycle.compose)
    implementation(libs.androidxLifecycle.service)
}
