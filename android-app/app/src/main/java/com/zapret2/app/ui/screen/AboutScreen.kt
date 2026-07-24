package com.zapret2.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.BuildConfig
import com.zapret2.app.R
import com.zapret2.app.ui.components.ContentCard
import kotlinx.coroutines.launch

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val linkError = stringResource(R.string.about_link_error)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openDestination: (AboutDestination) -> Unit = { destination ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, destination.httpsUrl.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                },
            )
        }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar(linkError)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) SpacingTokens.ExtraLarge else SpacingTokens.Large
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = SizeTokens.ExpandedBreakpoint)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.ItemVertical),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    top = SpacingTokens.Large,
                    end = horizontalPadding,
                    bottom = SpacingTokens.Section,
                ),
            ) {
                item { AboutHero() }
                item {
                    Text(
                        text = stringResource(R.string.about_community),
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = SpacingTokens.ExtraSmall, top = SpacingTokens.ItemVertical, bottom = SpacingTokens.Micro),
                    )
                }
                item {
                    ExternalLinkCard(
                        title = stringResource(R.string.about_telegram_group),
                        subtitle = stringResource(R.string.about_telegram_handle),
                        icon = Icons.Default.Groups,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { openDestination(AboutDestination.TELEGRAM_GROUP) },
                    )
                }
                item {
                    ExternalLinkCard(
                        title = stringResource(R.string.about_vpn_bot),
                        subtitle = stringResource(R.string.about_vpn_handle),
                        icon = Icons.Default.VpnKey,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { openDestination(AboutDestination.VPN_BOT) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.about_source_credits),
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = SpacingTokens.ExtraSmall, top = SpacingTokens.ItemVertical, bottom = SpacingTokens.Micro),
                    )
                }
                item {
                    ExternalLinkCard(
                        title = stringResource(R.string.about_upstream_repo),
                        subtitle = stringResource(R.string.about_original_project),
                        icon = Icons.Default.Code,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { openDestination(AboutDestination.UPSTREAM_REPOSITORY) },
                    )
                }
                item {
                    ExternalLinkCard(
                        title = stringResource(R.string.about_maintainer),
                        subtitle = stringResource(R.string.about_module_maintainer),
                        icon = Icons.Default.Code,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { openDestination(AboutDestination.MAINTAINER) },
                    )
                }
                item {
                    ExternalLinkCard(
                        title = stringResource(R.string.about_license),
                        subtitle = stringResource(R.string.about_license_details),
                        icon = Icons.Default.Code,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { openDestination(AboutDestination.LICENSING) },
                    )
                }
            }
        }
    }
}

private enum class AboutDestination(val httpsUrl: String) {
    TELEGRAM_GROUP("https://t.me/bypassblock"),
    VPN_BOT("https://t.me/zapretvpns_bot"),
    UPSTREAM_REPOSITORY("https://github.com/bol-van/zapret"),
    MAINTAINER("https://github.com/youtubediscord"),
    LICENSING("https://github.com/youtubediscord/magisk-zapret2/blob/main/docs/LICENSING.md"),
}

@Composable
private fun AboutHero() {
    ContentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(SpacingTokens.Large)
                        .size(SizeTokens.IconLarge),
                )
            }
            Spacer(Modifier.width(SpacingTokens.CardContent))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(SpacingTokens.CardContent))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(SpacingTokens.Large))
        Text(
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExternalLinkCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
) {
    val openLabel = stringResource(R.string.about_open_link, title)
    ContentCard(
        modifier = Modifier
            .heightIn(min = SizeTokens.MinimumTouchTarget)
            .clickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = onClick,
            ),
        contentPadding = PaddingValues(horizontal = SpacingTokens.CardContent, vertical = SpacingTokens.Large),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = iconTint.copy(alpha = 0.14f),
                contentColor = iconTint,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(SpacingTokens.Medium)
                        .size(SizeTokens.IconMedium),
                )
            }
            Spacer(Modifier.width(SpacingTokens.Large))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(SpacingTokens.Medium))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
