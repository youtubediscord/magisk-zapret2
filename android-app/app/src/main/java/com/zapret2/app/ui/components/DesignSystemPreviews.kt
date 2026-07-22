package com.zapret2.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.zapret2.app.R
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.ZapretTheme

@Preview(
    name = "Compact phone",
    widthDp = 320,
    heightDp = 640,
    showBackground = true,
)
@Preview(
    name = "Medium 600dp",
    widthDp = 600,
    heightDp = 480,
    showBackground = true,
)
@Preview(
    name = "Expanded 840dp · Dark",
    widthDp = 840,
    heightDp = 480,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(
    name = "Compact · RTL",
    widthDp = 360,
    heightDp = 640,
    locale = "ar",
    showBackground = true,
)
@Preview(
    name = "Compact · Russian 200% text",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
    locale = "ru",
    showBackground = true,
)
@Composable
private fun DesignSystemStatePreview() {
    ZapretTheme(dynamicColor = false, reducedMotion = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier.padding(SpacingTokens.Large),
            ) {
                val expanded = maxWidth >= SizeTokens.MediumBreakpoint
                val layoutModifier = Modifier.fillMaxWidth()
                if (expanded) {
                    Row(
                        modifier = layoutModifier,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Large),
                        verticalAlignment = Alignment.Top,
                    ) {
                        PreviewStatusCard(Modifier.weight(1f))
                        PreviewSettingsCard(Modifier.weight(1f))
                    }
                } else {
                    Column(
                        modifier = layoutModifier,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Large),
                    ) {
                        PreviewStatusCard()
                        PreviewSettingsCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStatusCard(modifier: Modifier = Modifier) {
    ContentCard(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.screen_control))
        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(isActive = true)
            Text(
                text = stringResource(R.string.state_active),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
        }
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewSettingsCard(modifier: Modifier = Modifier) {
    ContentCard(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.nav_group_configuration))
        SettingToggleRow(
            title = stringResource(R.string.screen_dns_manager),
            subtitle = stringResource(R.string.app_subtitle),
            checked = true,
            onCheckedChange = {},
        )
    }
}
