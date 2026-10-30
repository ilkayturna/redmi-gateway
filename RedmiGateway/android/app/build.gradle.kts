plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ilkay.redmigateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ilkay.redmigateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 225
        versionName = "2.0.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Android lint never ran on this project until 16.10.2026, and its first run found two real
    // crashes: setShowWhenLocked at minSdk 26, and an unguarded RoleManager reference at API 29.
    // Both are fixed. The baseline records what remains, so the check can run in CI from now on
    // and fail on anything new rather than on the backlog.
    //
    // Most of what the baseline holds is deliberate and documented in docs/LINT-NOTLARI.md:
    // BleServerManager calls Bluetooth APIs behind a helper lint cannot see through, the three
    // StaticFieldLeak warnings are intentional singletons, and the wakelock releases are all
    // guarded by isHeld.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The unit tests run against the stubbed android.jar, where org.json returns default values,
    // so JSONObject.put would hand back null and any assertion on the payload would be vacuous.
    // This artifact supplies the real implementation on the test classpath instead.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    // HTTP/2 client for Apple push (APNs) when the iPhone is away from the Redmi.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
