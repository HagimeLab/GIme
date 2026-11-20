# R8 keep：WebView 桥是反射调用，桥方法被裁掉等于整个 App 的页面失联。
-keepattributes JavascriptInterface,*Annotation*,Signature
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.fan.shuatiku.agent.** { *; }
-keep class com.fan.shuatiku.SecurePrefs { *; }
-keep class org.json.** { *; }
-dontwarn org.json.**
# sherpa-onnx 的 Kotlin/Java 绑定通过 JNI 调用，类名/方法名不能被 R8 改。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
# Kotlin data class 编译产物依赖 stdlib 的 Intrinsics；缺类会在初始化时崩。
-keep class kotlin.jvm.internal.Intrinsics { *; }
-dontwarn kotlin.**

# PDFBox 的可选依赖（JPX / BC 签名链）本 App 不打包；只关警告，不改变行为。
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
