plugins {
    id("com.android.application")
}

android {
    namespace = "kr.ac.hit.networkprinterstudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.ac.hit.networkprinterstudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 97
        versionName = "0.9.7"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
