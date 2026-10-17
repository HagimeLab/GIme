# GIme

离线刷题 App，当前版本 **v1.0.0**。

## 功能

**题库**：960 题，来自四份题源——HCIP-Cloud Service（338）、HCNA-Storage H13-611（254）、HCNA-Cloud 云计算（222）、ICT 大赛计算赛道（146）。单选 413 / 多选 285 / 判断 262。

**刷题模式**：顺序练习、背题、模拟考试、错题本重刷、收藏。答错自动进错题本，再答对不会自动移出（需手动管理）。

**组卷**：可勾选多个题库合成一池随机抽指定题数；也可按单选 / 多选 / 判断分别指定题数，并设置考试时长。

**解析**：957 题带解析，其中题源原文 106 条、按知识点撰写 851 条。界面上用两种胶囊标注来源，撰写部分不冒充权威。

**导入题库**：支持 `.docx` / `txt` / `md`，题目与解析一起识别；文档里用红字标答案的也能识别。可在 App 内选文件，也可在微信里长按文件 →「用其他应用打开」→ 选 GIme。不支持 PDF 和老版 `.doc`，需先在电脑上转成 docx 或 txt。

**题库管理**：题库卡片左滑露出按钮，导入的题库可删除、内置题库可隐藏，都有二次确认；隐藏的题库可在「我的」里恢复。

**检查更新**：「我的 → 检查更新」比对 GitHub Release 版本，有新版可直接下载安装。

**界面**：黑白灰新拟态，无任何彩色。圆形扩散换肤（深色 / 浅色）、滑杆惯性吸附、批量错峰勾选、卡片曲线删除、卡片堆叠压缩。支持字号调节、震动反馈开关。

**离线**：无网络请求（检查更新除外），题目和做题记录都存在本机。最低 Android 7.0。

## 文件目录

```
GIme/
├── README.md
├── LICENSE
├── .gitignore
├── build.cmd                        一键构建（固定 JDK 21 与 SDK 路径）
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties                含 android.overridePathCheck=true
├── design/
│   └── ic_master.png                应用图标母图，五档 mipmap 由它缩放
├── docs/
│   └── explain_review.md            答案存疑 / 题干不全的复核清单（68 条）
└── app/
    ├── build.gradle.kts             版本号与签名配置在这里
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── bank.js              题库数据（960 题 + 解析）
        │   └── index.html           全部界面、样式与逻辑（单文件）
        ├── java/com/fan/shuatiku/
        │   ├── MainActivity.java    WebView 壳、JS 桥、边到边与系统栏适配
        │   ├── TextExtract.java     从 docx / txt 抽取正文，红字标记答案
        │   ├── Updater.java         检查更新、下载 APK、拉起系统安装页
        │   └── Ver.java             版本号比较（纯逻辑，无 Android 依赖）
        └── res/
            └── mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher.png
```

界面与逻辑都在 `app/src/main/assets/index.html` 一个文件里；题库数据在 `bank.js`。改题或补解析改这两个文件即可，不需要动 Java。

`signing/` 目录（发布签名密钥）不在仓库里，只存在于构建机上。

## 下载

**APK 直链**：<https://github.com/HagimeLab/GIme/releases/download/v1.0.0/GIme-v1.0.0.apk>

**Release 页**：<https://github.com/HagimeLab/GIme/releases/tag/v1.0.0>

| | |
|---|---|
| 文件 | `GIme-v1.0.0.apk` |
| 大小 | 940,853 字节 |
| SHA-256 | `85e2b3fc299daf406dcb06a2ba07e3eac05b415c11d5b60177bf69672e77eb1c` |
| 最低系统 | Android 7.0（API 24） |

把 APK 传到手机上点开安装，需要允许「安装未知来源应用」。装好后后续版本可以在 App 内直接更新。
