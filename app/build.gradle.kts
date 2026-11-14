plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bearite.patcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bearite.patcher"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // Игра грузится в процесс лаунчера, ABI должны совпадать.
        // У Unity IL2CPP-игр на современных телефонах это arm64-v8a.
        // Один универсальный APK: 64-битные (arm64-v8a) и 32-битные (armeabi-v7a) телефоны.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    // Постоянная подпись debug-сборок: без неё каждая сборка CI подписывается новым ключом и не ставится поверх прежней
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // .so должны лежать извлечёнными: загрузчик классов игры ищет их по путям
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
