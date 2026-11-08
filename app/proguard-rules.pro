# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class sh.filee.android.**$$serializer { *; }
-keepclassmembers class sh.filee.android.** {
    *** Companion;
}
-keepclasseswithmembers class sh.filee.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# PDFBox-Android & AndroidSVG
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keep class com.caverock.androidsvg.** { *; }
