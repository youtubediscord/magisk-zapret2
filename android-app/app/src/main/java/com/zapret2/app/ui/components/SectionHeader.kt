package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zapret2.app.ui.theme.*

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(),
        style = SectionHeaderStyle,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}
