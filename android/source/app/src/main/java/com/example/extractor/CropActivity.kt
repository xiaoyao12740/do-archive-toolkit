package com.example.extractor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.Toast
import java.io.File
import kotlin.math.min

/** 图标裁剪页：选图后在此拖动/缩放调整，白框正方形区域确认裁剪。 */
class CropActivity : Activity() {
    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_OUT = "out_path"
        const val RESULT_PATH = "path"
        const val OUT_SIZE = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        val uriStr = intent.getStringExtra(EXTRA_URI) ?: ""
        val bmp = try {
            if (uriStr.startsWith("file://") || !uriStr.contains("://")) {
                BitmapFactory.decodeFile(uriStr.removePrefix("file://"))
            } else {
                contentResolver.openInputStream(Uri.parse(uriStr))?.let { BitmapFactory.decodeStream(it) }
            }
        } catch (_: Exception) { null }
        if (bmp == null) { Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show(); finish(); return }

        val cropView = findViewById<CropView>(R.id.cropView)
        cropView.setBitmap(bmp)

        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCropOk).setOnClickListener {
            val outPath = intent.getStringExtra(EXTRA_OUT) ?: ""
            val crop = cropView.cropSquare() ?: run { Toast.makeText(this, "裁剪失败，请调整位置", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val scaled = Bitmap.createScaledBitmap(crop, OUT_SIZE, OUT_SIZE, true)
            try {
                File(outPath).outputStream().use { os -> scaled.compress(Bitmap.CompressFormat.PNG, 100, os) }
                setResult(RESULT_OK, Intent().putExtra(RESULT_PATH, outPath))
            } catch (e: Exception) {
                android.util.Log.e("CropActivity", "保存失败", e)
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
            scaled.recycle()
            crop.recycle()
            finish()
        }
    }
}

/** 可拖动/缩放的自定义裁剪视图。 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bmp: Bitmap? = null
    private val matrix = Matrix()
    private var prevX = 0f
    private var prevY = 0f
    private val detector: ScaleGestureDetector

    init {
        detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                matrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                invalidate()
                return true
            }
        })
    }

    fun setBitmap(b: Bitmap) {
        bmp = b
        post {
            val vw = width.toFloat(); val vh = height.toFloat()
            val s = min(vw / b.width, vh / b.height)
            matrix.setScale(s, s)
            matrix.postTranslate((vw - b.width * s) / 2f, (vh - b.height * s) / 2f)
            invalidate()
        }
    }

    /** 屏幕中心正方形裁剪框。 */
    private fun cropRect(): RectF {
        val s = min(width.toFloat(), height.toFloat()) * 0.8f
        val l = (width - s) / 2f; val t = (height - s) / 2f
        return RectF(l, t, l + s, t + s)
    }

    /** 把白框区域映射到原图坐标并裁出。 */
    fun cropSquare(): Bitmap? {
        val b = bmp ?: return null
        val inv = Matrix()
        if (!matrix.invert(inv)) return null
        val box = cropRect()
        val pts = floatArrayOf(box.left, box.top, box.right, box.bottom)
        inv.mapPoints(pts)
        var x1 = pts[0]; var y1 = pts[1]; var x2 = pts[2]; var y2 = pts[3]
        if (x1 > x2) { val t = x1; x1 = x2; x2 = t }
        if (y1 > y2) { val t = y1; y1 = y2; y2 = t }
        x1 = maxOf(0f, x1); y1 = maxOf(0f, y1)
        x2 = minOf(b.width.toFloat(), x2); y2 = minOf(b.height.toFloat(), y2)
        if (x2 - x1 < 1f || y2 - y1 < 1f) return null
        return Bitmap.createBitmap(b, x1.toInt(), y1.toInt(), (x2 - x1).toInt(), (y2 - y1).toInt())
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawColor(android.graphics.Color.BLACK)
        c.save()
        c.concat(matrix)
        c.drawBitmap(b, 0f, 0f, null)
        c.restore()
        val box = cropRect()
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0x99000000L).toInt() }
        c.drawRect(0f, 0f, width.toFloat(), box.top, mask)
        c.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), mask)
        c.drawRect(0f, box.top, box.left, box.bottom, mask)
        c.drawRect(box.right, box.top, width.toFloat(), box.bottom, mask)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        c.drawRect(box, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { prevX = e.x; prevY = e.y; return true }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1) {
                    matrix.postTranslate(e.x - prevX, e.y - prevY)
                    prevX = e.x; prevY = e.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                prevX = e.x; prevY = e.y
                return true
            }
        }
        return true
    }
}
