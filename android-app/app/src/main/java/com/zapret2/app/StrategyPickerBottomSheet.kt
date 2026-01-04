package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * Bottom sheet for selecting a strategy for a hostlist or category.
 *
 * Now works with strategy NAMES instead of indices.
 * Returns the selected strategy ID (name) via callback.
 */
class StrategyPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CATEGORY_KEY = "category_key"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_ICON_RES = "icon_res"
        private const val ARG_CURRENT_STRATEGY_NAME = "current_strategy_name"
        private const val ARG_STRATEGY_TYPE = "strategy_type"

        const val TYPE_TCP = "tcp"
        const val TYPE_UDP = "udp"
        const val TYPE_VOICE = "voice"
        const val TYPE_DEBUG = "debug"
        const val TYPE_PKT_COUNT = "pkt_count"

        /**
         * Create a new instance with strategy NAME (not index)
         */
        fun newInstance(
            categoryKey: String,
            categoryName: String,
            protocol: String,
            iconRes: Int,
            currentStrategyName: String,  // Now accepts name instead of index
            strategyType: String
        ): StrategyPickerBottomSheet {
            return StrategyPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_KEY, categoryKey)
                    putString(ARG_CATEGORY_NAME, categoryName)
                    putString(ARG_PROTOCOL, protocol)
                    putInt(ARG_ICON_RES, iconRes)
                    putString(ARG_CURRENT_STRATEGY_NAME, currentStrategyName)  // Store as String
                    putString(ARG_STRATEGY_TYPE, strategyType)
                }
            }
        }
    }

    // Callback now returns strategy NAME (String) instead of index (Int)
    private var onStrategySelected: ((String) -> Unit)? = null

    fun setOnStrategySelectedListener(listener: (String) -> Unit) {
        onStrategySelected = listener
    }

    override fun getTheme(): Int = R.style.Theme_Zapret2_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_strategy_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val categoryKey = arguments?.getString(ARG_CATEGORY_KEY) ?: return
        val categoryName = arguments?.getString(ARG_CATEGORY_NAME) ?: return
        val protocol = arguments?.getString(ARG_PROTOCOL) ?: ""
        val iconRes = arguments?.getInt(ARG_ICON_RES) ?: R.drawable.ic_settings
        val currentStrategyName = arguments?.getString(ARG_CURRENT_STRATEGY_NAME) ?: "disabled"
        val strategyType = arguments?.getString(ARG_STRATEGY_TYPE) ?: TYPE_TCP

        // Set title and icon
        view.findViewById<TextView>(R.id.sheetTitle).text = categoryName
        view.findViewById<TextView>(R.id.sheetProtocol).text = protocol
        view.findViewById<ImageView>(R.id.sheetIcon).setImageResource(iconRes)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerStrategies)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Load strategies asynchronously for TCP/UDP
        when (strategyType) {
            TYPE_TCP -> loadStrategiesAsync(recyclerView, true, currentStrategyName)
            TYPE_UDP -> loadStrategiesAsync(recyclerView, false, currentStrategyName)
            TYPE_VOICE -> setupAdapter(recyclerView, getVoiceStrategies(), currentStrategyName)
            TYPE_DEBUG -> setupAdapter(recyclerView, getDebugModes(), currentStrategyName)
            TYPE_PKT_COUNT -> setupAdapter(recyclerView, getPktCountOptions(), currentStrategyName)
            else -> loadStrategiesAsync(recyclerView, true, currentStrategyName)
        }
    }

    /**
     * Load TCP/UDP strategies asynchronously and setup adapter
     */
    private fun loadStrategiesAsync(recyclerView: RecyclerView, isTcp: Boolean, currentStrategyName: String) {
        lifecycleScope.launch {
            val strategyInfoList = if (isTcp) {
                StrategyRepository.getTcpStrategies()
            } else {
                StrategyRepository.getUdpStrategies()
            }

            // Convert to StrategyItem format
            // strategyInfoList already includes "disabled" at index 0 from StrategyRepository
            val strategies = strategyInfoList.map { info ->
                StrategyItem(
                    id = info.id,  // Use the actual ID
                    name = info.displayName,
                    description = if (info.id == "disabled") "No DPI bypass" else ""
                )
            }

            setupAdapter(recyclerView, strategies, currentStrategyName)
        }
    }

    /**
     * Setup adapter with strategy items
     * Now finds selected item by NAME instead of index
     */
    private fun setupAdapter(recyclerView: RecyclerView, strategies: List<StrategyItem>, currentStrategyName: String) {
        recyclerView.adapter = StrategyAdapter(strategies, currentStrategyName) { selectedId ->
            // Return the strategy ID (name), not index
            onStrategySelected?.invoke(selectedId)
            dismiss()
        }
    }

    // Voice, Debug and PktCount strategies remain as hardcoded small lists
    // Now they have proper IDs

    private fun getVoiceStrategies(): List<StrategyItem> = listOf(
        StrategyItem("disabled", "Disabled", "No voice bypass"),
        StrategyItem("voice_fake_stun_6", "Strategy 1 - fake STUN x6", "6 STUN fake packets"),
        StrategyItem("voice_fake_stun_4", "Strategy 2 - fake STUN x4", "4 STUN fake packets"),
        StrategyItem("voice_fake_udplen", "Strategy 3 - fake+udplen", "STUN + length mod")
    )

    private fun getDebugModes(): List<StrategyItem> = listOf(
        StrategyItem("none", "None", "Logging disabled"),
        StrategyItem("android", "Android (logcat)", "Output to logcat"),
        StrategyItem("file", "File", "Write to file"),
        StrategyItem("syslog", "Syslog", "System logger")
    )

    private fun getPktCountOptions(): List<StrategyItem> = listOf(
        StrategyItem("1", "1", "Minimal"),
        StrategyItem("3", "3", "Light"),
        StrategyItem("5", "5", "Default"),
        StrategyItem("10", "10", "Extended"),
        StrategyItem("15", "15", "Heavy"),
        StrategyItem("20", "20", "Maximum")
    )

    /**
     * Strategy item with ID, name and description
     */
    data class StrategyItem(
        val id: String,           // The actual strategy ID (e.g., "syndata_multisplit_tls_google_700")
        val name: String,         // Display name
        val description: String = ""
    )

    /**
     * Adapter for strategy list
     * Now works with strategy IDs (names) instead of indices
     */
    inner class StrategyAdapter(
        private val items: List<StrategyItem>,
        private val selectedStrategyId: String,  // Changed from selectedIndex to selectedStrategyId
        private val onItemClick: (String) -> Unit  // Changed from Int to String
    ) : RecyclerView.Adapter<StrategyAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val radioButton: RadioButton = view.findViewById(R.id.radioStrategy)
            val nameText: TextView = view.findViewById(R.id.textStrategyName)
            val descText: TextView = view.findViewById(R.id.textStrategyDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_strategy_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameText.text = item.name

            // Check by ID (strategy name), not by position
            holder.radioButton.isChecked = item.id == selectedStrategyId

            if (item.description.isNotEmpty()) {
                holder.descText.visibility = View.VISIBLE
                holder.descText.text = item.description
            } else {
                holder.descText.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                // Return the strategy ID, not the position
                onItemClick(item.id)
            }
        }

        override fun getItemCount() = items.size
    }
}
