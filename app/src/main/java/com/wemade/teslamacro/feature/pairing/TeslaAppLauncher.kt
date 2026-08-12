package com.wemade.teslamacro.feature.pairing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 공식 테슬라 앱을 여는 통로.
 *
 * VIN은 차량 화면이나 테슬라 앱에서 확인한다.
 * 앱을 직접 찾아 들어가게 두면 등록 흐름이 거기서 끊긴다.
 */
object TeslaAppLauncher {

    private const val PACKAGE = "com.teslamotors.tesla"
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /**
     * 설치돼 있으면 앱을, 없으면 스토어를 연다.
     * 둘 다 실패하면 false를 돌려주고 호출부가 안내한다.
     */
    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            // 우리 화면 위에 새 작업으로 띄운다. 돌아오면 등록 화면이 그대로 남아 있다
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return true
        }
        return openStore(context)
    }

    private fun openStore(context: Context): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (notFound: ActivityNotFoundException) {
        false
    }

}
