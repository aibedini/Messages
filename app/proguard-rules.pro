# ProGuard / R8 Rules for Messages Gateway App

# Jetpack Compose rules
-keep class androidx.compose.** { *; }

# Preserve Gateway models and service classes
-keep class com.autonomousone.messages.gateway.** { *; }
-keep class com.autonomousone.messages.model.** { *; }

# mmslib (org.fossify:mmslib — Fossify fork of klinker android-smsmms).
# Receivers/services are manifest-referenced (kept automatically), but the
# transaction/PDU machinery is reached via the library's own broadcast wiring,
# so keep it whole and silence warnings from bundled legacy deps.
-keep class com.klinker.android.** { *; }
-keep class com.android.mms.** { *; }
-dontwarn com.android.mms.**
-dontwarn com.klinker.android.**
-dontwarn com.squareup.okhttp.**
-dontwarn org.apache.http.**

# Keep json serialized properties if any
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
