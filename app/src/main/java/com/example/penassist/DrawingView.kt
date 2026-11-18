package com.example.penassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Pressure-sensitive canvas:
 *  - pen width follows stylus pressure
 *  - palm rejection: fingers are ignored unless fingerDraws = true
 *  - stylus eraser end / holding the stylus side button = temporary eraser (if the pen reports it)
 *  - hover cursor when the pen hovers (if the pen reports hover)
 *  - CROP tool = "Smart Select" rectangle
 */
class DrawingView(context: Context) : View(context) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER, CROP }

    var tool: Tool = Tool.PEN
        set(value) { field = value; invalidate() }
    var penColor: Int = Color.RED
    var baseWidth: Float = dp(3f)
    var fingerDraws: Boolean = false

    var backgroundImage: Bitmap? = null
        set(value) { field = value; updateBgRect(); invalidate() }
    private val bgRect = RectF()

    private class Stroke(val tool: Tool, val color: Int) {
        val pts = ArrayList<FloatArray>() // x, y, width
    }

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var activeId = -1

    var cropRect: RectF? = null
        private set
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var hoverX = -1f
    private var hoverY = -1f

    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x88000000.toInt() }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.GRAY
        strokeWidth = dp(1.5f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---------- public API ----------

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            redrawAll()
        }
    }

    fun clearAll() {
        strokes.clear()
        cropRect = null
        redrawAll()
    }

    fun clearCrop() {
        cropRect = null
        invalidate()
    }

    /** Render background + ink. Crops to the selection (if any) or to the screenshot area. */
    fun export(applyCrop: Boolean = true): Bitmap {
        val w = max(width, 1)
        val h = max(height, 1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawBase(c)
        layer?.let { c.drawBitmap(it, 0f, 0f, null) }

        val area = Rect()
        val r = cropRect
        when {
            applyCrop && r != null -> r.roundOut(area)
            backgroundImage != null -> bgRect.roundOut(area)
            else -> return out
        }
        if (!area.intersect(0, 0, w, h) || area.width() < 2 || area.height() < 2) return out
        return Bitmap.createBitmap(out, area.left, area.top, area.width(), area.height())
    }

    // ---------- layout / drawing ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val l = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        layer = l
        layerCanvas = Canvas(l)
        updateBgRect()
        redrawAll()
    }

    private fun updateBgRect() {
        val bg = backgroundImage ?: return
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / bg.width, height.toFloat() / bg.height)
        val dw = bg.width * s
        val dh = bg.height * s
        bgRect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun drawBase(c: Canvas) {
        val bg = backgroundImage
        if (bg != null) {
            c.drawColor(Color.BLACK)
            c.drawBitmap(bg, null, bgRect, bmpPaint)
        } else {
            c.drawColor(Color.WHITE)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBase(canvas)
        layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        current?.let {
            if (it.tool == Tool.HIGHLIGHTER) {
                configPaint(it)
                canvas.drawPath(pathOf(it), strokePaint)
            }
        }
        cropRect?.let { r ->
            canvas.save()
            canvas.clipOutRect(r)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.restore()
            canvas.drawRect(r, cropPaint)
        }
        if (hoverX >= 0f && tool != Tool.CROP) {
            canvas.drawCircle(hoverX, hoverY, baseWidth / 2f + dp(3f), hoverPaint)
        }
    }

    private fun configPaint(s: Stroke) {
        strokePaint.xfermode = null
        when (s.tool) {
            Tool.HIGHLIGHTER -> {
                strokePaint.color = (s.color and 0x00FFFFFF) or 0x66000000
                strokePaint.strokeWidth = s.pts[0][2]
            }
            Tool.ERASER -> {
                strokePaint.color = Color.BLACK
                strokePaint.xfermode = clearMode
            }
            else -> strokePaint.color = s.color
        }
    }

    private fun drawSegment(c: Canvas, a: FloatArray, b: FloatArray) {
        strokePaint.strokeWidth = (a[2] + b[2]) / 2f
        c.drawLine(a[0], a[1], b[0], b[1], strokePaint)
    }

    private fun pathOf(s: Stroke): Path {
        val p = Path()
        s.pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt[0], pt[1]) else p.lineTo(pt[0], pt[1]) }
        if (s.pts.size == 1) p.lineTo(s.pts[0][0] + 0.1f, s.pts[0][1])
        return p
    }

    private fun renderStroke(c: Canvas, s: Stroke) {
        configPaint(s)
        when {
            s.tool == Tool.HIGHLIGHTER -> c.drawPath(pathOf(s), strokePaint)
            s.pts.size == 1 -> {
                val p = s.pts[0]
                strokePaint.strokeWidth = p[2]
                c.drawPoint(p[0], p[1], strokePaint)
            }
            else -> for (i in 1 until s.pts.size) drawSegment(c, s.pts[i - 1], s.pts[i])
        }
    }

    private fun redrawAll() {
        val l = layer ?: return
        l.eraseColor(Color.TRANSPARENT)
        layerCanvas?.let { c -> strokes.forEach { renderStroke(c, it) } }
        invalidate()
    }

    // ---------- input ----------

    private fun isStylus(type: Int) =
        type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

    private fun accepts(type: Int) = isStylus(type) || fingerDraws || tool == Tool.CROP

    private fun toolFor(e: MotionEvent, idx: Int): Tool {
        if (tool == Tool.CROP) return Tool.CROP
        val sideButton = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER || sideButton) return Tool.ERASER
        return tool
    }

    private fun widthFor(t: Tool, e: MotionEvent, idx: Int, pressure: Float): Float {
        val stylus = isStylus(e.getToolType(idx))
        return when (t) {
            Tool.PEN -> if (stylus) baseWidth * (0.3f + 1.4f * pressure.coerceIn(0f, 1f)) else baseWidth
            Tool.HIGHLIGHTER -> baseWidth * 5f
            Tool.ERASER -> dp(24f) + baseWidth * 2f
            Tool.CROP -> 0f
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                if (activeId == -1 && accepts(e.getToolType(idx))) {
                    activeId = e.getPointerId(idx)
                    begin(e, idx)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activeId)
                if (idx >= 0) move(e, idx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (activeId != -1 && e.getPointerId(e.actionIndex) == activeId) {
                    move(e, e.actionIndex)
                    finishStroke()
                    activeId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                activeId = -1
                redrawAll()
            }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = e.x; hoverY = e.y; invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverX = -1f; invalidate()
            }
        }
        return true
    }

    private fun begin(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        val t = toolFor(e, idx)
        if (t == Tool.CROP) {
            cropStartX = x; cropStartY = y
            cropRect = RectF(x, y, x, y)
            invalidate()
            return
        }
        val s = Stroke(t, penColor)
        s.pts.add(floatArrayOf(x, y, widthFor(t, e, idx, e.getPressure(idx))))
        current = s
        invalidate()
    }

    private fun move(e: MotionEvent, idx: Int) {
        val s = current
        if (s == null) {
            if (tool == Tool.CROP && cropRect != null) {
                val x = e.getX(idx)
                val y = e.getY(idx)
                cropRect = RectF(min(cropStartX, x), min(cropStartY, y), max(cropStartX, x), max(cropStartY, y))
                invalidate()
            }
            return
        }
        for (h in 0 until e.historySize) {
            addPoint(s, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                widthFor(s.tool, e, idx, e.getHistoricalPressure(idx, h)))
        }
        addPoint(s, e.getX(idx), e.getY(idx), widthFor(s.tool, e, idx, e.getPressure(idx)))
        invalidate()
    }

    private fun addPoint(s: Stroke, x: Float, y: Float, w: Float) {
        val prev = s.pts.last()
        if (prev[0] == x && prev[1] == y) return
        val pt = floatArrayOf(x, y, w)
        s.pts.add(pt)
        if (s.tool != Tool.HIGHLIGHTER) {
            layerCanvas?.let {
                configPaint(s)
                drawSegment(it, prev, pt)
            }
        }
    }

    private fun finishStroke() {
        val s = current
        if (s == null) {
            val r = cropRect
            if (r != null && (r.width() < dp(12f) || r.height() < dp(12f))) cropRect = null
            invalidate()
            return
        }
        if (s.tool == Tool.HIGHLIGHTER || s.pts.size == 1) layerCanvas?.let { renderStroke(it, s) }
        strokes.add(s)
        current = null
        invalidate()
    }
}
