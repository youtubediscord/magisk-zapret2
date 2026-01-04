package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class CategoryEditBottomSheet : BottomSheetDialogFragment() {

    private var category: Category? = null
    private var onSaveListener: ((Category) -> Unit)? = null

    private lateinit var textCategoryName: TextView
    private lateinit var radioGroupFilterMode: RadioGroup
    private lateinit var radioHostlist: RadioButton
    private lateinit var radioIpset: RadioButton
    private lateinit var btnSave: MaterialButton

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_FILTER_MODE = "filter_mode"
        private const val ARG_HOSTLIST_FILE = "hostlist_file"
        private const val ARG_STRATEGY = "strategy"
        private const val ARG_SECTION = "section"

        fun newInstance(category: Category): CategoryEditBottomSheet {
            return CategoryEditBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, category.name)
                    putString(ARG_PROTOCOL, category.protocol)
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
            category = Category(
                name = args.getString(ARG_NAME, ""),
                protocol = args.getString(ARG_PROTOCOL, "tcp"),
                filterMode = Category.FilterMode.fromString(args.getString(ARG_FILTER_MODE, "hostlist")),
                hostlistFile = args.getString(ARG_HOSTLIST_FILE, ""),
                strategy = args.getString(ARG_STRATEGY, "disabled"),
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
        radioGroupFilterMode = view.findViewById(R.id.radioGroupFilterMode)
        radioHostlist = view.findViewById(R.id.radioHostlist)
        radioIpset = view.findViewById(R.id.radioIpset)
        btnSave = view.findViewById(R.id.btnSave)
    }

    private fun populateViews() {
        category?.let { cat ->
            textCategoryName.text = cat.getDisplayName()

            // Set filter mode radio (default to hostlist if none)
            when (cat.filterMode) {
                Category.FilterMode.HOSTLIST, Category.FilterMode.NONE -> radioHostlist.isChecked = true
                Category.FilterMode.IPSET -> radioIpset.isChecked = true
            }
        }
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            saveAndDismiss()
        }
    }

    private fun saveAndDismiss() {
        category?.let { cat ->
            val updatedCategory = cat.copy(
                filterMode = if (radioIpset.isChecked) {
                    Category.FilterMode.IPSET
                } else {
                    Category.FilterMode.HOSTLIST
                }
            )
            onSaveListener?.invoke(updatedCategory)
        }
        dismiss()
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme
}
