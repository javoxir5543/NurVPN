# ═══════════════════════════════════════════════════════
# NurVPN — ProGuard qoidalari
# ═══════════════════════════════════════════════════════

# ─────── libbox / sing-box / gomobile ───────
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keep class mob.** { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**
-dontwarn mob.**

# libbox reflection uchun
-keepclassmembers class io.nekohasekai.libbox.** {
    native <methods>;
    *** <fields>;
}

# ─────── ZXing (QR) ───────
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**

# ─────── Kotlin ───────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ─────── AndroidX ───────
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ─────── Material ───────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ─────── JSON ───────
-keep class org.json.** { *; }

# ─────── NurVPN ───────
-keep class com.nurvpn.app.** { *; }
-keepclassmembers class com.nurvpn.app.** {
    *;
}

# Data classes (JSON serialization uchun)
-keep class com.nurvpn.app.ServerItem { *; }
-keep class com.nurvpn.app.Subscription { *; }
-keep class com.nurvpn.app.AWGConfig { *; }
-keep class com.nurvpn.app.OpenSourceSubscription { *; }
-keep class com.nurvpn.app.UserInfo { *; }
-keep class com.nurvpn.app.BuiltinAwgConfigs { *; }

# ─────── Enum ───────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─────── Parcelable ───────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ─────── Serializable ───────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─────── Native methods ───────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─────── Reflection ───────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─────── Debug ma'lumotlari ───────
-keepattributes SourceFile,LineNumberTable

# ─────── Boshqa ───────
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
