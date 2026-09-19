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

# 카카오내비 SDK는 내부에서 리플렉션·네이티브 바인딩을 쓴다. 통째로 남긴다
-keep class com.kakaomobility.knsdk.** { *; }
-dontwarn com.kakaomobility.knsdk.**
