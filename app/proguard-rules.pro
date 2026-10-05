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

# 별도 셸 프로세스에서 이름으로 호출하는 가상 화면 진입점을 유지한다.
-keep class com.wemade.teslamacro.data.nav.NaverDisplaySession { *; }
# Conscrypt의 공식 consumer 규칙에 없는 구형 시스템 어댑터 타입만 예외 처리한다.
# 공개 SDK에 없는 시스템 타입이며 앱은 이 어댑터 대신 자체 TLS 소켓 경로를 사용한다.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
