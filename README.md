# GIme

离线刷题 App。

## 软件功能

**刷题**：顺序练习、背题模式、模拟考试、错题本重刷、收藏。答错自动进错题本，再答对不会自动移出。

**组卷**：可勾选多个题库合成一池随机抽题，指定总题数；也可按单选 / 多选 / 判断分别指定题数，并设置考试时长。

**搜索**：按题干或解析关键词全库检索，可从结果直接进题。

**解析**：答完题在折叠区显示本题答案与解析，可设置答完是否自动展开。

**导入题库**：支持 `.docx` / `txt` / `md`，题目与解析一起识别；文档中用颜色标注答案的也能识别。可在 App 内选文件，也可从微信「用其他应用打开」直接导入。

**题库管理**：题库卡片左滑露出操作按钮，导入的题库可删除、内置题库可隐藏，均有二次确认；隐藏的题库可一键恢复。

**检查更新**：App 内比对已发布版本，有新版本时提示并可直接下载安装。

**界面**：深浅两套主题，切换时从手指触点圆形扩散；支持字号调节与震动反馈。

**离线**：题目与做题记录都存在本机，除检查更新外无网络请求。

## 源码结构

```
.
├── README.md
├── LICENSE
├── .gitignore
├── build.cmd                        构建脚本
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── docs/
│   └── explain_review.md            补充说明文档
└── app/
    ├── build.gradle.kts             版本号配置
    └── src/main/
        ├── AndroidManifest.xml      权限与入口声明
        ├── assets/
        │   ├── bank.js              题库数据
        │   └── index.html           全部界面、样式与交互逻辑
        ├── java/com/fan/shuatiku/
        │   ├── MainActivity.java    WebView 容器、页面与系统的桥接、屏幕适配
        │   ├── TextExtract.java     从导入文件抽取正文与答案标记
        │   ├── Updater.java         版本检查、下载安装包
        │   └── Ver.java             版本号比较
        └── res/
            └── mipmap-*/            各密度资源
```

界面、样式和全部交互逻辑都在 `app/src/main/assets/index.html` 一个文件内；题库数据在 `app/src/main/assets/bank.js`。修改这两处即可完成大部分内容调整。

Android 侧只承担容器职责：`MainActivity.java` 提供 WebView 与系统能力之间的桥（选文件、读写题库、震动、系统栏适配、返回键），`TextExtract.java` 负责解析导入文件，`Updater.java` 与 `Ver.java` 负责更新检查。

## 下载

**APK 直链**：<https://github.com/HagimeLab/GIme/releases/download/v1.0.0/GIme-v1.0.0.apk>

**Release 页**：<https://github.com/HagimeLab/GIme/releases/tag/v1.0.0>

最低 Android 7.0。将 APK 传到手机上点开安装，需要允许「安装未知来源应用」；装好后后续版本可在 App 内直接更新。
