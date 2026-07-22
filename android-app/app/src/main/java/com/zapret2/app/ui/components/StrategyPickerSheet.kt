package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R

data class StrategyItem(
    val id: String,
    val name: String,
    val description: String = "",
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
    onSelected: (strategyId: String, filterMode: String?) -> Unit,
) {
    var filterMode by rememberSaveable(currentFilterMode) { mutableStateOf(currentFilterMode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = SpacingTokens.CardContent)
                .navigationBarsPadding(),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpacingTokens.Large))

            if (canSwitchFilter) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = filterMode == "ipset",
                        onClick = { filterMode = "ipset" },
                        label = { Text(stringResource(R.string.strategy_filter_ipset)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(SpacingTokens.Small))
                    FilterChip(
                        selected = filterMode == "hostlist",
                        onClick = { filterMode = "hostlist" },
                        label = { Text(stringResource(R.string.strategy_filter_hostlist)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.Small))
            }

            LazyColumn(
                modifier = Modifier
                    .heightIn(max = SizeTokens.SheetContentMaxHeight)
                    .selectableGroup(),
            ) {
                items(items = strategies, key = { it.id }) { strategy ->
                    val selected = strategy.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SizeTokens.MinimumTouchTarget)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelected(
                                        strategy.id,
                                        if (canSwitchFilter) filterMode else null,
                                    )
                                },
                            )
                            .padding(
                                vertical = SpacingTokens.Medium,
                                horizontal = SpacingTokens.ExtraSmall,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                        Spacer(Modifier.width(SpacingTokens.Medium))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strategy.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (strategy.description.isNotEmpty()) {
                                Text(
                                    text = strategy.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(SpacingTokens.Medium))
        }
    }
}
