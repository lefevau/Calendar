package org.fossify.calendar.fragments

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.collection.LongSparseArray
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.fossify.calendar.R
import org.fossify.calendar.activities.MainActivity
import org.fossify.calendar.databinding.FragmentDayTimelineBinding
import org.fossify.calendar.databinding.TopNavigationBinding
import org.fossify.calendar.databinding.WeekAllDayEventMarkerBinding
import org.fossify.calendar.databinding.WeekEventMarkerBinding
import org.fossify.calendar.databinding.WeekNowMarkerBinding
import org.fossify.calendar.databinding.WeeklyViewHourTextviewBinding
import org.fossify.calendar.extensions.bindEvent
import org.fossify.calendar.extensions.checkViewStrikeThrough
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.getEventInkColor
import org.fossify.calendar.extensions.getViewBitmap
import org.fossify.calendar.extensions.getWeeklyViewItemHeight
import org.fossify.calendar.extensions.printBitmap
import org.fossify.calendar.extensions.setEventMarkerBackground
import org.fossify.calendar.extensions.shouldStrikeThrough
import org.fossify.calendar.helpers.DAY_CODE
import org.fossify.calendar.helpers.DayTimelineLayout
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.EVENT_OCCURRENCE_TS
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.IS_TASK_COMPLETED
import org.fossify.calendar.helpers.NEW_EVENT_SET_HOUR_DURATION
import org.fossify.calendar.helpers.NEW_EVENT_START_TS
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.helpers.getActivityToOpen
import org.fossify.calendar.interfaces.DayPage
import org.fossify.calendar.interfaces.NavigationListener
import org.fossify.calendar.models.Event
import org.fossify.calendar.views.CollapsedHoursView
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.helpers.HIGHER_ALPHA
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.models.RadioItem
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalTime
import kotlin.math.max
import kotlin.math.roundToInt

class DayTimelineFragment : Fragment(), DayPage {
    override var mListener: NavigationListener? = null
    private var dayCode = ""
    private var lastHash = 0
    private var events = listOf<Event>()
    private var expandedGaps = HashSet<Int>()
    private var calendarColors = LongSparseArray<Int>()
    private var wasScrolled = false
    private var isPrintVersion = false
    private val minuteHandler = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            render()
            scheduleMinuteTick()
        }
    }

    private lateinit var binding: FragmentDayTimelineBinding
    private lateinit var topNavigationBinding: TopNavigationBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentDayTimelineBinding.inflate(inflater, container, false)
        topNavigationBinding = TopNavigationBinding.bind(binding.root)
        dayCode = requireArguments().getString(DAY_CODE)!!
        setupButtons()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        requireContext().eventsHelper.getCalendars(requireActivity(), false) {
            it.forEach { calendar -> calendarColors.put(calendar.id!!, calendar.color) }
            // calendar colors may have changed, so render even if the events did not
            lastHash = 0
            updateCalendar()
        }
        scheduleMinuteTick()
    }

    override fun onPause() {
        super.onPause()
        minuteHandler.removeCallbacks(minuteTick)
    }

    // keeps the now marker and the "now" busy range current
    private fun scheduleMinuteTick() {
        minuteHandler.removeCallbacks(minuteTick)
        if (dayCode == Formatter.getTodayCode()) {
            minuteHandler.postDelayed(minuteTick, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    private fun setupButtons() {
        val textColor = requireContext().getProperTextColor()
        topNavigationBinding.topLeftArrow.apply {
            applyColorFilter(textColor)
            background = null
            setOnClickListener { mListener?.goLeft() }
            contentDescription = getString(R.string.accessibility_previous_day)
        }

        topNavigationBinding.topRightArrow.apply {
            applyColorFilter(textColor)
            background = null
            setOnClickListener { mListener?.goRight() }
            contentDescription = getString(R.string.accessibility_next_day)
        }

        topNavigationBinding.topValue.apply {
            text = Formatter.getDayTitle(requireContext(), dayCode)
            contentDescription = text
            setOnClickListener { (activity as MainActivity).showGoToDateDialog() }
            setTextColor(textColor)
        }
    }

    override fun updateCalendar() {
        val startTS = Formatter.getDayStartTS(dayCode)
        val endTS = Formatter.getDayEndTS(dayCode)
        context?.eventsHelper?.getEvents(startTS, endTS) { received ->
            val newHash = received.hashCode()
            if (newHash == lastHash || !isAdded) {
                return@getEvents
            }
            lastHash = newHash
            events = received.sortedWith(compareBy<Event> { it.startTS }.thenBy { it.endTS }.thenBy { it.title })
            activity?.runOnUiThread { render() }
        }
    }

    private fun render() {
        if (!isAdded) {
            return
        }

        val content = binding.dayTimelineContent
        if (content.width == 0) {
            content.onGlobalLayout { render() }
            return
        }

        val dayStartTS = Formatter.getDayStartTS(dayCode)
        val dayEndTS = Formatter.getDayEndTS(dayCode)
        val showMidnightSpanningAtTop = requireContext().config.showMidnightSpanningEventsAtTop
        val (topEvents, timedEvents) = events.partition {
            it.getIsAllDay() || (showMidnightSpanningAtTop &&
                Formatter.getDayCodeFromTS(it.startTS) != Formatter.getDayCodeFromTS(it.endTS))
        }

        fun minuteOf(ts: Long, fallback: Int) =
            if (ts < dayStartTS || ts > dayEndTS) fallback else Formatter.getDateTimeFromTS(ts).minuteOfDay

        val hourHeight = requireContext().getWeeklyViewItemHeight()
        val minuteHeight = hourHeight / 60
        val minimalMinutes = (resources.getDimension(R.dimen.weekly_view_minimal_event_height) / minuteHeight).roundToInt()
        val ranges = timedEvents.map {
            val start = minuteOf(it.startTS, 0)
            start to max(minuteOf(it.endTS, 1440), start + minimalMinutes).coerceAtMost(1440)
        }

        val nowMinute = DateTime().minuteOfDay
        val nowRange = if (dayCode == Formatter.getTodayCode()) listOf(nowMinute to nowMinute) else emptyList()
        val layout = DayTimelineLayout(
            busyRanges = ranges + nowRange,
            hourHeight = hourHeight,
            gapHeight = resources.getDimension(R.dimen.daily_view_gap_height),
            expandedGaps = expandedGaps
        )

        content.removeAllViews()
        content.layoutParams.height = layout.totalHeight.roundToInt()
        addRows(layout)
        addTimedEvents(layout, timedEvents, ranges)
        addTopEvents(topEvents)
        addCurrentTimeIndicator(layout)

        if (!wasScrolled) {
            wasScrolled = true
            val firstMinute = ranges.minOfOrNull { it.first } ?: 0
            binding.dayTimelineScrollview.post {
                binding.dayTimelineScrollview.scrollY = (layout.minuteToY(firstMinute) - hourHeight / 2).roundToInt()
            }
        }
    }

    private fun addRows(layout: DayTimelineLayout) {
        val content = binding.dayTimelineContent
        val textColor = getTextColor()
        val dividerColor = ContextCompat.getColor(requireContext(), org.fossify.commons.R.color.divider_grey)
        val hoursWidth = resources.getDimensionPixelSize(R.dimen.daily_view_hours_width)
        val timeBase = DateTime().withDate(2000, 1, 1).withTime(0, 0, 0, 0)
        fun formatHour(hour: Int) = Formatter.getTime(requireContext(), timeBase.withHourOfDay(hour % 24))

        layout.rows.forEachIndexed { index, row ->
            val top = layout.topOf(index).roundToInt()
            val height = layout.heightOf(row).roundToInt()

            View(requireContext()).apply {
                background = ColorDrawable(dividerColor)
                content.addView(this, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = top })
            }

            if (row.isCollapsed) {
                CollapsedHoursView(
                    context = requireContext(),
                    text = "${formatHour(row.fromHour)} – ${formatHour(row.toHour)}",
                    gutterWidth = hoursWidth.toFloat(),
                    textColor = textColor
                ).apply {
                    setOnClickListener {
                        expandedGaps.add(row.fromHour)
                        render()
                    }
                    content.addView(this, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = top })
                }
            } else {
                WeeklyViewHourTextviewBinding.inflate(layoutInflater).root.apply {
                    text = formatHour(row.fromHour)
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setTextColor(textColor)
                    content.addView(this, RelativeLayout.LayoutParams(hoursWidth, height).apply { topMargin = top })
                }

                View(requireContext()).apply {
                    setOnClickListener { createNewEvent(row.fromHour) }
                    content.addView(this, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                        topMargin = top
                        leftMargin = hoursWidth
                    })
                }

                if (row.fromHour in layout.expandedStarts && !isPrintVersion) {
                    addCollapseButton(row.fromHour, top, textColor)
                }
            }
        }
    }

    private fun addCollapseButton(gapStart: Int, top: Int, textColor: Int) {
        val density = resources.displayMetrics.density
        val icon = resources.getColoredDrawableWithColor(R.drawable.ic_unfold_less_vector, textColor).apply {
            val size = (15 * density).roundToInt()
            setBounds(0, 0, size, size)
        }

        TextView(requireContext()).apply {
            text = getString(R.string.collapse)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (4 * density).roundToInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).roundToInt(), (4 * density).roundToInt(), (10 * density).roundToInt(), (4 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(textColor.adjustAlpha(0.1f))
            }
            setOnClickListener {
                expandedGaps.remove(gapStart)
                render()
            }
            binding.dayTimelineContent.addView(this, RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                topMargin = top + (6 * density).roundToInt()
                marginEnd = (8 * density).roundToInt()
            })
        }
    }

    private fun addTimedEvents(layout: DayTimelineLayout, timedEvents: List<Event>, ranges: List<Pair<Int, Int>>) {
        val content = binding.dayTimelineContent
        val hoursWidth = resources.getDimensionPixelSize(R.dimen.daily_view_hours_width)
        val columnsWidth = content.width - hoursWidth
        val density = resources.displayMetrics.density.roundToInt()
        val columns = DayTimelineLayout.assignColumns(ranges)
        val inset = 4 * density

        timedEvents.forEachIndexed { i, event ->
            val (start, end) = ranges[i]
            val (column, columnCount) = columns[i]
            val top = layout.minuteToY(start).roundToInt()
            val height = (layout.minuteToY(end) - top).roundToInt()
            val width = columnsWidth / columnCount
            val cardWidth = width - 2 * inset
            val cardHeight = height - 3 * density

            WeekEventMarkerBinding.inflate(layoutInflater).apply {
                val (backgroundColor, textColor) = getEventColors(event, MEDIUM_ALPHA)
                bindEvent(event, cardWidth, cardHeight, backgroundColor, textColor)
                root.setOnClickListener { openEvent(event) }
                content.addView(root, RelativeLayout.LayoutParams(cardWidth, cardHeight).apply {
                    topMargin = top + 2 * density
                    leftMargin = hoursWidth + column * width + inset
                })
            }
        }
    }

    private fun addTopEvents(topEvents: List<Event>) {
        val holder = binding.dayTimelineAllDayHolder
        holder.removeAllViews()
        topEvents.forEach { event ->
            WeekAllDayEventMarkerBinding.inflate(layoutInflater, holder, false).apply {
                val (backgroundColor, textColor) = getEventColors(event, LOWER_ALPHA)
                root.setEventMarkerBackground(backgroundColor)
                weekEventLabel.apply {
                    val verticalPadding = resources.getDimensionPixelSize(R.dimen.event_marker_padding)
                    val horizontalPadding = verticalPadding * 10 / 7
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    setTextColor(textColor)
                    text = event.title
                    checkViewStrikeThrough(event.shouldStrikeThrough())
                    contentDescription = text
                }
                weekEventTaskImage.beVisibleIf(event.isTask())
                if (event.isTask()) {
                    weekEventTaskImage.applyColorFilter(textColor)
                }
                (root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
                    setMargins(margin, 0, margin, margin)
                }
                root.setOnClickListener { openEvent(event) }
                holder.addView(root)
            }
        }
    }

    private fun addCurrentTimeIndicator(layout: DayTimelineLayout) {
        if (isPrintVersion || dayCode != Formatter.getTodayCode()) {
            return
        }

        val now = DateTime()
        val markerHeight = resources.getDimensionPixelSize(R.dimen.weekly_view_now_height)
        val dotSize = resources.getDimensionPixelSize(R.dimen.weekly_view_now_dot_size)
        WeekNowMarkerBinding.inflate(layoutInflater).root.apply {
            binding.dayTimelineContent.addView(this, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, markerHeight).apply {
                topMargin = (layout.minuteToY(now.minuteOfDay) - markerHeight / 2).roundToInt()
                leftMargin = resources.getDimensionPixelSize(R.dimen.daily_view_hours_width) - dotSize
            })
        }
    }

    private fun getEventColors(event: Event, dimAlpha: Float): Pair<Int, Int> {
        val config = requireContext().config
        var backgroundColor = if (event.color == 0) {
            calendarColors.get(event.calendarId, requireContext().getProperPrimaryColor())
        } else {
            event.color
        }
        var textColor = backgroundColor.getEventInkColor()

        val dim = if (event.isTask()) {
            config.dimCompletedTasks && event.isTaskCompleted()
        } else {
            config.dimPastEvents && event.isPastEvent && !isPrintVersion
        }

        if (dim) {
            backgroundColor = backgroundColor.adjustAlpha(dimAlpha)
            textColor = textColor.adjustAlpha(HIGHER_ALPHA)
        }
        return backgroundColor to textColor
    }

    private fun getTextColor() = if (isPrintVersion) {
        resources.getColor(org.fossify.commons.R.color.theme_light_text_color)
    } else {
        requireContext().getProperTextColor()
    }

    private fun openEvent(event: Event) {
        Intent(context, getActivityToOpen(event.isTask())).apply {
            putExtra(EVENT_ID, event.id)
            putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
            startActivity(this)
        }
    }

    private fun createNewEvent(hour: Int) {
        // non-strict conversion shifts hours skipped by DST forward instead of throwing
        val localMillis = Formatter.getLocalDateTimeFromCode(dayCode).toLocalDate().toDateTime(LocalTime(hour, 0), DateTimeZone.UTC).millis
        val timestamp = DateTimeZone.getDefault().convertLocalToUTC(localMillis, false) / 1000L
        if (requireContext().config.allowCreatingTasks) {
            val items = arrayListOf(
                RadioItem(TYPE_EVENT, getString(R.string.event)),
                RadioItem(TYPE_TASK, getString(R.string.task))
            )
            RadioGroupDialog(requireActivity(), items) {
                launchNewEventIntent(timestamp, it as Int == TYPE_TASK)
            }
        } else {
            launchNewEventIntent(timestamp, false)
        }
    }

    private fun launchNewEventIntent(timestamp: Long, isTask: Boolean) {
        Intent(context, getActivityToOpen(isTask)).apply {
            putExtra(NEW_EVENT_START_TS, timestamp)
            putExtra(NEW_EVENT_SET_HOUR_DURATION, true)
            startActivity(this)
        }
    }

    override fun printCurrentView() {
        isPrintVersion = true
        topNavigationBinding.apply {
            topLeftArrow.beGone()
            topRightArrow.beGone()
            topValue.setTextColor(getTextColor())
            render()

            Handler().postDelayed({
                requireContext().printBitmap(binding.dayTimelineHolder.getViewBitmap())

                Handler().postDelayed({
                    isPrintVersion = false
                    topLeftArrow.beVisible()
                    topRightArrow.beVisible()
                    topValue.setTextColor(getTextColor())
                    render()
                }, 1000)
            }, 1000)
        }
    }
}
