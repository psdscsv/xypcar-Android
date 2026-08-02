# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath

# 高德地图 SDK 混淆规则
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.amap.api.maps.** { *; }
-keep class com.amap.api.maps2d.** { *; }
-keep class com.amap.api.location.** { *; }
-keep class com.amap.api.fence.** { *; }
-keep class com.autonavi.aps.amapapi.model.** { *; }
-keep class com.amap.api.services.** { *; }
-keep class com.amap.api.navi.** { *; }

# 保留高德地图 JNI 调用的类和方法
-keep class com.amap.api.maps.MapView { *; }
-keep class com.amap.api.maps.AMap { *; }
-keep class com.amap.api.maps.model.** { *; }

# 保留所有 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Kotlin 协程相关
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.android.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class com.psd.xypcar.remote.** { *; }

# 保留 JSON 解析类
-keep class org.json.** { *; }

# 保留自定义控件
-keep class com.psd.xypcar.control.JoystickView { *; }

# 保留所有 Activity 和 Service
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 保留所有实现了 Parcelable 的类
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 R 文件
-keep class **.R$* { *; }

# 保留布局文件中的 View 构造方法
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# 保留 Gson（如果使用）
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# 特别针对高德地图定位 SDK 的额外规则
-keep class com.amap.location.** { *; }
-keep class com.amap.location.* { *; }
-keep class com.loc.** { *; }
-keep class com.autonavi.aps.** { *; }
-keep class com.autonavi.loc.** { *; }

# 保留所有高德地图 JNI 类
-keep class com.autonavi.jni.** { *; }

# 保留高德地图的日志类
-keep class com.amap.location.support.log.** { *; }

# 保留高德地图的 apssdk
-keep class com.autonavi.aps.amapapi.** { *; }