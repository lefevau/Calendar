package org.fossify.calendar.views

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LongSparseArray
import android.util.SparseIntArray
import android.view.View
import org.fossify.calendar.R
import org.fossify.calendar.extensions.*
import org.fossify.calendar.helpers.COLUMN_COUNT
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.ROW_COUNT
import org.fossify.calendar.models.DayMonthly
import org.fossify.calendar.models.Event
import org.fossify.calendar.models.MonthViewEvent
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.HIGHER_ALPHA
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.joda.time.DateTime
import org.joda.time.Days
import kotlin.math.max
import kotlin.math.min

// used in the Monthly view fragment, 1 view per screen
class MonthView(context: Context, attrs: AttributeSet, defStyle: Int) : View(context, attrs, defStyle) {
    companion object {
        private const val EVENT_DOT_COLUMN_COUNT = 3
        private const val EVENT_DOT_ROW_COUNT = 1
    }

    private var textPaint: Paint
    private var eventTitlePaint: TextPaint
    private var gridPaint: Paint
    private var circleStrokePaint: Paint
    private var plusTextPaint: Paint
    private var eventDotPaint: Paint
    private var morePaint: Paint
    private var config = context.config
    private var dayWidth = 0f
    private var dayHeight = 0f
    private var primaryColor = 0
    private var textColor = 0
    private var weekendsTextColor = 0
    private var weekDaysLetterHeight = 0
    private var eventTitleHeight = 0
    private var currDayOfWeek = 0
    private var smallPadding = 0
    private var chipHeight = 0
    private var chipGap = 0
    private var chipRadius = 0f
    private var chipPadding = 0f
    private var chipTextBaseline = 0f
    private var horizontalOffset = 0
    private var showWeekNumbers = false
    private var dimPastEvents = true
    private var dimCompletedTasks = true
    private var highlightWeekends = false
    private var isPrintVersion = false
    private var isMonthDayView = false
    private var allEvents = ArrayList<MonthViewEvent>()
    private var bgRectF = RectF()
    private var dayTextRect = Rect()
    private var dayLetters = ArrayList<String>()
    private var days = ArrayList<DayMonthly>()
    private var dayVerticalOffsets = SparseIntArray()
    private var remainingEvents = IntArray(ROW_COUNT * COLUMN_COUNT)
    private var hiddenEvents = IntArray(ROW_COUNT * COLUMN_COUNT)
    private var selectedDayCoords = Point(-1, -1)
    private var fadeShaders = LongSparseArray<Shader>()

    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    init {
        primaryColor = context.getProperPrimaryColor()
        textColor = context.getProperTextColor()
        weekendsTextColor = config.highlightWeekendsColor
        showWeekNumbers = config.showWeekNumbers
        dimPastEvents = config.dimPastEvents
        dimCompletedTasks = config.dimCompletedTasks
        highlightWeekends = config.highlightWeekends

        smallPadding = resources.displayMetrics.density.toInt()
        val normalTextSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_text_size)
        weekDaysLetterHeight = normalTextSize * 2

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = normalTextSize.toFloat()
            textAlign = Paint.Align.CENTER
            typeface = FontHelper.getTypeface(context)
        }

        eventDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        plusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            alpha = 175
            textSize = normalTextSize.toFloat()
            textAlign = Paint.Align.CENTER
            typeface = FontHelper.getTypeface(context)
        }

        gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.adjustAlpha(LOWER_ALPHA)
        }

        circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.circle_stroke_width)
            color = primaryColor
        }

        val density = resources.displayMetrics.density
        val chipTextSize = resources.getDimension(R.dimen.month_chip_text_size)
        eventTitleHeight = chipTextSize.toInt()
        eventTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = chipTextSize
            textAlign = Paint.Align.LEFT
            typeface = FontHelper.getTypeface(context)
        }

        morePaint = Paint(eventTitlePaint).apply {
            color = textColor.adjustAlpha(0.6f)
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(typeface, 500, false) else typeface
        }

        chipHeight = max((15 * density).toInt(), (chipTextSize * 1.5f).toInt())
        chipGap = (2 * density).toInt()
        chipRadius = 4 * density
        chipPadding = 4 * density
        val fm = eventTitlePaint.fontMetrics
        chipTextBaseline = chipHeight / 2f - (fm.ascent + fm.descent) / 2

        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
    }

    fun updateDays(newDays: ArrayList<DayMonthly>, isMonthDayView: Boolean) {
        this.isMonthDayView = isMonthDayView
        days = newDays
        showWeekNumbers = config.showWeekNumbers
        horizontalOffset = context.getWeekNumberWidth()
        initWeekDayLetters()
        setupCurrentDayOfWeekIndex()
        groupAllEvents()
        invalidate()
    }

    private fun groupAllEvents() {
        days.forEach { day ->
            val dayIndexOnMonthView = day.indexOnMonthView

            day.dayEvents.forEach { event ->
                // make sure we properly handle events lasting multiple days and repeating ones
                val validDayEvent = isDayValid(event, day.code)
                val lastEvent = allEvents.lastOrNull { it.id == event.id }
                val notYetAddedOrIsRepeatingEvent = lastEvent == null || lastEvent.endTS <= event.startTS

                // handle overlapping repeating events e.g. an event that lasts 3 days, but repeats every 2 days has a one day overlap
                val canOverlap = event.endTS - event.startTS > event.repeatInterval
                val shouldAddEvent = notYetAddedOrIsRepeatingEvent || canOverlap && (lastEvent.startTS < event.startTS)

                if (shouldAddEvent && !validDayEvent) {
                    val daysCnt = getEventLastingDaysCount(event)

                    val monthViewEvent = MonthViewEvent(
                        id = event.id!!,
                        title = event.title,
                        startTS = event.startTS,
                        endTS = event.endTS,
                        color = event.color,
                        startDayIndex = dayIndexOnMonthView,
                        daysCnt = daysCnt,
                        originalStartDayIndex = dayIndexOnMonthView,
                        isAllDay = event.getIsAllDay(),
                        isPastEvent = event.isPastEvent,
                        isTask = event.isTask(),
                        isTaskCompleted = event.isTaskCompleted(),
                        isAttendeeInviteDeclined = event.isAttendeeInviteDeclined(),
                        isEventCanceled = event.isEventCanceled()
                    )
                    allEvents.add(monthViewEvent)
                }
            }
        }

        allEvents = allEvents.asSequence().sortedWith(
            compareBy({ -it.daysCnt }, { !it.isAllDay }, { it.startTS }, { it.endTS }, { it.startDayIndex }, { it.title })
        ).toMutableList() as ArrayList<MonthViewEvent>
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dayVerticalOffsets.clear()
        measureDaySize(canvas)

        if (config.showGrid && !isMonthDayView) {
            drawGrid(canvas)
        }

        addWeekDayLetters(canvas)
        if (showWeekNumbers && days.isNotEmpty()) {
            addWeekNumbers(canvas)
        }

        var curId = 0
        for (y in 0 until ROW_COUNT) {
            for (x in 0 until COLUMN_COUNT) {
                val day = days.getOrNull(curId)
                if (day != null) {
                    val dayNumber = day.value.toString()
                    val textPaint = getTextPaint(day)
                    textPaint.getTextBounds(dayNumber, 0, dayNumber.length, dayTextRect)
                    dayVerticalOffsets.put(day.indexOnMonthView, dayVerticalOffsets[day.indexOnMonthView] + weekDaysLetterHeight)
                    val verticalOffset = dayVerticalOffsets[day.indexOnMonthView]
                    val xPos = x * dayWidth + horizontalOffset
                    val yPos = y * dayHeight + verticalOffset
                    val textY = yPos + textPaint.textSize
                    val xPosCenter = xPos + dayWidth / 2

                    val isDaySelected = selectedDayCoords.x != -1 && x == selectedDayCoords.x && y == selectedDayCoords.y
                    if (isDaySelected) {
                        canvas.drawCircle(
                            xPosCenter,
                            textY - dayTextRect.height() / 2,
                            textPaint.textSize * 0.8f,
                            circleStrokePaint
                        )
                        if (day.isToday) {
                            textPaint.color = textColor
                        }
                    } else if (day.isToday && !isPrintVersion) {
                        canvas.drawCircle(
                            xPosCenter,
                            textY - dayTextRect.height() / 2,
                            textPaint.textSize * 0.8f,
                            getCirclePaint(day)
                        )
                    }

                    // mark days with a dot for each event
                    if (isMonthDayView && !isDaySelected && !day.isToday && day.dayEvents.isNotEmpty()) {
                        val height = dayTextRect.height() * 1.25f
                        val eventCount = day.dayEvents.size
                        val dotRadius = textPaint.textSize * 0.2f
                        val stepSize = dotRadius * 2.5f
                        val columnCount = EVENT_DOT_COLUMN_COUNT

                        val dayEventsSorted = day.dayEvents
                            .asSequence()
                            .sortedWith(
                                comparator = compareBy({ it.startTS }, { it.endTS }, { it.title })
                            )
                            .distinctBy { it.color }

                        var xDot: Float
                        var yDot = yPos + height + textPaint.textSize / 2
                        var indexInRow: Int

                        val dotCount = dayEventsSorted.count()
                        for ((index, event) in dayEventsSorted.withIndex()) {
                            indexInRow = index % columnCount
                            xDot = xPosCenter + stepSize * (indexInRow - (min(dotCount, columnCount)) / 2)
                            if (dotCount % 2 == 0) { // center even number of dots
                                xDot += stepSize / 2
                            }

                            if (index > 0 && indexInRow == 0) { // next row of dots
                                yDot += stepSize
                            }

                            // Always show a + sign if the event count exceeds columnCount.
                            if (eventCount - 1 != index && index >= columnCount * EVENT_DOT_ROW_COUNT - 1) {
                                plusTextPaint.textSize = stepSize * 1.5f
                                canvas.drawText("+", xDot, yDot + dotRadius * 1.2f, plusTextPaint)
                                break
                            } else {
                                val paint = eventDotPaint.apply { color = event.color }
                                canvas.drawCircle(xDot, yDot, dotRadius, paint)
                            }
                        }
                    }

                    canvas.drawText(dayNumber, xPosCenter, textY, textPaint)
                    dayVerticalOffsets.put(day.indexOnMonthView, (verticalOffset + textPaint.textSize * 1.5f).toInt())
                }
                curId++
            }
        }

        if (!isMonthDayView) {
            remainingEvents.fill(0)
            hiddenEvents.fill(0)
            allEvents.forEach { event ->
                for (i in event.startDayIndex until min(event.startDayIndex + event.daysCnt, remainingEvents.size)) {
                    remainingEvents[i]++
                }
            }

            for (event in allEvents) {
                drawEvent(event, canvas)
            }
            drawMoreLabels(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // vertical lines
        for (i in 0 until COLUMN_COUNT) {
            var lineX = i * dayWidth
            if (showWeekNumbers) {
                lineX += horizontalOffset
            }
            canvas.drawLine(lineX, 0f, lineX, canvas.height.toFloat(), gridPaint)
        }

        // horizontal lines
        canvas.drawLine(0f, 0f, canvas.width.toFloat(), 0f, gridPaint)
        for (i in 0 until ROW_COUNT) {
            canvas.drawLine(0f, i * dayHeight + weekDaysLetterHeight, canvas.width.toFloat(), i * dayHeight + weekDaysLetterHeight, gridPaint)
        }
        canvas.drawLine(0f, canvas.height.toFloat(), canvas.width.toFloat(), canvas.height.toFloat(), gridPaint)
    }

    private fun addWeekDayLetters(canvas: Canvas) {
        for (i in 0 until COLUMN_COUNT) {
            val xPos = horizontalOffset + (i + 1) * dayWidth - dayWidth / 2
            var weekDayLetterPaint = textPaint
            if (i == currDayOfWeek && !isPrintVersion) {
                weekDayLetterPaint = getColoredPaint(primaryColor)
            } else if (highlightWeekends && context.isWeekendIndex(i)) {
                weekDayLetterPaint = getColoredPaint(weekendsTextColor)
            }
            canvas.drawText(dayLetters[i], xPos, weekDaysLetterHeight * 0.7f, weekDayLetterPaint)
        }
    }

    private fun addWeekNumbers(canvas: Canvas) {
        val weekNumberPaint = Paint(textPaint)

        for (i in 0 until ROW_COUNT) {
            val weekDays = days.subList(i * 7, i * 7 + 7)
            weekNumberPaint.color = if (weekDays.any { it.isToday && !isPrintVersion }) primaryColor else textColor

            // fourth day of the week determines the week of the year number
            val weekOfYear = days.getOrNull(i * 7 + 3)?.weekOfYear ?: 1
            val id = "$weekOfYear:"
            val horizontalMarginFactor = 0.5f
            val xPos = horizontalOffset * horizontalMarginFactor
            val yPos = i * dayHeight + weekDaysLetterHeight
            canvas.drawText(id, xPos, yPos + textPaint.textSize, weekNumberPaint)
        }
    }

    private fun measureDaySize(canvas: Canvas) {
        val newDayWidth = (canvas.width - horizontalOffset) / 7f
        if (newDayWidth != dayWidth) {
            fadeShaders.clear()
        }
        dayWidth = newDayWidth
        dayHeight = (canvas.height - weekDaysLetterHeight) / ROW_COUNT.toFloat()
    }

    private fun drawEvent(event: MonthViewEvent, canvas: Canvas) {
        val span = min(event.daysCnt, 7 - event.startDayIndex % 7)
        val top = (event.startDayIndex until event.startDayIndex + span).maxOf { dayVerticalOffsets[it] }
        val rowBottom = weekDaysLetterHeight + dayHeight
        val isLastLane = top + chipHeight * 2 + chipGap > rowBottom
        val fits = top + chipHeight <= rowBottom

        // per day: once a day hides an event, later ones stay hidden so its last lane is kept for "+N more"
        var hiddenMask = 0
        for (i in 0 until span) {
            val day = event.startDayIndex + i
            if (!fits || hiddenEvents[day] > 0 || isLastLane && remainingEvents[day] > 1) {
                hiddenMask = hiddenMask or (1 shl i)
                hiddenEvents[day]++
            }
            remainingEvents[day]--
        }

        // draw each run of visible days as its own chip
        var runStart = -1
        for (i in 0..span) {
            val isVisible = i < span && hiddenMask and (1 shl i) == 0
            if (isVisible && runStart == -1) {
                runStart = i
            } else if (!isVisible && runStart != -1) {
                drawEventChip(event, canvas, event.startDayIndex + runStart, i - runStart, top)
                runStart = -1
            }
        }

        val newStartDayIndex = (event.startDayIndex / 7 + 1) * 7
        if (event.daysCnt > span && newStartDayIndex < ROW_COUNT * COLUMN_COUNT) {
            drawEvent(event.copy(startDayIndex = newStartDayIndex, daysCnt = event.daysCnt - span), canvas)
        }
    }

    private fun drawEventChip(event: MonthViewEvent, canvas: Canvas, startDayIndex: Int, span: Int, top: Int) {
        val xPos = startDayIndex % 7 * dayWidth + horizontalOffset
        val yPos = (startDayIndex / 7) * dayHeight
        val inset = chipGap / 2f
        bgRectF.set(xPos + inset, yPos + top, xPos + dayWidth * span - inset, yPos + top + chipHeight)
        canvas.drawRoundRect(bgRectF, chipRadius, chipRadius, getEventBackgroundColor(event))

        val titlePaint = getEventTitlePaint(event)
        var textLeft = bgRectF.left + chipPadding
        if (event.isTask) {
            val iconSize = eventTitleHeight
            val iconTop = (bgRectF.centerY() - iconSize / 2f).toInt()
            val taskIcon = resources.getColoredDrawableWithColor(R.drawable.ic_task_vector, titlePaint.color).mutate()
            taskIcon.setBounds(textLeft.toInt(), iconTop, textLeft.toInt() + iconSize, iconTop + iconSize)
            taskIcon.draw(canvas)
            textLeft += iconSize + smallPadding * 2
        }

        drawEventTitle(event.title, canvas, textLeft, bgRectF.right - chipPadding, yPos + top + chipTextBaseline, titlePaint)
        for (day in startDayIndex until startDayIndex + span) {
            dayVerticalOffsets.put(day, top + chipHeight + chipGap)
        }
    }

    // fade out overflowing titles instead of ellipsizing
    private fun drawEventTitle(title: String, canvas: Canvas, left: Float, right: Float, baseline: Float, paint: Paint) {
        val width = right - left
        if (width <= 0) return

        if (paint.measureText(title) > width) {
            val opaque = paint.color or Color.BLACK
            val key = (width.toLong() shl 32) or (opaque.toLong() and 0xFFFFFFFFL)
            paint.shader = fadeShaders[key] ?: LinearGradient(width * 0.75f, 0f, width, 0f, opaque, opaque and 0x00FFFFFF, Shader.TileMode.CLAMP)
                .also { fadeShaders.put(key, it) }
        }

        canvas.save()
        canvas.translate(left, 0f)
        canvas.clipRect(0f, bgRectF.top, width, bgRectF.bottom)
        canvas.drawText(title, 0f, baseline, paint)
        canvas.restore()
    }

    private fun drawMoreLabels(canvas: Canvas) {
        hiddenEvents.forEachIndexed { index, hidden ->
            val top = dayVerticalOffsets[index]
            if (hidden == 0 || top + chipHeight > weekDaysLetterHeight + dayHeight) return@forEachIndexed

            val cellLeft = index % 7 * dayWidth + horizontalOffset
            val cellTop = (index / 7) * dayHeight + top
            val maxWidth = dayWidth - chipGap - chipPadding * 2
            var label = resources.getString(R.string.plus_x_more, hidden)
            if (morePaint.measureText(label) > maxWidth) {
                label = "+$hidden"
            }

            canvas.save()
            canvas.clipRect(cellLeft, cellTop, cellLeft + dayWidth, cellTop + chipHeight)
            canvas.drawText(label, cellLeft + chipGap / 2f + chipPadding, cellTop + chipTextBaseline, morePaint)
            canvas.restore()
        }
    }

    private fun getTextPaint(startDay: DayMonthly): Paint {
        var paintColor = when {
            !isPrintVersion && startDay.isToday -> primaryColor.getContrastColor()
            highlightWeekends && startDay.isWeekend -> weekendsTextColor
            else -> textColor
        }

        if (!startDay.isThisMonth) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }

        return getColoredPaint(paintColor)
    }

    private fun getColoredPaint(color: Int): Paint {
        val curPaint = Paint(textPaint)
        curPaint.color = color
        return curPaint
    }

    private fun getEventBackgroundColor(event: MonthViewEvent): Paint {
        var paintColor = event.color

        val adjustAlpha = when {
            event.isTask -> dimCompletedTasks && event.isTaskCompleted
            else -> dimPastEvents && event.isPastEvent && !isPrintVersion
        }

        if (adjustAlpha) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }

        return getColoredPaint(paintColor)
    }

    private fun getEventTitlePaint(event: MonthViewEvent): Paint {
        var paintColor = event.color.getEventInkColor()
        val adjustAlpha = when {
            event.isTask -> dimCompletedTasks && event.isTaskCompleted
            else -> dimPastEvents && event.isPastEvent && !isPrintVersion
        }

        if (adjustAlpha) {
            paintColor = paintColor.adjustAlpha(HIGHER_ALPHA)
        }

        val curPaint = Paint(eventTitlePaint)
        curPaint.color = paintColor
        curPaint.isStrikeThruText = event.shouldStrikeThrough()
        return curPaint
    }

    private fun getCirclePaint(day: DayMonthly): Paint {
        val curPaint = Paint(textPaint)
        var paintColor = primaryColor
        if (!day.isThisMonth) {
            paintColor = paintColor.adjustAlpha(MEDIUM_ALPHA)
        }
        curPaint.color = paintColor
        return curPaint
    }

    private fun initWeekDayLetters() {
        dayLetters = context.withFirstDayOfWeekToFront(
            context.resources.getStringArray(org.fossify.commons.R.array.week_days_short).toList()
        )
    }

    private fun setupCurrentDayOfWeekIndex() {
        if (days.firstOrNull { it.isToday && it.isThisMonth } == null) {
            currDayOfWeek = -1
            return
        }

        currDayOfWeek = context.getProperDayIndexInWeek(DateTime())
    }

    // take into account cases when an event starts on the previous screen, subtract those days
    private fun getEventLastingDaysCount(event: Event): Int {
        val startDateTime = Formatter.getDateTimeFromTS(event.startTS)
        val endDateTime = Formatter.getDateTimeFromTS(event.endTS)
        val code = days.first().code
        val screenStartDateTime = Formatter.getDateTimeFromCode(code).toLocalDate()
        var eventStartDateTime = Formatter.getDateTimeFromTS(startDateTime.seconds()).toLocalDate()
        val eventEndDateTime = Formatter.getDateTimeFromTS(endDateTime.seconds()).toLocalDate()
        val diff = Days.daysBetween(screenStartDateTime, eventStartDateTime).days
        if (diff < 0) {
            eventStartDateTime = screenStartDateTime
        }

        val isMidnight = Formatter.getDateTimeFromTS(endDateTime.seconds()) == Formatter.getDateTimeFromTS(endDateTime.seconds()).withTimeAtStartOfDay()
        val numDays = Days.daysBetween(eventStartDateTime, eventEndDateTime).days
        val daysCnt = if (numDays == 1 && isMidnight) 0 else numDays
        return daysCnt + 1
    }

    private fun isDayValid(event: Event, code: String): Boolean {
        val date = Formatter.getDateTimeFromCode(code)
        return event.startTS != event.endTS && Formatter.getDateTimeFromTS(event.endTS) == Formatter.getDateTimeFromTS(date.seconds()).withTimeAtStartOfDay()
    }

    fun togglePrintMode() {
        isPrintVersion = !isPrintVersion
        textColor = if (isPrintVersion) {
            resources.getColor(org.fossify.commons.R.color.theme_light_text_color, null)
        } else {
            context.getProperTextColor()
        }

        textPaint.color = textColor
        gridPaint.color = textColor.adjustAlpha(LOWER_ALPHA)
        invalidate()
        initWeekDayLetters()
    }

    fun updateCurrentlySelectedDay(x: Int, y: Int) {
        selectedDayCoords = Point(x, y)
        invalidate()
    }
}
