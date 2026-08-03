# ProGuard / R8 Rules for Messages Gateway App

# Jetpack Compose rules
-keep class androidx.compose.** { *; }

# Preserve Gateway models and service classes
-keep class com.autonomousone.messages.gateway.** { *; }
-keep class com.autonomousone.messages.model.** { *; }

# Keep json serialized properties if any
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
