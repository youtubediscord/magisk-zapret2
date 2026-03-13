package com.zapret2.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapret2.app.ui.theme.*

data class StrategyItem(
    val id: String,
    val name: String,
    val description: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyPickerSheet(
    title: String,
    subtitle: String,
    strategies: List<StrategyItem>,
    selectedId: String,
    canSwitchFilter: Boolean = false,
    currentFilterMode: String = "none",
    onDismiss: () -> Unit,
    onSelected: (strategyId: String, filterMode: String?) -> Unit
) {
    var filterMode by remember { mutableStateOf(currentFilterMode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        contentColor = TextPrimary,
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = title, fontSize = 18.sp, color = TextPrimary)
            Text(text = subtitle, fontSize = 13.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))

            if (canSwitchFilter) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilterChip(
                        selected = filterMode == "ipset",
                        onClick = { filterMode = "ipset" },
                        label = { Text("IPSet") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = filterMode == "hostlist",
                        onClick = { filterMode = "hostlist" },
                        label = { Text("Hostlist") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(strategies) { strategy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(strategy.id, if (canSwitchFilter) filterMode else null)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = strategy.id == selectedId,
                            onClick = {
                                onSelected(strategy.id, if (canSwitchFilter) filterMode else null)
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AccentLightBlue,
                                unselectedColor = TextQuaternary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = strategy.name, fontSize = 14.sp, color = TextPrimary)
                            if (strategy.description.isNotEmpty()) {
                                Text(text = strategy.description, fontSize = 11.sp, color = TextTertiary)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
