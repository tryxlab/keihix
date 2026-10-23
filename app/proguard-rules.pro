# Proguard rules for KeihiX

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Google API Client & Drive
-keep class com.google.api.services.drive.** { *; }
-keep interface com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }
