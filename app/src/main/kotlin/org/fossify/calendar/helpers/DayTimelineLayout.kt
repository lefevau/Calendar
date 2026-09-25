package org.fossify.calendar.helpers

import kotlin.math.max

/**
 * Vertical geometry of the daily timeline: busy hours get full height,
 * runs of at least [minGapHours] empty hours collapse into a single short row.
 */
class DayTimelineLayout(
    busyRanges: List<Pair<Int, Int>>,
    private val hourHeight: Float,
    private val gapHeight: Float,
    expandedGaps: Set<Int> = emptySet(),
    minGapHours: Int = 2
) {
    data class Row(val fromHour: Int, val toHour: Int) {
        val isCollapsed get() = toHour - fromHour > 1
    }

    val rows = ArrayList<Row>()
    val expandedStarts = HashSet<Int>()
    private val tops = ArrayList<Float>()
    val totalHeight: Float

    init {
        val busy = BooleanArray(24)
        for ((start, end) in busyRanges) {
            val lastHour = if (end > start) (end - 1) / 60 else start / 60
            for (hour in (start / 60).coerceIn(0, 23)..lastHour.coerceIn(0, 23)) {
                busy[hour] = true
            }
        }

        var hour = 0
        while (hour < 24) {
            var runEnd = hour
            while (runEnd < 24 && !busy[runEnd]) runEnd++
            if (runEnd - hour >= minGapHours) {
                if (hour in expandedGaps) {
                    expandedStarts.add(hour)
                    (hour until runEnd).forEach { rows.add(Row(it, it + 1)) }
                } else {
                    rows.add(Row(hour, runEnd))
                }
                hour = runEnd
            } else {
                rows.add(Row(hour, hour + 1))
                hour++
            }
        }

        var y = 0f
        for (row in rows) {
            tops.add(y)
            y += heightOf(row)
        }
        totalHeight = y
    }

    fun heightOf(row: Row) = if (row.isCollapsed) gapHeight else hourHeight

    fun topOf(index: Int) = tops[index]

    fun minuteToY(minute: Int): Float {
        val index = rows.indexOfLast { it.fromHour * 60 <= minute }.coerceAtLeast(0)
        val row = rows[index]
        val fraction = (minute - row.fromHour * 60) / ((row.toHour - row.fromHour) * 60f)
        return tops[index] + fraction.coerceIn(0f, 1f) * heightOf(row)
    }

    companion object {
        /**
         * Side-by-side columns for overlapping ranges, which must be sorted by start.
         * Returns (column, columnCount) for each range.
         */
        fun assignColumns(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            val columns = IntArray(ranges.size)
            val counts = IntArray(ranges.size)
            val columnEnds = ArrayList<Int>()
            val cluster = ArrayList<Int>()
            var clusterEnd = Int.MIN_VALUE

            fun closeCluster() {
                cluster.forEach { counts[it] = columnEnds.size }
                cluster.clear()
                columnEnds.clear()
            }

            ranges.forEachIndexed { i, (start, end) ->
                if (start >= clusterEnd) closeCluster()
                var column = columnEnds.indexOfFirst { it <= start }
                if (column == -1) {
                    column = columnEnds.size
                    columnEnds.add(end)
                } else {
                    columnEnds[column] = end
                }
                columns[i] = column
                cluster.add(i)
                clusterEnd = max(clusterEnd, end)
            }
            closeCluster()
            return ranges.indices.map { columns[it] to counts[it] }
        }
    }
}
