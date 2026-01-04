package com.zapret2.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {

    private var viewPager: ViewPager2? = null
    private var bottomNav: BottomNavigationView? = null
    private var backPressedTime: Long = 0

    companion object {
        private var shellInitialized = false

        fun initShell() {
            if (!shellInitialized) {
                try {
                    Shell.setDefaultBuilder(
                        Shell.Builder.create()
                            .setFlags(Shell.FLAG_MOUNT_MASTER)
                            .setTimeout(30)
                    )
                    shellInitialized = true
                } catch (e: Exception) {
                    // Shell initialization failed - will be handled in fragments
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Shell safely before UI setup
        initShell()

        setContentView(R.layout.activity_main)

        if (!initViews()) {
            // Critical views not found - show error and finish
            Toast.makeText(this, "Failed to initialize UI", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupViewPager()
        setupBottomNavigation()
        setupBackPressHandler()
    }

    /**
     * Initialize views with null safety.
     * @return true if all required views were found, false otherwise
     */
    private fun initViews(): Boolean {
        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)

        return viewPager != null && bottomNav != null
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return
        val nav = bottomNav ?: return

        val adapter = ViewPagerAdapter(this)
        pager.adapter = adapter

        // Disable swipe between pages (optional - can be enabled)
        pager.isUserInputEnabled = true

        // Listen for page changes to sync with bottom navigation
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Safe access to menu item with bounds check
                val menuItemCount = nav.menu.size()
                if (position in 0 until menuItemCount) {
                    nav.menu.getItem(position)?.isChecked = true
                }
            }
        })
    }

    private fun setupBottomNavigation() {
        val pager = viewPager ?: return
        val nav = bottomNav ?: return

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_control -> {
                    pager.setCurrentItem(0, true)
                    true
                }
                R.id.nav_strategies -> {
                    pager.setCurrentItem(1, true)
                    true
                }
                R.id.nav_categories -> {
                    pager.setCurrentItem(2, true)
                    true
                }
                R.id.nav_hostlists -> {
                    pager.setCurrentItem(3, true)
                    true
                }
                R.id.nav_logs -> {
                    pager.setCurrentItem(4, true)
                    true
                }
                R.id.nav_about -> {
                    pager.setCurrentItem(5, true)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pager = viewPager

                // If not on first page, go back to first page
                if (pager != null && pager.currentItem != 0) {
                    pager.setCurrentItem(0, true)
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
