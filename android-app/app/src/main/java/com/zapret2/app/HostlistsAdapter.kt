package com.zapret2.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView Adapter for displaying hostlist files.
 * Simplified version - shows hostlist name, domain count, and opens content on click.
 */
class HostlistsAdapter(
    private val items: MutableList<HostlistsFragment.HostlistConfig>,
    private val onItemClick: (HostlistsFragment.HostlistConfig) -> Unit
) : RecyclerView.Adapter<HostlistsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconCategory: ImageView = view.findViewById(R.id.iconCategory)
        val textHostlistName: TextView = view.findViewById(R.id.textHostlistName)
        val textDomainCount: TextView = view.findViewById(R.id.textDomainCount)
        val iconChevron: ImageView = view.findViewById(R.id.iconChevron)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hostlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = items[position]
        val context = holder.itemView.context

        // Set hostlist name (without .txt extension)
        val displayName = config.filename.removeSuffix(".txt")
        holder.textHostlistName.text = displayName

        // Set domain count
        holder.textDomainCount.text = "${formatNumber(config.domainCount)} domains"

        // Set category icon
        val iconRes = getHostlistIcon(config.filename)
        holder.iconCategory.setImageResource(iconRes)

        // Set icon tint based on category
        val iconTint = getHostlistIconTint(config.filename)
        holder.iconCategory.setColorFilter(ContextCompat.getColor(context, iconTint))

        // Click on whole card opens content
        holder.itemView.setOnClickListener {
            onItemClick(config)
        }
    }

    override fun getItemCount() = items.size

    /**
     * Get icon resource based on hostlist filename
     */
    private fun getHostlistIcon(filename: String): Int {
        val name = filename.lowercase().removeSuffix(".txt")
        return when {
            name.contains("youtube") -> R.drawable.ic_video
            name.contains("discord") -> R.drawable.ic_message
            name.contains("telegram") -> R.drawable.ic_message
            name.contains("whatsapp") -> R.drawable.ic_message
            name.contains("facebook") -> R.drawable.ic_social
            name.contains("instagram") -> R.drawable.ic_social
            name.contains("twitter") -> R.drawable.ic_social
            name.contains("tiktok") -> R.drawable.ic_video
            name.contains("twitch") -> R.drawable.ic_video
            name.contains("spotify") -> R.drawable.ic_apps
            name.contains("soundcloud") -> R.drawable.ic_apps
            name.contains("steam") -> R.drawable.ic_apps
            name.contains("google") -> R.drawable.ic_apps
            else -> R.drawable.ic_hostlist
        }
    }

    /**
     * Get icon tint color based on hostlist filename
     */
    private fun getHostlistIconTint(filename: String): Int {
        val name = filename.lowercase().removeSuffix(".txt")
        return when {
            name.contains("youtube") -> R.color.youtube_red
            name.contains("discord") -> R.color.discord_blue
            name.contains("telegram") -> R.color.telegram_blue
            name.contains("whatsapp") -> R.color.whatsapp_green
            name.contains("facebook") -> R.color.facebook_blue
            name.contains("instagram") -> R.color.instagram_pink
            name.contains("twitter") -> R.color.twitter_blue
            else -> R.color.accent_light_blue
        }
    }

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }
}
