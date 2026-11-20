plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hobby.quizbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hobby.quizbot"
        minSdk = 26
        targetSdk = 34
        // Each GitHub build gets a higher version number so Android accepts it as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.$versionCode"
    }

    // One fixed signing key for every build, so new versions install as updates
    // (keeps the Accessibility permission and your learned answers).
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/quizbot.jks")
            storePassword = "quizbot-hobby"
            keyAlias = "quizbot"
            keyPassword = "quizbot-hobby"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
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
}

dependencies {
    // No third-party libraries: plain Android SDK only.
}
