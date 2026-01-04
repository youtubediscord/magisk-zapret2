package com.zapret2.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying categories in RecyclerView
 * Supports two view types: section headers and category items
 */
class CategoriesAdapter(
    private val onEdit: (Category) -> Unit
) : ListAdapter<CategoriesAdapter.ListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    sealed class ListItem {
        data class SectionItem(val title: String) : ListItem()
        data class CategoryItem(val category: Category) : ListItem()
    }

    companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_CATEGORY = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.SectionItem -> VIEW_TYPE_SECTION
            is ListItem.CategoryItem -> VIEW_TYPE_CATEGORY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> {
                val view = inflater.inflate(R.layout.item_category_section, parent, false)
                SectionViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_category, parent, false)
                CategoryViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.SectionItem -> (holder as SectionViewHolder).bind(item.title)
            is ListItem.CategoryItem -> (holder as CategoryViewHolder).bind(item.category)
        }
    }

    /**
     * Convert flat category list to list with section headers
     */
    fun submitCategories(categories: List<Category>) {
        val items = mutableListOf<ListItem>()
        var currentSection = ""

        for (category in categories) {
            if (category.section.isNotEmpty() && category.section != currentSection) {
                currentSection = category.section
                items.add(ListItem.SectionItem(currentSection))
            }
            items.add(ListItem.CategoryItem(category))
        }

        submitList(items)
    }

    inner class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textTitle: TextView = view.findViewById(R.id.textSectionTitle)

        fun bind(title: String) {
            textTitle.text = title
        }
    }

    inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textName: TextView = view.findViewById(R.id.textCategoryName)
        private val textProtocol: TextView = view.findViewById(R.id.textProtocol)
        private val textHostlist: TextView = view.findViewById(R.id.textHostlist)
        private val textStrategy: TextView = view.findViewById(R.id.textStrategy)
        private val btnEdit: ImageView = view.findViewById(R.id.btnEdit)

        fun bind(category: Category) {
            textName.text = category.getDisplayName()

            // Protocol badge
            textProtocol.text = category.protocol.uppercase()
            textProtocol.setBackgroundColor(getProtocolColor(category.protocol))

            // Dim row if disabled (strategy == "disabled")
            val alpha = if (category.isEnabled) 1.0f else 0.5f
            itemView.alpha = alpha

            // Hostlist file
            if (category.hostlistFile.isNotEmpty()) {
                textHostlist.visibility = View.VISIBLE
                textHostlist.text = category.hostlistFile
            } else {
                textHostlist.visibility = View.GONE
            }

            // Strategy badge - show "Off" or strategy name
            textStrategy.text = formatStrategyName(category.strategy)

            // Edit button and row click
            btnEdit.setOnClickListener { onEdit(category) }
            itemView.setOnClickListener { onEdit(category) }
        }

        private fun getProtocolColor(protocol: String): Int {
            return when (protocol.lowercase()) {
                "tcp" -> Color.parseColor("#2D5A27")   // green
                "udp" -> Color.parseColor("#5A4A2D")   // orange
                "stun" -> Color.parseColor("#4A3B5C") // purple
                else -> Color.parseColor("#505050")
            }
        }

        private fun formatStrategyName(strategyId: String): String {
            if (strategyId.isEmpty() || strategyId == "disabled") {
                return "Off"
            }
            // Convert snake_case to Title Case, keeping it short
            val words = strategyId.split("_")
            // Take first 2-3 significant words for compact display
            val significantWords = words.take(3)
            return significantWords.joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return when {
                oldItem is ListItem.SectionItem && newItem is ListItem.SectionItem ->
                    oldItem.title == newItem.title
                oldItem is ListItem.CategoryItem && newItem is ListItem.CategoryItem ->
                    oldItem.category.name == newItem.category.name
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return oldItem == newItem
        }
    }
}
