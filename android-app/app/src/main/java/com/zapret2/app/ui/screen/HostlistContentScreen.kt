package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun HostlistContentScreen(navController: NavController, viewModel: HostlistContentViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top bar
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (state.isEditing) viewModel.exitEditMode()
                    else navController.popBackStack()
                }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.fileName.removeSuffix(".txt"), fontSize = 16.sp, color = TextPrimary)
                    Text(
                        if (state.isEditing) "Editing" else "${state.totalLines} domains",
                        fontSize = 12.sp,
                        color = if (state.isEditing) AccentLightBlue else TextSecondary
                    )
                }
                if (!state.isEditing) {
                    // View mode: Edit button
                    IconButton(onClick = { viewModel.enterEditMode() }) {
                        Icon(Icons.Default.Edit, "Edit", tint = AccentLightBlue)
                    }
                } else {
                    // Edit mode: Save button
                    IconButton(
                        onClick = { viewModel.saveFile() },
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentLightBlue, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Save, "Save",
                                tint = if (state.hasUnsavedChanges) AccentLightBlue else TextQuaternary
                            )
                        }
                    }
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentLightBlue)
            }

            if (state.isEditing) {
                // Edit mode: full-file text editor
                OutlinedTextField(
                    value = state.editorContent,
                    onValueChange = { viewModel.updateEditorContent(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    textStyle = MonospaceStyle.copy(fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentLightBlue,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentLightBlue
                    ),
                    placeholder = { Text("One domain per line", color = TextHint, fontSize = 12.sp) }
                )

                // Bottom bar with line count and save button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val lineCount = state.editorContent.lines().size
                    Text("$lineCount lines", fontSize = 12.sp, color = TextTertiary)

                    Button(
                        onClick = { viewModel.saveFile() },
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", color = TextPrimary)
                    }
                }
            } else {
                // View mode: search + paginated list
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.search(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search domains...", color = TextHint) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentLightBlue,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Text(
                    state.showingCount,
                    fontSize = 12.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                val listState = rememberLazyListState()
                val shouldLoadMore = remember {
                    derivedStateOf {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= state.domains.size - 10 && state.domains.isNotEmpty()
                    }
                }
                LaunchedEffect(listState) {
                    snapshotFlow { shouldLoadMore.value }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect { viewModel.loadMore() }
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.domains) { index, domain ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("${index + 1}", fontSize = 12.sp, color = TextQuaternary, modifier = Modifier.width(40.dp))
                            Text(domain, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}
