package com.winschneid.adblocker.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winschneid.adblocker.R
import com.winschneid.adblocker.ui.MainViewModel
import com.winschneid.adblocker.vpn.VpnStats
import com.winschneid.adblocker.vpn.VpnStatus

@Composable
fun HomeScreen(viewModel: MainViewModel, onRequestStart: () -> Unit) {
    val status by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val error by viewModel.vpnError.collectAsStateWithLifecycle()
    val blockedDomains by viewModel.blockedDomainCount.collectAsStateWithLifecycle()
    val privateDnsMode by viewModel.privateDnsMode.collectAsStateWithLifecycle()
    val hasLists by viewModel.hasDownloadedLists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = status != VpnStatus.STOPPED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        StatusCard(status = status, onToggle = { if (active) viewModel.stopVpn() else onRequestStart() })

        error?.let { message ->
            MessageCard(
                title = stringResource(R.string.status_error),
                text = stringResource(R.string.error_vpn_failed, message),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        StatsCard(stats = stats, blockedDomains = blockedDomains)

        if (privateDnsMode == "hostname") {
            MessageCard(
                title = stringResource(R.string.warning_private_dns_title),
                text = stringResource(R.string.warning_private_dns),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = {
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: ActivityNotFoundException) {
                        // No settings screen available on this device.
                    }
                },
            )
        }

        if (!hasLists) {
            MessageCard(
                title = null,
                text = stringResource(R.string.home_lists_not_downloaded),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        MessageCard(
            title = stringResource(R.string.info_chrome_title),
            text = stringResource(R.string.info_chrome),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.home_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(status: VpnStatus, onToggle: () -> Unit) {
    val active = status != VpnStatus.STOPPED
    val containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val statusText = when (status) {
        VpnStatus.RUNNING -> R.string.status_running
        VpnStatus.STARTING -> R.string.status_starting
        VpnStatus.STOPPED -> R.string.status_stopped
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(statusText),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = active,
                onCheckedChange = { onToggle() },
                enabled = status != VpnStatus.STARTING,
            )
        }
        Button(
            onClick = onToggle,
            enabled = status != VpnStatus.STARTING,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(stringResource(if (active) R.string.action_stop else R.string.action_start))
        }
    }
}

@Composable
private fun StatsCard(stats: VpnStats, blockedDomains: Int) {
    val rate = if (stats.totalQueries > 0) (stats.blockedQueries * 100 / stats.totalQueries).toInt() else 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatColumn(value = stats.blockedQueries.toString(), label = stringResource(R.string.stats_blocked))
            StatColumn(value = stats.totalQueries.toString(), label = stringResource(R.string.stats_total))
            StatColumn(value = blockedDomains.toString(), label = stringResource(R.string.stats_entries))
        }
        Text(
            text = stringResource(R.string.stats_block_rate, rate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MessageCard(
    title: String?,
    text: String,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
