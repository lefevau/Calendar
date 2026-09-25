package org.fossify.calendar.extensions

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fossify.calendar.R
import org.fossify.calendar.databinding.WeekEventMarkerBinding
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import kotlin.math.max

/** Dark tint of the event color for light backgrounds, white otherwise. */
fun Int.getEventInkColor(): Int =
    if (getContrastColor() == Color.WHITE) Color.WHITE else ColorUtils.blendARGB(this, Color.BLACK, 0.7f)

fun View.setEventMarkerBackground(color: Int) {
    background = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.event_marker_corner_radius)
        setColor(color)
    }
}

/** Fills an event card of the given size, adding time, location and description only when they fit. */
fun WeekEventMarkerBinding.bindEvent(event: Event, width: Int, height: Int, backgroundColor: Int, textColor: Int) {
    val res = root.resources
    root.setEventMarkerBackground(backgroundColor)

    weekEventTaskImage.beVisibleIf(event.isTask())
    if (event.isTask()) {
        weekEventTaskImage.applyColorFilter(textColor)
    }
    weekEventLocationIcon.applyColorFilter(textColor)
    weekEventDescriptionDivider.background = ColorDrawable(textColor.adjustAlpha(0.15f))
    arrayOf(weekEventLabel, weekEventTime, weekEventLocation, weekEventDescription).forEach { it.setTextColor(textColor) }

    weekEventLabel.apply {
        text = event.title
        checkViewStrikeThrough(event.shouldStrikeThrough())
        contentDescription = text
    }

    val padding = res.getDimensionPixelSize(R.dimen.event_marker_padding)
    val paddingH = if (width >= 8 * padding) padding * 10 / 7 else padding / 2
    val innerWidth = width - 2 * paddingH
    val rowGap = res.getDimensionPixelSize(R.dimen.event_marker_row_gap)
    val descriptionGap = 2 * res.getDimensionPixelSize(R.dimen.event_marker_description_gap) +
        res.getDimensionPixelSize(org.fossify.commons.R.dimen.one_dp)
    val titleHeight = weekEventLabel.lineHeight
    val rowHeight = rowGap + weekEventTime.lineHeight

    val context = root.context
    val timeText = "${Formatter.getTime(context, Formatter.getDateTimeFromTS(event.startTS))} – " +
        Formatter.getTime(context, Formatter.getDateTimeFromTS(event.endTS))
    val timeFits = weekEventTime.fits(timeText, innerWidth)
    var available = height - 2 * padding - titleHeight

    fun reveal(view: View, neededHeight: Int, condition: Boolean): Boolean {
        val show = condition && available >= neededHeight
        view.beVisibleIf(show)
        if (show) available -= neededHeight
        return show
    }

    val showTime = reveal(weekEventTime, rowHeight, timeFits && event.startTS != event.endTS)
    if (!showTime) {
        // title only, centered when it's a single line
        root.setPadding(paddingH, 0, paddingH, 0)
        root.gravity = Gravity.CENTER_VERTICAL
        weekEventLabel.maxLines = max(1, height / max(1, titleHeight))
        arrayOf(weekEventLocationHolder, weekEventDescriptionDivider, weekEventDescription).forEach { it.beVisibleIf(false) }
        return
    }

    root.setPadding(paddingH, padding, paddingH, padding)
    root.gravity = Gravity.TOP
    weekEventLabel.maxLines = 1

    val location = event.location.trim()
    weekEventLocation.text = location
    val showLocation = reveal(weekEventLocationHolder, rowHeight, location.isNotEmpty())
    weekEventTime.text = if (location.isNotEmpty() && !showLocation) "$timeText · $location" else timeText

    val description = event.description.trim()
    val descriptionLine = weekEventDescription.lineHeight
    val showDescription = reveal(weekEventDescription, descriptionGap + descriptionLine, description.isNotEmpty())
    weekEventDescriptionDivider.beVisibleIf(showDescription)
    if (showDescription) {
        weekEventDescription.text = description
        weekEventDescription.maxLines = 1 + available / descriptionLine
    }
}

private fun TextView.fits(text: String, width: Int) = paint.measureText(text) <= width
