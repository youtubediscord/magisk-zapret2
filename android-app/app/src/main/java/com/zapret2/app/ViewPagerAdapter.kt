package com.zapret2.app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ControlFragment()
            1 -> StrategiesFragment()
            2 -> CategoriesFragment()
            3 -> HostlistsFragment()
            4 -> LogsFragment()
            5 -> AboutFragment()
            else -> ControlFragment()
        }
    }
}
