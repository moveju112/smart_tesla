package com.wemade.teslamacro.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.wemade.teslable.DiagLog

/** 다른 앱 위 표시 권한 화면을 열고, 제조사 미지원 시 앱 정보 화면으로 보낸다 */
fun openOverlayPermissionSettings(context: Context) {
    val appUri = Uri.parse("package:${context.packageName}")
    val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(fallback) }.onFailure {
            DiagLog.add("권한 · 다른 앱 위 표시 설정 화면을 열지 못함")
            Toast.makeText(
                context,
                "앱 정보에서 '다른 앱 위에 표시'를 허용해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
