# NewPipeExtractor parses YouTube's player JS with Rhino and reaches into its own classes
# reflectively, so neither survives aggressive shrinking.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**

# JNI entry points for LAME.
-keep class com.naman14.androidlame.** { *; }

# Desktop-only APIs some transitive dependencies reference but never call on Android.
-dontwarn javax.annotation.**
-dontwarn javax.sound.**
-dontwarn java.awt.**
-dontwarn edu.umd.cs.findbugs.annotations.**
