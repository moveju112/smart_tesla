package com.wemade.teslamacro.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.wemade.teslable.DiagLog

/**
 * 설치기가 돌려주는 결과를 받는다.
 * 확인 화면이 필요하다고 하면(첫 설치 때) 그 화면을 띄우고, 그 뒤로는 조용히 끝난다.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 확인이 필요하다는 뜻. 취소하고 돌아와도 다시 누를 수 있게 상태를 되돌린다
                AppUpdater.restoreAvailable()
                val confirm = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { DiagLog.add("업데이트 · 설치 화면을 열지 못함") }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DiagLog.add("업데이트 설치 완료")
                AppUpdater.clearPendingApk(context)
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppUpdater.state.value =
                    UpdateState.Failed("설치하지 못했어요 · ${message ?: "알 수 없는 이유"}")
                DiagLog.add("업데이트 설치 실패 · ${message ?: "알 수 없는 이유"}")
            }
        }
    }
}
