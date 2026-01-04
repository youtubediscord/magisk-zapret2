package com.zapret2.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private var viewPager: ViewPager2? = null
    private var drawerLayout: DrawerLayout? = null
    private var navView: NavigationView? = null
    private var toolbar: MaterialToolbar? = null
    private var drawerToggle: ActionBarDrawerToggle? = null
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

        setupToolbar()
        setupDrawer()
        setupViewPager()
        setupBackPressHandler()
    }

    /**
     * Initialize views with null safety.
     * @return true if all required views were found, false otherwise
     */
    private fun initViews(): Boolean {
        viewPager = findViewById(R.id.viewPager)
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)

        return viewPager != null && drawerLayout != null && navView != null && toolbar != null
    }

    private fun setupToolbar() {
        val tb = toolbar ?: return
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
    }

    private fun setupDrawer() {
        val drawer = drawerLayout ?: return
        val tb = toolbar ?: return
        val nav = navView ?: return

        // Setup ActionBarDrawerToggle for hamburger menu animation
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawer,
            tb,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        drawer.addDrawerListener(drawerToggle!!)
        drawerToggle?.syncState()

        // Set navigation item selected listener
        nav.setNavigationItemSelectedListener(this)

        // Set first item as checked by default
        nav.menu.getItem(0)?.isChecked = true
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return
        val nav = navView ?: return

        val adapter = ViewPagerAdapter(this)
        pager.adapter = adapter

        // Enable swipe between pages
        pager.isUserInputEnabled = true

        // Listen for page changes to sync with navigation drawer
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Safe access to menu item with bounds check
                val menuItemCount = nav.menu.size()
                if (position in 0 until menuItemCount) {
                    nav.menu.getItem(position)?.isChecked = true
                    // Update toolbar title based on selected page
                    updateToolbarTitle(position)
                }
            }
        })
    }

    private fun updateToolbarTitle(position: Int) {
        val titles = arrayOf("Control", "Strategies", "Hostlists", "Logs", "About")
        if (position in titles.indices) {
            supportActionBar?.title = titles[position]
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val pager = viewPager ?: return false

        when (item.itemId) {
            R.id.nav_control -> pager.setCurrentItem(0, true)
            R.id.nav_strategies -> pager.setCurrentItem(1, true)
            R.id.nav_hostlists -> pager.setCurrentItem(2, true)
            R.id.nav_logs -> pager.setCurrentItem(3, true)
            R.id.nav_about -> pager.setCurrentItem(4, true)
            else -> return false
        }

        // Close drawer after selection
        drawerLayout?.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle drawer toggle click
        if (drawerToggle?.onOptionsItemSelected(item) == true) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = drawerLayout
                val pager = viewPager

                // If drawer is open, close it first
                if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                    return
                }

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
