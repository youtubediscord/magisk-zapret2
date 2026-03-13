package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val listState = rememberLazyListState()

    // Load more when near end
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.fileName.removeSuffix(".txt"), fontSize = 16.sp, color = TextPrimary)
                Text("${state.totalLines} domains", fontSize = 12.sp, color = TextSecondary)
            }
        }

        // Search
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.search(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search domains...", color = TextHint) },
            singleLine = true,
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Default.Clear, "Clear") }
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentLightBlue, unfocusedBorderColor = Border, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
        )

        Text(state.showingCount, fontSize = 12.sp, color = TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentLightBlue)

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
