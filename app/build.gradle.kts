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
        versionName = "0.2.0" + (if (project.findProperty("abi") == "armeabi-v7a") "-v7a" else "")

        // Игра грузится в процесс лаунчера, ABI должны совпадать.
        // У Unity IL2CPP-игр на современных телефонах это arm64-v8a.
        // Для телефонов с 32-битным процессором: gradle -Pabi=armeabi-v7a (отдельный APK, applicationId тот же).
        ndk { abiFilters += (project.findProperty("abi") as String? ?: "arm64-v8a") }

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

// Папка achievements/ в корне репозитория (список, иконки, анимация) попадает в assets APK как achievements/
val syncAchievements = tasks.register<Copy>("syncAchievements") {
    from(rootProject.file("achievements")) { exclude("README.md") }
    into(layout.buildDirectory.dir("generated/achAssets/achievements"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/achAssets"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(syncAchievements) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
