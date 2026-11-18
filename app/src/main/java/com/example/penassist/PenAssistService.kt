package com.example.penassist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.hypot

/**
 * Floating "Air Command"-style button.
 * Uses an AccessibilityService so it can (1) draw an overlay without the "draw over other apps"
 * permission and (2) take a screenshot without the screen-recording prompt.
 * It does NOT read screen content (canRetrieveWindowContent = false).
 */
class PenAssistService : AccessibilityService() {

    companion object {
        var instance: PenAssistService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var hiddenByUser = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("penassist", MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ImageUtils.cleanOld(this)
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeAll()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeAll()
        instance = null
        super.onDestroy()
    }

    private fun removeAll() {
        closeMenu()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------- bubble ----------

    fun showBubble() {
        hiddenByUser = false
        bubble?.let { it.visibility = View.VISIBLE; return }
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "✒"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E88E5.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", dp(8))
            y = prefs.getInt("by", dp(240))
        }
        tv.setOnTouchListener(BubbleTouch(lp))
        wm.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
    }

    fun hideBubble() {
        hiddenByUser = true
        closeMenu()
        bubble?.visibility = View.GONE
    }

    /** Used by the editor so the bubble doesn't cover the canvas. */
    fun setBubbleVisible(visible: Boolean) {
        if (!visible) closeMenu()
        if (hiddenByUser) return
        bubble?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private inner class BubbleTouch(val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8)) {
                        moved = true
                        closeMenu()
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                    else toggleMenu()
                }
            }
            return true
        }
    }

    // ---------- menu (Air Command) ----------

    private fun toggleMenu() {
        if (menu != null) closeMenu() else openMenu()
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun openMenu() {
        val blp = bubbleLp ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF0202124.toInt())
            }
        }
        fun item(label: String, action: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(24), dp(12))
                setOnClickListener {
                    closeMenu()
                    action()
                }
            })
        }
        item("✍️  เขียนบนหน้าจอ") { captureThen { f -> openEditor(EditorActivity.MODE_WRITE, f) } }
        item("✂️  เลือกและครอบตัด") { captureThen { f -> openEditor(EditorActivity.MODE_CROP, f) } }
        item("📝  โน้ตด่วน") { openEditor(EditorActivity.MODE_NOTE, null) }
        item("🔍  ค้นหา/แปลหน้าจอ") { captureThen { f -> ImageUtils.searchWithLens(this, f) } }
        item("📸  จับภาพหน้าจอ") {
            handler.postDelayed({ performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 300)
        }
        item("🙈  ซ่อนปุ่มลอย") {
            hideBubble()
            toast("เปิดปุ่มลอยอีกครั้งได้จากแอป Pen Assist")
        }

        val screenW = resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (blp.x > screenW / 2) (blp.x - dp(240)).coerceAtLeast(0) else blp.x + dp(60)
            y = blp.y
        }
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }
        wm.addView(box, lp)
        menu = box
    }

    // ---------- actions ----------

    private fun openEditor(mode: String, f: File?) {
        val i = Intent(this, EditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EditorActivity.EXTRA_MODE, mode)
            f?.let { putExtra(EditorActivity.EXTRA_IMAGE, it.absolutePath) }
        }
        startActivity(i)
    }

    /** Hide the bubble, take a screenshot (Android 11+ API), save it to cache, then continue. */
    private fun captureThen(onDone: (File) -> Unit) {
        bubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (!hiddenByUser) bubble?.visibility = View.VISIBLE
                        if (bmp == null) {
                            toast("จับภาพหน้าจอไม่สำเร็จ")
                            return
                        }
                        Thread {
                            val f = ImageUtils.saveTemp(this@PenAssistService, bmp)
                            handler.post { onDone(f) }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!hiddenByUser) bubble?.visibility = View.VISIBLE
                        toast("จับภาพหน้าจอไม่สำเร็จ (รหัส $errorCode) – บางแอปเช่นแอปธนาคารห้ามจับภาพ")
                    }
                })
        }, 300)
    }
}
