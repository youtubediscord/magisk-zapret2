package com.zapret2.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

class AboutFragment : Fragment() {

    // UI Elements
    private lateinit var textAppVersionAbout: TextView
    private lateinit var cardTelegramGroup: LinearLayout
    private lateinit var cardVpnService: LinearLayout
    private lateinit var cardBolvan: LinearLayout
    private lateinit var cardYoutubediscord: LinearLayout
    private lateinit var cardGithubRepo: LinearLayout

    // URLs
    companion object {
        private const val URL_TELEGRAM_GROUP = "https://t.me/bypassblock"
        private const val URL_VPN_BOT = "https://t.me/zapretvpns_bot"
        private const val URL_GITHUB_BOLVAN = "https://github.com/bol-van/zapret"
        private const val URL_GITHUB_YOUTUBEDISCORD = "https://github.com/youtubediscord"
        private const val URL_GITHUB_REPO = "https://github.com/bol-van/zapret"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        displayVersion()
    }

    private fun initViews(view: View) {
        textAppVersionAbout = view.findViewById(R.id.textAppVersionAbout)
        cardTelegramGroup = view.findViewById(R.id.cardTelegramGroup)
        cardVpnService = view.findViewById(R.id.cardVpnService)
        cardBolvan = view.findViewById(R.id.cardBolvan)
        cardYoutubediscord = view.findViewById(R.id.cardYoutubediscord)
        cardGithubRepo = view.findViewById(R.id.cardGithubRepo)
    }

    private fun setupClickListeners() {
        cardTelegramGroup.setOnClickListener {
            openUrl(URL_TELEGRAM_GROUP)
        }

        cardVpnService.setOnClickListener {
            openUrl(URL_VPN_BOT)
        }

        cardBolvan.setOnClickListener {
            openUrl(URL_GITHUB_BOLVAN)
        }

        cardYoutubediscord.setOnClickListener {
            openUrl(URL_GITHUB_YOUTUBEDISCORD)
        }

        cardGithubRepo.setOnClickListener {
            openUrl(URL_GITHUB_REPO)
        }
    }

    private fun displayVersion() {
        textAppVersionAbout.text = "v${BuildConfig.VERSION_NAME}"
    }

    /**
     * Opens a URL in the default browser or appropriate app.
     * Handles Telegram links specially to open in the Telegram app if available.
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // If no app can handle the intent, fail silently
            e.printStackTrace()
        }
    }
}
