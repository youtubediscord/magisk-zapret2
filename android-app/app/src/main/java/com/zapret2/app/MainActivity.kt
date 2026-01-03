package com.zapret2.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private var backPressedTime: Long = 0

    companion object {
        init {
            // Initialize Shell with root settings
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(30)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupViewPager()
        setupBottomNavigation()
        setupBackPressHandler()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        // Disable swipe between pages (optional - can be enabled)
        viewPager.isUserInputEnabled = true

        // Listen for page changes to sync with bottom navigation
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNav.menu.getItem(position).isChecked = true
            }
        })
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_control -> {
                    viewPager.setCurrentItem(0, true)
                    true
                }
                R.id.nav_strategies -> {
                    viewPager.setCurrentItem(1, true)
                    true
                }
                R.id.nav_categories -> {
                    viewPager.setCurrentItem(2, true)
                    true
                }
                R.id.nav_hostlists -> {
                    viewPager.setCurrentItem(3, true)
                    true
                }
                R.id.nav_logs -> {
                    viewPager.setCurrentItem(4, true)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If not on first page, go back to first page
                if (viewPager.currentItem != 0) {
                    viewPager.setCurrentItem(0, true)
                    return
                }

                // Double-press to exit
                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Press back again to exit",
                        Toast.LENGTH_SHORT
                    ).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })
    }
}
