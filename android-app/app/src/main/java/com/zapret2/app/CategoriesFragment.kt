package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for displaying and managing categories from categories.txt
 * Allows enabling/disabling categories and changing filter modes
 */
class CategoriesFragment : Fragment() {

    // Views
    private lateinit var btnRefresh: ImageButton
    private lateinit var progressLoading: ProgressBar
    private lateinit var textError: TextView
    private lateinit var recyclerCategories: RecyclerView

    // Adapter
    private lateinit var adapter: CategoriesAdapter

    // Data
    private val categories = mutableListOf<Category>()
    private var originalFileLines = mutableListOf<String>()

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CATEGORIES_FILE = "$MODDIR/zapret2/categories.txt"
    private val START_SCRIPT = "$MODDIR/zapret2/scripts/zapret-start.sh"
    private val STOP_SCRIPT = "$MODDIR/zapret2/scripts/zapret-stop.sh"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_categories, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupRecyclerView()
        setupListeners()
        loadCategories()
    }

    private fun initViews(view: View) {
        btnRefresh = view.findViewById(R.id.btnRefresh)
        progressLoading = view.findViewById(R.id.progressLoading)
        textError = view.findViewById(R.id.textError)
        recyclerCategories = view.findViewById(R.id.recyclerCategories)
    }

    private fun setupRecyclerView() {
        adapter = CategoriesAdapter(
            onToggle = { category, enabled ->
                toggleCategory(category, enabled)
            },
            onEdit = { category ->
                showEditDialog(category)
            }
        )

        recyclerCategories.layoutManager = LinearLayoutManager(requireContext())
        recyclerCategories.adapter = adapter
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener {
            loadCategories()
        }
    }

    private fun loadCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            textError.visibility = View.GONE

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            }

            if (!result.isSuccess || result.out.isEmpty()) {
                showError("Failed to load categories.txt\nMake sure Zapret2 module is installed")
                showLoading(false)
                return@launch
            }

            // Parse the file
            parseCategories(result.out)

            showLoading(false)

            if (categories.isEmpty()) {
                showError("No categories found in categories.txt")
            } else {
                adapter.submitCategories(categories)
            }
        }
    }

    private fun parseCategories(lines: List<String>) {
        categories.clear()
        originalFileLines.clear()
        originalFileLines.addAll(lines)

        var currentSection = ""

        for (line in lines) {
            val trimmed = line.trim()

            // Check for section header
            val sectionName = Category.parseSectionName(trimmed)
            if (sectionName != null) {
                currentSection = sectionName
                continue
            }

            // Try to parse as category
            val category = Category.fromLine(trimmed, currentSection)
            if (category != null) {
                categories.add(category)
            }
        }
    }

    private fun toggleCategory(category: Category, enabled: Boolean) {
        // Update local data
        val index = categories.indexOfFirst { it.name == category.name }
        if (index >= 0) {
            categories[index] = category.copy(enabled = enabled)
            saveAndRestart()
        }
    }

    private fun showEditDialog(category: Category) {
        val bottomSheet = CategoryEditBottomSheet.newInstance(category)
        bottomSheet.setOnSaveListener { updatedCategory ->
            // Update local data
            val index = categories.indexOfFirst { it.name == updatedCategory.name }
            if (index >= 0) {
                categories[index] = updatedCategory
                adapter.submitCategories(categories.toList())
                saveAndRestart()
            }
        }
        bottomSheet.show(parentFragmentManager, "category_edit")
    }

    private fun saveAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            val newContent = buildCategoriesFile()

            val (saveSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                // Save the file
                val escaped = newContent.replace("'", "'\\''")
                val saveResult = Shell.cmd("echo '$escaped' > $CATEGORIES_FILE").exec()

                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // Restart zapret service
                Shell.cmd("$STOP_SCRIPT 2>/dev/null").exec()
                val startResult = Shell.cmd("$START_SCRIPT 2>/dev/null").exec()

                Pair(true, startResult.isSuccess)
            }

            if (saveSuccess && restartSuccess) {
                Toast.makeText(requireContext(), "Categories saved and applied", Toast.LENGTH_SHORT).show()
            } else if (saveSuccess) {
                Toast.makeText(requireContext(), "Saved, but service restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to save categories", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Rebuild the categories.txt file content
     * Preserves comments and section structure from original file
     */
    private fun buildCategoriesFile(): String {
        val result = StringBuilder()
        val updatedCategories = categories.associateBy { it.name }

        for (line in originalFileLines) {
            val trimmed = line.trim()

            // Keep comments and empty lines as-is
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.appendLine(line)
                continue
            }

            // Try to parse as category line
            val parts = trimmed.split("|")
            if (parts.size >= 5) {
                val name = parts[0]
                val updatedCategory = updatedCategories[name]

                if (updatedCategory != null) {
                    // Replace with updated category
                    result.appendLine(updatedCategory.toLine())
                } else {
                    // Keep original line
                    result.appendLine(line)
                }
            } else {
                // Keep unrecognized lines
                result.appendLine(line)
            }
        }

        return result.toString().trimEnd()
    }

    private fun showLoading(show: Boolean) {
        progressLoading.visibility = if (show) View.VISIBLE else View.GONE
        recyclerCategories.visibility = if (show) View.GONE else View.VISIBLE
        btnRefresh.isEnabled = !show
    }

    private fun showError(message: String) {
        textError.text = message
        textError.visibility = View.VISIBLE
        recyclerCategories.visibility = View.GONE
    }
}
