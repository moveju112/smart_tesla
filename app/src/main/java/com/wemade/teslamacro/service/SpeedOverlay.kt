package com.wemade.teslamacro.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 다른 앱 위에 뜨는 속도 표시.
 *
 * 내비를 띄우면 우리 화면이 가려진다 — 그래도 속도는 계속 보여야 해서 창을 하나 띄운다.
 * 도면 미학 그대로다: 종이색 판, 모서리 0dp, 잉크 테두리 한 겹, 색 없음.
 * **정지하면 사라진다** — 주차된 차 위에 떠 있는 "0 km/h"는 정보가 아니라 방해다.
 */
class SpeedOverlay(private val context: Context) {

    private var view: TextView? = null

    val canDraw: Boolean get() = Settings.canDrawOverlays(context)

    /**
     * 속도와 (있으면) 다가오는 안전 지점을 보여준다.
     * 창이 없으면 만들고, 있으면 글자만 갈아 끼운다.
     */
    fun show(speedKph: Double, warning: String? = null, over: Boolean = false) {
        if (!canDraw) return
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val text = view ?: create().also { fresh ->
            val attached = runCatching { manager.addView(fresh, layoutParams()) }.isSuccess
            if (!attached) {
                com.wemade.teslable.DiagLog.add("HUD 오버레이 창을 올리지 못했어요")
                return
            }
            view = fresh
        }
        // 경고가 있으면 아래 줄에 붙인다 — 속도가 늘 위, 경고가 아래로 자리가 고정돼야
        // 주행 중 눈이 같은 곳을 본다
        text.text = buildString {
            append(speedKph.toInt())
            if (warning != null) {
                append('\n')
                append(warning)
            }
        }
        // 과속일 때만 적색. 도면의 "지금 봐야 할 것"과 같은 규칙이다
        text.setTextColor(if (over) ALERT else INK)
        text.textSize = if (warning != null) 24f else 34f
    }

    /** 창을 내린다. 두 번 불려도 안전하다 */
    fun hide() {
        val attached = view ?: return
        view = null
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { manager.removeView(attached) }
    }

    private fun create(): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return TextView(context).apply {
            // 도면의 값 표시와 같은 고정폭. 숫자가 바뀔 때마다 폭이 흔들리면 눈이 따라간다
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(INK)
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(PAPER)
                // 모서리를 굴리지 않는다 — 굴리는 순간 이 창만 다른 세계가 된다
                cornerRadius = 0f
                setStroke(maxOf(1, dp(1)), INK)
            }
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 누를 수 없게 둔다 — 주행 중 화면에 새 터치 대상을 만들지 않는다
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        val density = context.resources.displayMetrics.density
        x = (16 * density).toInt()
        y = (16 * density).toInt()
    }

    private companion object {
        /** 도면 팔레트 — 제도지와 잉크. 밤 팔레트는 아직 쓰지 않는다(창이 작아 눈부심이 적다) */
        val PAPER = Color.parseColor("#F2F0E9")
        val INK = Color.parseColor("#1A1A17")

        /** 도면 정정 2색 중 적 — 지금 봐야 할 것에만 쓴다 */
        val ALERT = Color.parseColor("#C8321E")
    }
}
