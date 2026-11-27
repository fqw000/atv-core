plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.corvofeng.fakeatv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.corvofeng.fakeatv"
        // mitv4a: Android 6.0.1 / API 23. Leanback 1.0.0 needs API 21 at minimum.
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0-mitv4a"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.md"
            excludes += "/META-INF/*.txt"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.leanback:leanback:1.0.0")
    // mitv4a: ADB retained for mouse-cursor tap/swipe (GestureDescription is API 24+).
    // Button injection goes through TVRemoteIME HTTP /key (no ADB needed).
    implementation("dev.mobile:dadb:1.2.6")
}
