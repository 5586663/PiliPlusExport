plugins {
    id("com.android.application")
}

android {
    namespace = "com.piliplus.export"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piliplus.export"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // 只构建 arm64-v8a：libffmpeg_core.so 仅提供该架构。
        // 若需 armeabi-v7a / x86_64，须自行用 xmake 编译对应 so 后
        // 放入 jniLibs 并在此追加 ABI。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 预编译的 libffmpeg_core.so 放在 jniLibs，需避免被压缩（否则 dlopen 失败）
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名即可，LSPosed 模块不需要正式签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 不因缺少某 ABI 的 so 而失败（CI 只放 arm64）
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // Xposed API —— 只在编译期用，运行时由框架提供
    compileOnly("de.robv.android.xposed:api:82")
}
