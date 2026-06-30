# Strip verbose and debug log calls from release builds.
# Log.i/w/e are retained so crash reports and warnings remain visible.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# JavaMail
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# MediaPipe / Gemma
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
