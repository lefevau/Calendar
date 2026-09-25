package org.fossify.calendar.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class DayTimelineLayoutTest {
    @Test
    fun collapsesEmptyStretches() {
        // 9:00-10:30 and 11:00-11:15 busy
        val layout = DayTimelineLayout(listOf(540 to 630, 660 to 675), 60f, 20f)
        assertEquals(
            listOf(
                DayTimelineLayout.Row(0, 9),
                DayTimelineLayout.Row(9, 10),
                DayTimelineLayout.Row(10, 11),
                DayTimelineLayout.Row(11, 12),
                DayTimelineLayout.Row(12, 24)
            ),
            layout.rows
        )
        assertEquals(20f + 3 * 60f + 20f, layout.totalHeight, 0.01f)
        assertEquals(20f, layout.minuteToY(540), 0.01f)
        assertEquals(20f + 90f, layout.minuteToY(630), 0.01f)
        assertEquals(layout.totalHeight, layout.minuteToY(1440), 0.01f)
    }

    @Test
    fun singleEmptyHourIsNotCollapsed() {
        val layout = DayTimelineLayout(listOf(0 to 540, 600 to 1440), 60f, 20f)
        assertEquals(24, layout.rows.size)
    }

    @Test
    fun expandedGapShowsHours() {
        val layout = DayTimelineLayout(emptyList(), 60f, 20f, expandedGaps = setOf(0))
        assertEquals(24, layout.rows.size)
    }

    @Test
    fun overlappingEventsGoSideBySide() {
        val columns = DayTimelineLayout.assignColumns(
            listOf(540 to 600, 550 to 620, 600 to 660, 700 to 720)
        )
        assertEquals(listOf(0 to 2, 1 to 2, 0 to 2, 0 to 1), columns)
    }
}
