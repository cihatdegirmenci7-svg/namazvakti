plugins {
    id("com.android.application")
}

android {
    namespace = "tr.com.haktan.vakit"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.com.haktan.vakit"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
}
