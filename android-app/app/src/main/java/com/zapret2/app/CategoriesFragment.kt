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

    // Views - nullable to handle lifecycle properly
    private var btnRefresh: ImageButton? = null
    private var progressLoading: ProgressBar? = null
    private var textError: TextView? = null
    private var recyclerCategories: RecyclerView? = null

    // Adapter
    private var adapter: CategoriesAdapter? = null

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

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear references to avoid memory leaks and crashes
        recyclerCategories?.adapter = null
        btnRefresh = null
        progressLoading = null
        textError = null
        recyclerCategories = null
        adapter = null
    }

    private fun initViews(view: View) {
        btnRefresh = view.findViewById(R.id.btnRefresh)
        progressLoading = view.findViewById(R.id.progressLoading)
        textError = view.findViewById(R.id.textError)
        recyclerCategories = view.findViewById(R.id.recyclerCategories)
    }

    private fun setupRecyclerView() {
        val newAdapter = CategoriesAdapter(
            onToggle = { category, enabled ->
                toggleCategory(category, enabled)
            },
            onEdit = { category ->
                showEditDialog(category)
            }
        )
        adapter = newAdapter

        recyclerCategories?.layoutManager = LinearLayoutManager(requireContext())
        recyclerCategories?.adapter = newAdapter
    }

    private fun setupListeners() {
        btnRefresh?.setOnClickListener {
            loadCategories()
        }
    }

    private fun loadCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            textError?.visibility = View.GONE

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            }

            // Check if view is still valid after IO operation
            if (!isAdded || view == null) return@launch

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
                adapter?.submitCategories(categories)
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
            adapter?.submitCategories(categories.toList())
            saveAndRestart()
        }
    }

    private fun showEditDialog(category: Category) {
        // Check if fragment is still attached before showing dialog
        if (!isAdded) return

        val bottomSheet = CategoryEditBottomSheet.newInstance(category)
        bottomSheet.setOnSaveListener { updatedCategory ->
            // Check if fragment is still attached when callback fires
            if (!isAdded) return@setOnSaveListener

            // Update local data
            val index = categories.indexOfFirst { it.name == updatedCategory.name }
            if (index >= 0) {
                categories[index] = updatedCategory
                adapter?.submitCategories(categories.toList())
                saveAndRestart()
            }
        }
        bottomSheet.show(parentFragmentManager, "category_edit")
    }

    private fun saveAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            val newContent = buildCategoriesFile()

            val (saveSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                // Save the file using base64 encoding to avoid shell escaping issues
                val encoded = android.util.Base64.encodeToString(
                    newContent.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val saveResult = Shell.cmd("echo '$encoded' | base64 -d > $CATEGORIES_FILE").exec()

                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // Restart zapret service
                Shell.cmd("$STOP_SCRIPT 2>/dev/null").exec()
                val startResult = Shell.cmd("$START_SCRIPT 2>/dev/null").exec()

                Pair(true, startResult.isSuccess)
            }

            // Check if fragment is still attached before showing Toast
            if (!isAdded) return@launch
            val ctx = context ?: return@launch

            if (saveSuccess && restartSuccess) {
                Toast.makeText(ctx, "Categories saved and applied", Toast.LENGTH_SHORT).show()
            } else if (saveSuccess) {
                Toast.makeText(ctx, "Saved, but service restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Failed to save categories", Toast.LENGTH_SHORT).show()
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
        progressLoading?.visibility = if (show) View.VISIBLE else View.GONE
        recyclerCategories?.visibility = if (show) View.GONE else View.VISIBLE
        btnRefresh?.isEnabled = !show
    }

    private fun showError(message: String) {
        textError?.text = message
        textError?.visibility = View.VISIBLE
        recyclerCategories?.visibility = View.GONE
    }
}
