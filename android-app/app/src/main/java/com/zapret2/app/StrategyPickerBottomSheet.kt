package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
 * Returns the selected strategy ID (name) and optional filter mode via callback.
 */
class StrategyPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CATEGORY_KEY = "category_key"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_ICON_RES = "icon_res"
        private const val ARG_CURRENT_STRATEGY_NAME = "current_strategy_name"
        private const val ARG_STRATEGY_TYPE = "strategy_type"
        private const val ARG_CAN_SWITCH_FILTER = "can_switch_filter"
        private const val ARG_CURRENT_FILTER_MODE = "current_filter_mode"

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
            strategyType: String,
            canSwitchFilterMode: Boolean = false,
            currentFilterMode: String = "none"
        ): StrategyPickerBottomSheet {
            return StrategyPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_KEY, categoryKey)
                    putString(ARG_CATEGORY_NAME, categoryName)
                    putString(ARG_PROTOCOL, protocol)
                    putInt(ARG_ICON_RES, iconRes)
                    putString(ARG_CURRENT_STRATEGY_NAME, currentStrategyName)  // Store as String
                    putString(ARG_STRATEGY_TYPE, strategyType)
                    putBoolean(ARG_CAN_SWITCH_FILTER, canSwitchFilterMode)
                    putString(ARG_CURRENT_FILTER_MODE, currentFilterMode)
                }
            }
        }
    }

    // Callback now returns strategy NAME and optional filter mode
    private var onStrategySelected: ((String) -> Unit)? = null
    private var onStrategyAndFilterSelected: ((String, String?) -> Unit)? = null

    // Current selected filter mode (updated when user clicks toggle)
    private var selectedFilterMode: String = "none"

    fun setOnStrategySelectedListener(listener: (String) -> Unit) {
        onStrategySelected = listener
    }

    /**
     * Set listener that receives both strategy name and filter mode.
     * Filter mode will be null if filter mode switching is not available.
     */
    fun setOnStrategyAndFilterSelectedListener(listener: (String, String?) -> Unit) {
        onStrategyAndFilterSelected = listener
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
        val canSwitchFilter = arguments?.getBoolean(ARG_CAN_SWITCH_FILTER) ?: false
        val currentFilterMode = arguments?.getString(ARG_CURRENT_FILTER_MODE) ?: "none"

        // Initialize selected filter mode
        selectedFilterMode = currentFilterMode

        // Set title and icon
        view.findViewById<TextView>(R.id.sheetTitle).text = categoryName
        view.findViewById<TextView>(R.id.sheetProtocol).text = protocol
        view.findViewById<ImageView>(R.id.sheetIcon).setImageResource(iconRes)

        // Setup filter mode toggle if available
        val filterModeContainer = view.findViewById<LinearLayout>(R.id.filterModeContainer)
        val btnFilterIpset = view.findViewById<TextView>(R.id.btnFilterIpset)
        val btnFilterHostlist = view.findViewById<TextView>(R.id.btnFilterHostlist)

        if (canSwitchFilter && strategyType in listOf(TYPE_TCP, TYPE_UDP, TYPE_VOICE)) {
            filterModeContainer.visibility = View.VISIBLE
            updateFilterModeButtons(btnFilterIpset, btnFilterHostlist, currentFilterMode)

            btnFilterIpset.setOnClickListener {
                selectedFilterMode = "ipset"
                updateFilterModeButtons(btnFilterIpset, btnFilterHostlist, "ipset")
            }

            btnFilterHostlist.setOnClickListener {
                selectedFilterMode = "hostlist"
                updateFilterModeButtons(btnFilterIpset, btnFilterHostlist, "hostlist")
            }
        } else {
            filterModeContainer.visibility = View.GONE
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerStrategies)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Load strategies asynchronously for TCP/UDP/Voice
        when (strategyType) {
            TYPE_TCP -> loadStrategiesAsync(recyclerView, true, currentStrategyName, canSwitchFilter)
            TYPE_UDP -> loadStrategiesAsync(recyclerView, false, currentStrategyName, canSwitchFilter)
            TYPE_VOICE -> loadStunStrategiesAsync(recyclerView, currentStrategyName, canSwitchFilter)
            TYPE_DEBUG -> setupAdapter(recyclerView, getDebugModes(), currentStrategyName, false)
            TYPE_PKT_COUNT -> setupAdapter(recyclerView, getPktCountOptions(), currentStrategyName, false)
            else -> loadStrategiesAsync(recyclerView, true, currentStrategyName, canSwitchFilter)
        }
    }

    /**
     * Update filter mode toggle button states
     */
    private fun updateFilterModeButtons(btnIpset: TextView, btnHostlist: TextView, selectedMode: String) {
        if (selectedMode == "ipset") {
            btnIpset.setBackgroundResource(R.drawable.segmented_button_left_selected)
            btnIpset.setTextColor(0xFFFFFFFF.toInt())
            btnHostlist.setBackgroundResource(R.drawable.segmented_button_right)
            btnHostlist.setTextColor(0xFF888888.toInt())
        } else {
            btnIpset.setBackgroundResource(R.drawable.segmented_button_left)
            btnIpset.setTextColor(0xFF888888.toInt())
            btnHostlist.setBackgroundResource(R.drawable.segmented_button_right_selected)
            btnHostlist.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    /**
     * Load TCP/UDP strategies asynchronously and setup adapter
     */
    private fun loadStrategiesAsync(recyclerView: RecyclerView, isTcp: Boolean, currentStrategyName: String, canSwitchFilter: Boolean) {
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

            setupAdapter(recyclerView, strategies, currentStrategyName, canSwitchFilter)
        }
    }

    /**
     * Load STUN strategies asynchronously for voice/video calls
     */
    private fun loadStunStrategiesAsync(recyclerView: RecyclerView, currentStrategyName: String, canSwitchFilter: Boolean) {
        lifecycleScope.launch {
            val strategyInfoList = StrategyRepository.getStunStrategies()

            // Convert to StrategyItem format
            val strategies = strategyInfoList.map { info ->
                StrategyItem(
                    id = info.id,
                    name = info.displayName,
                    description = if (info.id == "disabled") "No DPI bypass" else ""
                )
            }

            setupAdapter(recyclerView, strategies, currentStrategyName, canSwitchFilter)
        }
    }

    /**
     * Setup adapter with strategy items
     * Now finds selected item by NAME instead of index
     */
    private fun setupAdapter(recyclerView: RecyclerView, strategies: List<StrategyItem>, currentStrategyName: String, canSwitchFilter: Boolean) {
        recyclerView.adapter = StrategyAdapter(strategies, currentStrategyName) { selectedId ->
            // Return the strategy ID (name) and optional filter mode
            if (canSwitchFilter) {
                onStrategyAndFilterSelected?.invoke(selectedId, selectedFilterMode)
            }
            onStrategySelected?.invoke(selectedId)
            dismiss()
        }
    }

    // Debug and PktCount strategies remain as hardcoded small lists
    // Voice now uses UDP strategies (loaded dynamically via loadStrategiesAsync)

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
