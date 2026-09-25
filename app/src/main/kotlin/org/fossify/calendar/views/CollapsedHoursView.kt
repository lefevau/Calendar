package org.fossify.calendar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.helpers.MEDIUM_ALPHA
import kotlin.math.sqrt

/** Hatched strip marking a stretch of collapsed hours in the daily view. */
class CollapsedHoursView(
    context: Context,
    private val text: String,
    private val gutterWidth: Float,
    textColor: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private val hatchSpacing = 7 * density * sqrt(2f)
    private val dotRadius = 1.5f * density
    private val dotSpacing = 6 * density
    private val textStart = 12 * density

    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor.adjustAlpha(0.06f)
        strokeWidth = density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor.adjustAlpha(0.3f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor.adjustAlpha(MEDIUM_ALPHA)
        textSize = resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
    }

    init {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        contentDescription = text
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        canvas.clipRect(gutterWidth, 0f, w, h)
        var x = gutterWidth - h
        while (x < w) {
            canvas.drawLine(x, h, x + h, 0f, hatchPaint)
            x += hatchSpacing
        }
        canvas.restore()

        for (i in -1..1) {
            canvas.drawCircle(gutterWidth / 2, h / 2 + i * dotSpacing, dotRadius, dotPaint)
        }

        val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, gutterWidth + textStart, baseline, textPaint)
    }
}
