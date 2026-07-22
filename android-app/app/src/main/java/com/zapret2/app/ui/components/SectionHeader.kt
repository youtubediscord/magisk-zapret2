package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.zapret2.app.ui.theme.SpacingTokens

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .semantics { heading() }
            .padding(
                horizontal = SpacingTokens.ExtraSmall,
                vertical = SpacingTokens.ItemVertical,
            ),
    )
}
