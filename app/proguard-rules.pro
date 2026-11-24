-keep class com.jhaiian.clint.** { *; }
-keepattributes SourceFile,LineNumberTable
-keep class android.webkit.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep public class com.caverock.androidsvg.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn com.google.flatbuffers.**
-dontwarn pl.droidsonroids.gif.**

# The FOSS flavor compiles against ML Kit but does not bundle it, so R8 sees the
# comic translation classes referencing types that are absent at runtime. The feature
# is disabled there at runtime, so the missing classes are expected.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
