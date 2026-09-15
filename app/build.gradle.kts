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
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // Xposed API —— 只在编译期用，运行时由框架提供
    compileOnly("de.robv.android.xposed:api:82")
}
