package org.fossify.calendar.interfaces

interface DayPage {
    var mListener: NavigationListener?

    fun updateCalendar()

    fun printCurrentView()
}
