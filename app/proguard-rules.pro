# kotlinx.serialization — @Serializable 클래스의 합성 serializer를 살려둔다
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$serializer {
    *** INSTANCE;
}

# Vosk / JNA — 네이티브에서 이름으로 찾는 클래스라 난독화하면 못 찾는다
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn java.awt.**
