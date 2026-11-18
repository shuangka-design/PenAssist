package com.example.penassist

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Full-screen editor: write on a screenshot, smart-select (crop), or a blank quick note. */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_IMAGE = "image"
        const val MODE_WRITE = "write"
        const val MODE_CROP = "crop"
        const val MODE_NOTE = "note"
    }

    private lateinit var board: DrawingView
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var colorBtn: TextView
    private lateinit var fingerBtn: TextView
    private val toolButtons = HashMap<DrawingView.Tool, TextView>()
    private var srcFile: File? = null
    private var barAtTop = true

    private val colors = intArrayOf(
        Color.RED, Color.BLACK, 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFFFC107.toInt(), Color.WHITE
    )
    private val colorNames = arrayOf("แดง", "ดำ", "น้ำเงิน", "เขียว", "เหลือง", "ขาว")
    private var colorIdx = 0
    private val sizes = floatArrayOf(1.5f, 3f, 5f, 8f)
    private var sizeIdx = 1

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setDecorFitsSystemWindows(false)

        board = DrawingView(this)
        intent.getStringExtra(EXTRA_IMAGE)?.let { path ->
            srcFile = File(path)
            BitmapFactory.decodeFile(path)?.let { board.backgroundImage = it }
        }
        board.penColor = colors[colorIdx]
        board.baseWidth = dp(sizes[sizeIdx])

        val root = FrameLayout(this)
        root.addView(board, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(6), dpi(4), dpi(6), dpi(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(0xEE202124.toInt())
            }
        }
        barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        root.addView(barScroll, barParams())
        setContentView(root)
        hideSystemBars()

        toolButtons[DrawingView.Tool.PEN] = addBtn(bar, "✏️ ปากกา") { selectTool(DrawingView.Tool.PEN) }
        toolButtons[DrawingView.Tool.HIGHLIGHTER] = addBtn(bar, "🖍 ไฮไลต์") { selectTool(DrawingView.Tool.HIGHLIGHTER) }
        toolButtons[DrawingView.Tool.ERASER] = addBtn(bar, "🧽 ยางลบ") { selectTool(DrawingView.Tool.ERASER) }
        toolButtons[DrawingView.Tool.CROP] = addBtn(bar, "✂️ เลือกพื้นที่") { selectTool(DrawingView.Tool.CROP) }
        colorBtn = addBtn(bar, "● สี") {
            colorIdx = (colorIdx + 1) % colors.size
            board.penColor = colors[colorIdx]
            updateColorBtn()
            toast("สี: " + colorNames[colorIdx])
        }
        addBtn(bar, "📏 ขนาด") {
            sizeIdx = (sizeIdx + 1) % sizes.size
            board.baseWidth = dp(sizes[sizeIdx])
            toast("ขนาดเส้น ${sizeIdx + 1}/${sizes.size}")
        }
        addBtn(bar, "↶ เลิกทำ") { board.undo() }
        addBtn(bar, "🗑 ล้าง") { board.clearAll() }
        fingerBtn = addBtn(bar, "") {
            board.fingerDraws = !board.fingerDraws
            updateFingerBtn()
        }
        addBtn(bar, "💾 บันทึก") { saveToGallery() }
        addBtn(bar, "📤 แชร์") { exportTemp { ImageUtils.shareImage(this, it) } }
        addBtn(bar, "🔍 ค้นหา/แปล") { exportTemp { ImageUtils.searchWithLens(this, it) } }
        addBtn(bar, "⇅") {
            barAtTop = !barAtTop
            barScroll.layoutParams = barParams()
        }
        addBtn(bar, "✕ ปิด") { finish() }
        updateColorBtn()
        updateFingerBtn()

        if (intent.getStringExtra(EXTRA_MODE) == MODE_CROP) {
            selectTool(DrawingView.Tool.CROP)
            toast("ลากเพื่อเลือกพื้นที่ แล้วกด บันทึก / แชร์ / ค้นหา")
        } else {
            selectTool(DrawingView.Tool.PEN)
        }
    }

    private fun barParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            (if (barAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dpi(12)
            bottomMargin = dpi(12)
        }

    private fun addBtn(bar: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            setOnClickListener { onClick() }
        }
        bar.addView(tv)
        return tv
    }

    private fun selectTool(t: DrawingView.Tool) {
        if (t == DrawingView.Tool.CROP && board.tool == DrawingView.Tool.CROP && board.cropRect != null) {
            board.clearCrop()
            toast("ยกเลิกพื้นที่ที่เลือกแล้ว")
        }
        board.tool = t
        toolButtons.forEach { (tool, btn) ->
            btn.background = if (tool == t) GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(0xFF1E88E5.toInt())
            } else null
        }
    }

    private fun updateColorBtn() = colorBtn.setTextColor(colors[colorIdx])

    private fun updateFingerBtn() {
        fingerBtn.text = if (board.fingerDraws) "✋ นิ้ว: เขียนได้" else "✋ นิ้ว: ไม่เขียน"
    }

    private fun saveToGallery() {
        val bmp = board.export()
        Thread {
            val uri = ImageUtils.saveToGallery(this, bmp)
            runOnUiThread { toast(if (uri != null) "บันทึกแล้วใน Pictures/PenAssist" else "บันทึกไม่สำเร็จ") }
        }.start()
    }

    private fun exportTemp(then: (File) -> Unit) {
        val bmp = board.export()
        Thread {
            val f = ImageUtils.saveTemp(this, bmp, "edit")
            runOnUiThread { then(f) }
        }.start()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        PenAssistService.instance?.setBubbleVisible(false)
    }

    override fun onPause() {
        super.onPause()
        PenAssistService.instance?.setBubbleVisible(true)
    }

    override fun onDestroy() {
        srcFile?.delete()
        super.onDestroy()
    }
}
