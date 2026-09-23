# Room entities / Firestore mapping use reflection-free code paths, but keep model field names
# stable for Firestore Map serialisation just in case.
-keepattributes Signature, *Annotation*
-keep class com.studytimelapse.app.data.remote.dto.** { *; }
