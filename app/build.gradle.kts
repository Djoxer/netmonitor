plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.djoxer.netmonitor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.djoxer.netmonitor"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}