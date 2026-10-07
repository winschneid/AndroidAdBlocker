package com.winschneid.adblocker.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winschneid.adblocker.R
import com.winschneid.adblocker.core.dns.BlockResponseMode
import com.winschneid.adblocker.data.UpstreamDns
import com.winschneid.adblocker.ui.MainViewModel

private const val SOURCE_URL = "https://github.com/winschneid/AndroidAdBlocker"

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_upstream_title))
        UpstreamDns.entries.forEach { option ->
            RadioRow(
                selected = settings.upstream == option,
                label = stringResource(option.labelRes()),
                onClick = { viewModel.updateSettings { it.copy(upstream = option) } },
            )
        }
        if (settings.upstream == UpstreamDns.CUSTOM) {
            OutlinedTextField(
                value = settings.customUpstream,
                onValueChange = { value -> viewModel.updateSettings { it.copy(customUpstream = value) } },
                label = { Text(stringResource(R.string.settings_upstream_custom_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_block_mode_title))
        RadioRow(
            selected = settings.blockMode == BlockResponseMode.NXDOMAIN,
            label = stringResource(R.string.settings_block_mode_nxdomain),
            onClick = { viewModel.updateSettings { it.copy(blockMode = BlockResponseMode.NXDOMAIN) } },
        )
        RadioRow(
            selected = settings.blockMode == BlockResponseMode.ZERO_IP,
            label = stringResource(R.string.settings_block_mode_zero),
            onClick = { viewModel.updateSettings { it.copy(blockMode = BlockResponseMode.ZERO_IP) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_behavior_title))
        SwitchRow(
            title = stringResource(R.string.settings_ipv6),
            subtitle = stringResource(R.string.settings_ipv6_summary),
            checked = settings.ipv6Enabled,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(ipv6Enabled = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_autostart),
            checked = settings.autoStartOnBoot,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(autoStartOnBoot = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_autoupdate),
            checked = settings.autoUpdateLists,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(autoUpdateLists = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_querylog),
            checked = settings.queryLogEnabled,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(queryLogEnabled = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_builtin),
            checked = settings.useBuiltinList,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(useBuiltinList = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_block_aliases),
            subtitle = stringResource(R.string.settings_block_aliases_summary),
            checked = settings.blockAliases,
            onCheckedChange = { value -> viewModel.updateSettings { it.copy(blockAliases = value) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_about_title))
        Text(
            text = stringResource(R.string.settings_about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_about_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.settings_about_limitations),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
            Text(stringResource(R.string.settings_source_code))
        }
    }
}

private fun UpstreamDns.labelRes(): Int = when (this) {
    UpstreamDns.SYSTEM -> R.string.settings_upstream_system
    UpstreamDns.CLOUDFLARE -> R.string.settings_upstream_cloudflare
    UpstreamDns.GOOGLE -> R.string.settings_upstream_google
    UpstreamDns.QUAD9 -> R.string.settings_upstream_quad9
    UpstreamDns.ADGUARD -> R.string.settings_upstream_adguard
    UpstreamDns.CUSTOM -> R.string.settings_upstream_custom
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
