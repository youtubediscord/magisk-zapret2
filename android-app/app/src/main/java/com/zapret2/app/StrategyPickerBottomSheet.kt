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

class StrategyPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CATEGORY_KEY = "category_key"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_ICON_RES = "icon_res"
        private const val ARG_CURRENT_INDEX = "current_index"
        private const val ARG_STRATEGY_TYPE = "strategy_type"

        const val TYPE_TCP = "tcp"
        const val TYPE_UDP = "udp"
        const val TYPE_VOICE = "voice"
        const val TYPE_DEBUG = "debug"
        const val TYPE_PKT_COUNT = "pkt_count"

        fun newInstance(
            categoryKey: String,
            categoryName: String,
            protocol: String,
            iconRes: Int,
            currentIndex: Int,
            strategyType: String
        ): StrategyPickerBottomSheet {
            return StrategyPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_KEY, categoryKey)
                    putString(ARG_CATEGORY_NAME, categoryName)
                    putString(ARG_PROTOCOL, protocol)
                    putInt(ARG_ICON_RES, iconRes)
                    putInt(ARG_CURRENT_INDEX, currentIndex)
                    putString(ARG_STRATEGY_TYPE, strategyType)
                }
            }
        }
    }

    private var onStrategySelected: ((Int) -> Unit)? = null

    fun setOnStrategySelectedListener(listener: (Int) -> Unit) {
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
        val currentIndex = arguments?.getInt(ARG_CURRENT_INDEX) ?: 0
        val strategyType = arguments?.getString(ARG_STRATEGY_TYPE) ?: TYPE_TCP

        // Set title and icon
        view.findViewById<TextView>(R.id.sheetTitle).text = categoryName
        view.findViewById<TextView>(R.id.sheetProtocol).text = protocol
        view.findViewById<ImageView>(R.id.sheetIcon).setImageResource(iconRes)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerStrategies)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Load strategies asynchronously for TCP/UDP
        when (strategyType) {
            TYPE_TCP -> loadStrategiesAsync(recyclerView, true, currentIndex)
            TYPE_UDP -> loadStrategiesAsync(recyclerView, false, currentIndex)
            TYPE_VOICE -> setupAdapter(recyclerView, getVoiceStrategies(), currentIndex)
            TYPE_DEBUG -> setupAdapter(recyclerView, getDebugModes(), currentIndex)
            TYPE_PKT_COUNT -> setupAdapter(recyclerView, getPktCountOptions(), currentIndex)
            else -> loadStrategiesAsync(recyclerView, true, currentIndex)
        }
    }

    private fun loadStrategiesAsync(recyclerView: RecyclerView, isTcp: Boolean, currentIndex: Int) {
        lifecycleScope.launch {
            val strategyInfoList = if (isTcp) {
                StrategyRepository.getTcpStrategies()
            } else {
                StrategyRepository.getUdpStrategies()
            }

            // Convert to StrategyItem format
            val strategies = strategyInfoList.map { info ->
                StrategyItem(info.displayName, info.id)
            }

            setupAdapter(recyclerView, strategies, currentIndex)
        }
    }

    private fun setupAdapter(recyclerView: RecyclerView, strategies: List<StrategyItem>, currentIndex: Int) {
        recyclerView.adapter = StrategyAdapter(strategies, currentIndex) { selectedIndex ->
            onStrategySelected?.invoke(selectedIndex)
            dismiss()
        }
    }

    // Voice, Debug and PktCount strategies remain as hardcoded small lists

    private fun getVoiceStrategies(): List<StrategyItem> = listOf(
        StrategyItem("Disabled", "No voice bypass"),
        StrategyItem("Strategy 1 - fake STUN x6", "6 STUN fake packets"),
        StrategyItem("Strategy 2 - fake STUN x4", "4 STUN fake packets"),
        StrategyItem("Strategy 3 - fake+udplen", "STUN + length mod")
    )

    private fun getDebugModes(): List<StrategyItem> = listOf(
        StrategyItem("None", "Logging disabled"),
        StrategyItem("Android (logcat)", "Output to logcat"),
        StrategyItem("File", "Write to file"),
        StrategyItem("Syslog", "System logger")
    )

    private fun getPktCountOptions(): List<StrategyItem> = listOf(
        StrategyItem("1", "Minimal"),
        StrategyItem("3", "Light"),
        StrategyItem("5", "Default"),
        StrategyItem("10", "Extended"),
        StrategyItem("15", "Heavy"),
        StrategyItem("20", "Maximum")
    )

    data class StrategyItem(val name: String, val description: String = "")

    inner class StrategyAdapter(
        private val items: List<StrategyItem>,
        private val selectedIndex: Int,
        private val onItemClick: (Int) -> Unit
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
            holder.radioButton.isChecked = position == selectedIndex

            if (item.description.isNotEmpty()) {
                holder.descText.visibility = View.VISIBLE
                holder.descText.text = item.description
            } else {
                holder.descText.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount() = items.size
    }
}
