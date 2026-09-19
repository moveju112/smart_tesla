# 전송 계층은 리플렉션을 쓰지 않는다. 소비자 측 추가 규칙 없음.

# protobuf javalite는 필드를 리플렉션으로 채운다. R8이 지우면 VCSEC 응답이 통째로 빈다
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-dontwarn com.google.protobuf.**
