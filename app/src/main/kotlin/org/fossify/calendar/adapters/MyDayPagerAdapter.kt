package org.fossify.calendar.adapters

import android.os.Bundle
import android.util.SparseArray
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import org.fossify.calendar.fragments.DayFragment
import org.fossify.calendar.fragments.DayTimelineFragment
import org.fossify.calendar.helpers.DAY_CODE
import org.fossify.calendar.interfaces.DayPage
import org.fossify.calendar.interfaces.NavigationListener

class MyDayPagerAdapter(
    fm: FragmentManager,
    private val mCodes: List<String>,
    private val mListener: NavigationListener,
    private val mTimeline: Boolean
) :
    FragmentStatePagerAdapter(fm) {
    private val mFragments = SparseArray<DayPage>()

    override fun getCount() = mCodes.size

    override fun getItem(position: Int): Fragment {
        val bundle = Bundle()
        val code = mCodes[position]
        bundle.putString(DAY_CODE, code)

        val fragment = if (mTimeline) DayTimelineFragment() else DayFragment()
        fragment.arguments = bundle
        (fragment as DayPage).mListener = mListener

        return fragment
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val item = super.instantiateItem(container, position)
        if (item is DayPage) {
            mFragments.put(position, item)
        }
        return item
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        mFragments.remove(position)
        super.destroyItem(container, position, `object`)
    }

    fun updateCalendars(pos: Int) {
        for (i in -1..1) {
            mFragments[pos + i]?.updateCalendar()
        }
    }

    fun printCurrentView(pos: Int) {
        mFragments[pos].printCurrentView()
    }
}
