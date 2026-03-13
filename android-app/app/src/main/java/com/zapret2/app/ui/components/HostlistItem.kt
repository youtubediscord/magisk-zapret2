package com.zapret2.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapret2.app.ui.theme.*

@Composable
fun HostlistItem(
    filename: String,
    domainCount: Int,
    sizeBytes: Long,
    iconRes: Int,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ItemBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filename.removeSuffix(".txt"),
                fontSize = 14.sp,
                color = TextPrimary
            )
            Text(
                text = "$domainCount domains",
                fontSize = 11.sp,
                color = TextTertiary
            )
        }
        Text(
            text = formatFileSize(sizeBytes),
            fontSize = 12.sp,
            color = TextQuaternary
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
