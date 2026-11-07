package com.baah.nativehost

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView

/** A black cover leaves Android awake; the optional hardware mode belongs to ControlService. */
class ScreenCover(private val context: Context) {
    val visible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val window = context.getSystemService(WindowManager::class.java)
    private var view: FrameLayout? = null
    fun show() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (view != null) return
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        frame.contentDescription = "熄屏挂机，双击退出"
        androidx.core.view.ViewCompat.replaceAccessibilityAction(frame, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "退出熄屏挂机") { _, _ -> hide(); true }
        val label = TextView(context).apply { text = UiStrings.text("双击退出熄屏挂机"); setTextColor(Color.GRAY); textSize = 14f; gravity = Gravity.CENTER }
        frame.addView(label, FrameLayout.LayoutParams(-1, -1))
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onDoubleTap(event: MotionEvent): Boolean { hide(); return true }
        })
        frame.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.OPAQUE)
        if (android.os.Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.addView(frame, params); view = frame; visible.value = true
        label.animate().alpha(0f).setStartDelay(2000).setDuration(1000).start()
    }
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) { Handler(Looper.getMainLooper()).post { hide() }; return }
        view?.let { window.removeView(it) }; view = null; visible.value = false
    }
}
