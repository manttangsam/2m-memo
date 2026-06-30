package com.example.infinitenote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class HandwritingInputView(context: Context) : View(context) {
    private val strokes = mutableListOf<MutableList<InkPoint>>()
    private var activeStroke: MutableList<InkPoint>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF132238.toInt()
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt()
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFFFFFFF.toInt())
        val midY = height * 0.62f
        canvas.drawLine(24f, midY, width - 24f, midY, guidePaint)
        strokes.forEach { drawStroke(canvas, it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = InkPoint(
            (event.x / max(1, width)).coerceIn(0f, 1f),
            (event.y / max(1, height)).coerceIn(0f, 1f)
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeStroke = mutableListOf(point).also { strokes.add(it) }
            }
            MotionEvent.ACTION_MOVE -> activeStroke?.add(point)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeStroke = null
        }
        invalidate()
        return true
    }

    fun normalizedStrokes(): List<List<InkPoint>> = strokes.map { it.toList() }

    fun clear() {
        strokes.clear()
        activeStroke = null
        invalidate()
    }

    private fun drawStroke(canvas: Canvas, stroke: List<InkPoint>) {
        if (stroke.size < 2) return
        val path = Path()
        stroke.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
