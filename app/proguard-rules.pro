# ARCore and kotlinx.serialization ship their own consumer rules.

# osmdroid (site map) has no consumer rules; it references optional classes it never loads here.
-dontwarn org.osmdroid.**

# Keep serializers of the project model (read/written as JSON).
-keepclassmembers class com.banyawa.sitescanner.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.banyawa.sitescanner.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
