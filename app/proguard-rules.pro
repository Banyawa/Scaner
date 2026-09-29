# ARCore and kotlinx.serialization ship their own consumer rules.

# Keep serializers of the project model (read/written as JSON).
-keepclassmembers class com.banyawa.sitescanner.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.banyawa.sitescanner.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
