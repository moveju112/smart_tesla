package com.wemade.teslamacro.data.nav

import android.net.Uri

/**
 * 길안내를 넘길 내비 앱.
 *
 * 앱마다 URL 스킴이 다르고, 같은 앱도 버전에 따라 받는 형식이 갈린다.
 * 그래서 후보를 여러 개 두고 순서대로 던진다 — 첫 번째가 안 열리면 다음 것으로.
 * 하나만 박아두면 앱이 조용히 안 뜨고 로그에는 성공으로 남는다.
 */
enum class NavigatorApp(
    val label: String,
    /** 설치 여부 확인·인텐트 대상 지정에 쓰는 패키지. 앞에 있는 것부터 찾는다 */
    val packages: List<String>,
) {
    NAVER("네이버 지도", listOf("com.nhn.android.nmap")),
    KAKAO("카카오내비", listOf("com.locnall.KimGiSa")),
    TMAP("티맵", listOf("com.skt.tmap.ku", "com.skt.skaf.l001mtm091")),
    GOOGLE("구글 지도", listOf("com.google.android.apps.maps"));

    /**
     * 좌표로 길안내를 시작하는 URI 후보들. 앞에서부터 시도한다.
     *
     * [appPackage]는 네이버가 요구하는 호출자 식별용이다 — 없으면 앱이 뜨고도 안내를 안 건다.
     */
    fun uris(latitude: Double, longitude: Double, label: String, appPackage: String): List<Uri> {
        val name = Uri.encode(label)
        return when (this) {
            NAVER -> listOf(
                "nmap://route/car?dlat=$latitude&dlng=$longitude&dname=$name&appname=$appPackage",
                "nmap://navigation?dlat=$latitude&dlng=$longitude&dname=$name&appname=$appPackage",
            )

            // 카카오내비는 "위도,경도" 순서다 (지도 API의 x·y 순서와 반대라 헷갈리는 자리)
            KAKAO -> listOf(
                "kakaonavi://navigate?ep=$latitude,$longitude&by=CAR",
                "kakaomap://route?ep=$latitude,$longitude&by=CAR",
            )

            // 티맵은 goalx가 경도, goaly가 위도다
            TMAP -> listOf(
                "tmap://route?goalname=$name&goalx=$longitude&goaly=$latitude",
                "tmap://route?goalname=$name&goalx=$longitude&goaly=$latitude&carType=0",
            )

            GOOGLE -> listOf(
                "google.navigation:q=$latitude,$longitude&mode=d",
                "geo:$latitude,$longitude?q=$latitude,$longitude($name)",
            )
        }.map(Uri::parse)
    }

    companion object {
        /** 저장된 값이 깨졌거나 처음이면 네이버. 이 기기에 이미 깔려 있던 기본값이다 */
        val Default = NAVER

        fun of(name: String?): NavigatorApp =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
