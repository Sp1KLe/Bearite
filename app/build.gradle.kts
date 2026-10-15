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
        versionCode = 1
        versionName = "0.1.0"

        // Игра грузится в процесс лаунчера, ABI должны совпадать.
        // У Unity IL2CPP-игр на современных телефонах это arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
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
