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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

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
    private lateinit var editStrategy: EditText
    private lateinit var btnSave: MaterialButton

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
                    putInt(ARG_STRATEGY, category.strategy)
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
            category = Category(
                name = args.getString(ARG_NAME, ""),
                enabled = args.getBoolean(ARG_ENABLED, false),
                filterMode = Category.FilterMode.fromString(args.getString(ARG_FILTER_MODE, "none")),
                hostlistFile = args.getString(ARG_HOSTLIST_FILE, ""),
                strategy = args.getInt(ARG_STRATEGY, 1),
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
        editStrategy = view.findViewById(R.id.editStrategy)
        btnSave = view.findViewById(R.id.btnSave)
    }

    private fun populateViews() {
        category?.let { cat ->
            textCategoryName.text = cat.getDisplayName()
            switchEnabled.isChecked = cat.enabled
            editHostlistFile.setText(cat.hostlistFile)
            editStrategy.setText(cat.strategy.toString())

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

        btnSave.setOnClickListener {
            saveAndDismiss()
        }
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
                strategy = editStrategy.text.toString().toIntOrNull() ?: 1
            )

            onSaveListener?.invoke(updatedCategory)
        }

        dismiss()
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme
}
