plugins {
    id("com.android.application")
}

android {
    namespace = "kr.ac.hit.networkprinterstudio"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "kr.ac.hit.networkprinterstudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 104
        versionName = "0.9.14"
        testInstrumentationRunner = "kr.ac.hit.networkprinterstudio.RenderingSmokeInstrumentation"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                targets += "ippjpeg"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
