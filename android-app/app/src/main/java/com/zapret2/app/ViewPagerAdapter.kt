package com.zapret2.app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 8

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ControlFragment()
            1 -> StrategiesFragment()
            2 -> PresetsFragment()
            3 -> ConfigEditorFragment()
            4 -> HostlistsFragment()
            5 -> HostsEditorFragment()
            6 -> LogsFragment()
            7 -> AboutFragment()
            else -> ControlFragment()
        }
    }
}
