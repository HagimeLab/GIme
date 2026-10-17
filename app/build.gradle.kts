import java.util.Properties

plugins {
    id("com.android.application")
}

// 发布签名材料放在 signing/（已 gitignore）。换机器前务必把 gime-release.jks 和
// keystore.properties 一起备份——签名一对不上，已装用户就只能卸载重装。
val keystoreProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.fan.shuatiku"
    compileSdk = 36

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile").replace("../", ""))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.fan.shuatiku"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        // debug 也用同一把钥匙。否则 debug 装过的手机换成 release 会因签名不同被系统拒绝安装，
        // 只能卸载重装、把做题记录一起带走。
        getByName("debug") {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
