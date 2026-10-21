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
        versionCode = 8
        versionName = "1.0.7"
    }

    // BuildConfig.DEBUG 用来区分构建变体：Release 里必须关掉 WebView 调试，
    // 这件事只能靠编译期常量决定，不能靠运行时猜。AGP 8 起默认不生成 BuildConfig。
    buildFeatures {
        buildConfig = true
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

dependencies {
    // PDF 正文抽取。中文 PDF 的字体编码（CID / 预定义 CMap / ToUnicode）组合太多，
    // 自己写解析器能过样例却过不了真实题库，这里用久经考验的实现，且只在这一层用到。
    //
    // 排除 BouncyCastle：PDFBox 只在「打开加密 PDF」和「验签」时才用到它，
    // 而本项目对加密文件的态度是明确拒绝、告诉用户去解密，不需要真能解。
    // 留着它会让 APK 从 0.9MB 涨到 9MB（光 dex 就多 8MB），不值得。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
}