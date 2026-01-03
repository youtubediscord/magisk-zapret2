package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * BottomSheet dialog for editing a category's properties
 */
class CategoryEditBottomSheet : BottomSheetDialogFragment() {

    private var category: Category? = null
    private var onSaveListener: ((Category) -> Unit)? = null

    // Views
    private lateinit var textCategoryName: TextView
    private lateinit var switchEnabled: SwitchCompat
    private lateinit var radioGroupFilterMode: RadioGroup
    private lateinit var radioNone: RadioButton
    private lateinit var radioHostlist: RadioButton
    private lateinit var radioIpset: RadioButton
    private lateinit var layoutHostlistFile: LinearLayout
    private lateinit var editHostlistFile: EditText
    private lateinit var layoutStrategyPicker: LinearLayout
    private lateinit var textSelectedStrategy: TextView
    private lateinit var btnSave: MaterialButton

    // Strategy selection state
    private var selectedStrategyId: String = "disabled"
    private var tcpStrategies: List<StrategyRepository.StrategyInfo> = emptyList()
    private var udpStrategies: List<StrategyRepository.StrategyInfo> = emptyList()

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_ENABLED = "enabled"
        private const val ARG_FILTER_MODE = "filter_mode"
        private const val ARG_HOSTLIST_FILE = "hostlist_file"
        private const val ARG_STRATEGY = "strategy"
        private const val ARG_SECTION = "section"

        fun newInstance(category: Category): CategoryEditBottomSheet {
            return CategoryEditBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, category.name)
                    putBoolean(ARG_ENABLED, category.enabled)
                    putString(ARG_FILTER_MODE, category.filterMode.value)
                    putString(ARG_HOSTLIST_FILE, category.hostlistFile)
                    putString(ARG_STRATEGY, category.strategy)
                    putString(ARG_SECTION, category.section)
                }
            }
        }
    }

    fun setOnSaveListener(listener: (Category) -> Unit) {
        onSaveListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            selectedStrategyId = args.getString(ARG_STRATEGY, "disabled")
            category = Category(
                name = args.getString(ARG_NAME, ""),
                enabled = args.getBoolean(ARG_ENABLED, false),
                filterMode = Category.FilterMode.fromString(args.getString(ARG_FILTER_MODE, "none")),
                hostlistFile = args.getString(ARG_HOSTLIST_FILE, ""),
                strategy = selectedStrategyId,
                section = args.getString(ARG_SECTION, "")
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_category_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        loadStrategies()
        populateViews()
        setupListeners()
    }

    private fun initViews(view: View) {
        textCategoryName = view.findViewById(R.id.textCategoryName)
        switchEnabled = view.findViewById(R.id.switchEnabled)
        radioGroupFilterMode = view.findViewById(R.id.radioGroupFilterMode)
        radioNone = view.findViewById(R.id.radioNone)
        radioHostlist = view.findViewById(R.id.radioHostlist)
        radioIpset = view.findViewById(R.id.radioIpset)
        layoutHostlistFile = view.findViewById(R.id.layoutHostlistFile)
        editHostlistFile = view.findViewById(R.id.editHostlistFile)
        layoutStrategyPicker = view.findViewById(R.id.layoutStrategyPicker)
        textSelectedStrategy = view.findViewById(R.id.textSelectedStrategy)
        btnSave = view.findViewById(R.id.btnSave)
    }

    private fun loadStrategies() {
        lifecycleScope.launch {
            tcpStrategies = StrategyRepository.getTcpStrategies()
            udpStrategies = StrategyRepository.getUdpStrategies()
            updateStrategyDisplay()
        }
    }

    private fun populateViews() {
        category?.let { cat ->
            textCategoryName.text = cat.getDisplayName()
            switchEnabled.isChecked = cat.enabled
            editHostlistFile.setText(cat.hostlistFile)
            updateStrategyDisplay()

            // Set filter mode radio
            when (cat.filterMode) {
                Category.FilterMode.NONE -> radioNone.isChecked = true
                Category.FilterMode.HOSTLIST -> radioHostlist.isChecked = true
                Category.FilterMode.IPSET -> radioIpset.isChecked = true
            }

            // Show/hide hostlist field
            updateHostlistVisibility(cat.filterMode)
        }
    }

    private fun updateStrategyDisplay() {
        val strategies = getStrategiesForCurrentCategory()
        val strategy = strategies.find { it.id == selectedStrategyId }
        textSelectedStrategy.text = strategy?.displayName ?: selectedStrategyId.formatStrategyName()
    }

    private fun String.formatStrategyName(): String {
        return this.split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun getStrategiesForCurrentCategory(): List<StrategyRepository.StrategyInfo> {
        val categoryName = category?.name?.lowercase() ?: ""
        return if (categoryName.contains("udp") || categoryName.contains("quic")) {
            udpStrategies
        } else {
            tcpStrategies
        }
    }

    private fun isUdpCategory(): Boolean {
        val categoryName = category?.name?.lowercase() ?: ""
        return categoryName.contains("udp") || categoryName.contains("quic")
    }

    private fun setupListeners() {
        radioGroupFilterMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioNone -> Category.FilterMode.NONE
                R.id.radioHostlist -> Category.FilterMode.HOSTLIST
                R.id.radioIpset -> Category.FilterMode.IPSET
                else -> Category.FilterMode.NONE
            }
            updateHostlistVisibility(mode)
        }

        // Strategy picker click
        layoutStrategyPicker.setOnClickListener {
            showStrategyPicker()
        }

        btnSave.setOnClickListener {
            saveAndDismiss()
        }
    }

    private fun showStrategyPicker() {
        val strategies = getStrategiesForCurrentCategory()
        val currentIndex = strategies.indexOfFirst { it.id == selectedStrategyId }.takeIf { it >= 0 } ?: 0

        val strategyType = if (isUdpCategory()) {
            StrategyPickerBottomSheet.TYPE_UDP
        } else {
            StrategyPickerBottomSheet.TYPE_TCP
        }

        val picker = StrategyPickerBottomSheet.newInstance(
            categoryKey = category?.name ?: "",
            categoryName = "Select Strategy",
            protocol = if (isUdpCategory()) "UDP" else "TCP",
            iconRes = R.drawable.ic_settings,
            currentIndex = currentIndex,
            strategyType = strategyType
        )

        picker.setOnStrategySelectedListener { selectedIndex ->
            val newStrategy = strategies.getOrNull(selectedIndex)
            if (newStrategy != null) {
                selectedStrategyId = newStrategy.id
                updateStrategyDisplay()
            }
        }

        picker.show(parentFragmentManager, "strategy_picker")
    }

    private fun updateHostlistVisibility(mode: Category.FilterMode) {
        layoutHostlistFile.visibility = when (mode) {
            Category.FilterMode.NONE -> View.GONE
            else -> View.VISIBLE
        }
    }

    private fun saveAndDismiss() {
        category?.let { cat ->
            // Update category with new values
            val updatedCategory = cat.copy(
                enabled = switchEnabled.isChecked,
                filterMode = when {
                    radioNone.isChecked -> Category.FilterMode.NONE
                    radioHostlist.isChecked -> Category.FilterMode.HOSTLIST
                    radioIpset.isChecked -> Category.FilterMode.IPSET
                    else -> Category.FilterMode.NONE
                },
                hostlistFile = editHostlistFile.text.toString().trim(),
                strategy = selectedStrategyId
            )

            onSaveListener?.invoke(updatedCategory)
        }

        dismiss()
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme
}
