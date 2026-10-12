# `app/proguard-rules.pro`

```proguard
# ═══════════════════════════════════════════════════════════════
# NurVPN — ProGuard / R8 qoidalari
# ═══════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════
# 1. libbox (sing-box Go + JNI) — reflection + JNI calls
# ═══════════════════════════════════════════════════════════════
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keepclassmembers class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keepclassmembers class go.** { *; }

# Reflection ishlatadigan interfeyslar
-keepclassmembers class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.CommandServerHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.CommandClientHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.InterfaceUpdateListener { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.HTTPResponseWriteToProgressHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.NetworkQualityTestHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.OpenConnectStatusHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.OpenVPNStatusHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.STUNTestHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.TailscalePingHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.TailscaleSSHHandler { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.TailscaleStatusHandler { *; }

# Aniq klasslar (Libbox$proxy* bilan chaqiriladi)
-keep class io.nekohasekai.libbox.Libbox { *; }
-keep class io.nekohasekai.libbox.Libbox$* { *; }
-keep class io.nekohasekai.libbox.CommandClient { *; }
-keep class io.nekohasekai.libbox.CommandServer { *; }
-keep class io.nekohasekai.libbox.PlatformInterface { *; }
-keep class io.nekohasekai.libbox.SystemProxyStatus { *; }
-keep class io.nekohasekai.libbox.SetupOptions { *; }
-keep class io.nekohasekai.libbox.TunOptions { *; }
-keep class io.nekohasekai.libbox.RoutePrefix { *; }
-keep class io.nekohasekai.libbox.ConnectionOwner { *; }
-keep class io.nekohasekai.libbox.LocalDNSTransport { *; }
-keep class io.nekohasekai.libbox.WIFIState { *; }
-keep class io.nekohasekai.libbox.NetworkInterface { *; }
-keep class io.nekohasekai.libbox.NetworkInterfaceIterator { *; }
-keep class io.nekohasekai.libbox.StringIterator { *; }
-keep class io.nekohasekai.libbox.PlatformUser { *; }
-keep class io.nekohasekai.libbox.ShellSession { *; }
-keep class io.nekohasekai.libbox.BridgeOptions { *; }
-keep class io.nekohasekai.libbox.BridgeSession { *; }
-keep class io.nekohasekai.libbox.Notification { *; }

-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**

# ═══════════════════════════════════════════════════════════════
# 2. Kotlin
# ═══════════════════════════════════════════════════════════════
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }
-keep class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ═══════════════════════════════════════════════════════════════
# 3. App data classes (JSON / storage)
# ═══════════════════════════════════════════════════════════════
-keep class com.nurvpn.app.core.** { *; }
-keep class com.nurvpn.app.storage.** { *; }
-keep class com.nurvpn.app.parser.** { *; }
-keep class com.nurvpn.app.config.** { *; }
-keep class com.nurvpn.app.util.** { *; }

# ═══════════════════════════════════════════════════════════════
# 4. Android komponentlar (Manifest'da)
# ═══════════════════════════════════════════════════════════════
-keep class com.nurvpn.app.ui.MainActivity { *; }
-keep class com.nurvpn.app.ui.awg.AWGEditorActivity { *; }
-keep class com.nurvpn.app.ui.awg.MasqueEditorActivity { *; }
-keep class com.nurvpn.app.ui.editor.ServerEditorActivity { *; }
-keep class com.nurvpn.app.ui.split.SplitAppsActivity { *; }
-keep class com.nurvpn.app.ui.qr.QrScanActivity { *; }
-keep class com.nurvpn.app.ui.qr.QrShowDialog { *; }
-keep class com.nurvpn.app.service.NurVpnService { *; }
-keep class com.nurvpn.app.service.NurVpnTileService { *; }

# ═══════════════════════════════════════════════════════════════
# 5. Annotations, source info (crash log uchun)
# ═══════════════════════════════════════════════════════════════
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>(...);
}

# ═══════════════════════════════════════════════════════════════
# 6. AndroidX / Material
# ═══════════════════════════════════════════════════════════════
-keep class androidx.core.app.** { *; }
-keep class androidx.fragment.app.** { *; }
-keep class androidx.recyclerview.widget.** { *; }
-keep class com.google.android.material.** { *; }
-keep class androidx.preference.** { *; }
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ═══════════════════════════════════════════════════════════════
# 7. ZXing (QR kod)
# ═══════════════════════════════════════════════════════════════
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# ═══════════════════════════════════════════════════════════════
# 8. Release'da Log.* olib tashlash
# ═══════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# BuildConfig'ni saqlash
-keep class com.nurvpn.app.BuildConfig { *; }

# ═══════════════════════════════════════════════════════════════
# 9. Ogohlantirishlarni o'chirish
# ═══════════════════════════════════════════════════════════════
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**

```
