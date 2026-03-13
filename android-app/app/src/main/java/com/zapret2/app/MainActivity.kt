package com.zapret2.app

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // Pre-warm root shell so fragments don't wait for initialization
        lifecycleScope.launch(Dispatchers.IO) {
            try { Shell.getShell() } catch (_: Exception) {}
        }

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

        // Find the nav header version text and set it
        val headerView = nav.getHeaderView(0)
        headerView?.findViewById<TextView>(R.id.navHeaderVersion)?.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return
        val nav = navView ?: return

        val adapter = ViewPagerAdapter(this)
        pager.adapter = adapter

        // Apply depth page transition animation
        pager.setPageTransformer(DepthPageTransformer())

        // Enable swipe between pages
        pager.isUserInputEnabled = true

        // Keep ALL pages alive — only 9 lightweight fragments, no reason to destroy/recreate
        pager.offscreenPageLimit = adapter.itemCount

        // Reduce ViewPager2 horizontal swipe sensitivity to prevent conflicts with vertical scroll
        try {
            val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
            recyclerViewField.isAccessible = true
            val recyclerView = recyclerViewField.get(pager) as? RecyclerView
            recyclerView?.let { rv ->
                val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
                touchSlopField.isAccessible = true
                val touchSlop = touchSlopField.getInt(rv)
                touchSlopField.setInt(rv, touchSlop * 3)
            }
        } catch (_: Exception) {
            // Reflection may fail on some Android versions -- NestedScrollableHost handles it as fallback
        }

        // Map ViewPager positions to navigation menu item IDs
        val navMenuIds = intArrayOf(
            R.id.nav_control, R.id.nav_strategies, R.id.nav_presets,
            R.id.nav_editor, R.id.nav_hostlists, R.id.nav_hosts_editor,
            R.id.nav_dns_manager, R.id.nav_logs, R.id.nav_about
        )

        // Listen for page changes to sync with navigation drawer
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Check the corresponding menu item by ID
                if (position in navMenuIds.indices) {
                    nav.setCheckedItem(navMenuIds[position])
                    // Update toolbar title based on selected page
                    updateToolbarTitle(position)
                }
            }
        })
    }

    private fun updateToolbarTitle(position: Int) {
        val titles = arrayOf("Control", "Strategies", "Presets", "Cmdline", "Hostlists", "Hosts Editor", "DNS", "Logs", "About")
        if (position in titles.indices) {
            supportActionBar?.title = titles[position]
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val pager = viewPager ?: return false

        when (item.itemId) {
            R.id.nav_control -> pager.setCurrentItem(0, true)
            R.id.nav_strategies -> pager.setCurrentItem(1, true)
            R.id.nav_presets -> pager.setCurrentItem(2, true)
            R.id.nav_editor -> pager.setCurrentItem(3, true)
            R.id.nav_hostlists -> pager.setCurrentItem(4, true)
            R.id.nav_hosts_editor -> pager.setCurrentItem(5, true)
            R.id.nav_dns_manager -> pager.setCurrentItem(6, true)
            R.id.nav_logs -> pager.setCurrentItem(7, true)
            R.id.nav_about -> pager.setCurrentItem(8, true)
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

    private class DepthPageTransformer : ViewPager2.PageTransformer {
        private val MIN_SCALE = 0.85f
        private val MIN_ALPHA = 0.5f

        override fun transformPage(page: View, position: Float) {
            page.apply {
                val pageWidth = width
                when {
                    position < -1 -> {
                        alpha = 0f
                    }
                    position <= 0 -> {
                        alpha = 1f
                        translationX = 0f
                        translationZ = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                    position <= 1 -> {
                        alpha = 1f - position * (1f - MIN_ALPHA)
                        translationX = pageWidth * -position
                        translationZ = -1f
                        val scaleFactor = MIN_SCALE + (1f - MIN_SCALE) * (1f - position)
                        scaleX = scaleFactor
                        scaleY = scaleFactor
                    }
                    else -> {
                        alpha = 0f
                    }
                }
            }
        }
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
