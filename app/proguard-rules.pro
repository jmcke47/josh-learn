-keep class com.josh.learn.** { *; }
# LiteRT-LM (JNI callbacks + gson/reflection)
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn kotlin.reflect.**
