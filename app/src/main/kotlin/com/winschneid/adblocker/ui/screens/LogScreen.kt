package com.winschneid.adblocker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winschneid.adblocker.R
import com.winschneid.adblocker.core.dns.DnsType
import com.winschneid.adblocker.core.filter.Decision
import com.winschneid.adblocker.ui.MainViewModel
import com.winschneid.adblocker.vpn.QueryLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(viewModel: MainViewModel) {
    val entries by viewModel.log.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<QueryLogEntry?>(null) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val shown = if (blockedOnly) entries.filter { it.decision.blocked } else entries

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !blockedOnly,
                onClick = { blockedOnly = false },
                label = { Text(stringResource(R.string.log_filter_all)) },
            )
            FilterChip(
                selected = blockedOnly,
                onClick = { blockedOnly = true },
                label = { Text(stringResource(R.string.log_filter_blocked)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.log_count, shown.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { viewModel.clearLog() }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_clear))
            }
        }

        if (!settings.queryLogEnabled) {
            Text(
                text = stringResource(R.string.log_disabled),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown) { entry ->
                    LogRow(entry = entry, time = timeFormat.format(Date(entry.time)), onClick = { selected = entry })
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.host) },
            text = {
                val summary = DnsType.name(entry.type) + " · " + stringResource(entry.decision.labelRes())
                val alias = entry.blockedAlias
                Text(if (alias == null) summary else summary + "\n" + stringResource(R.string.log_cname_target, alias))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addRule(entry.host, allow = true)
                    selected = null
                }) { Text(stringResource(R.string.action_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.addRule(entry.host, allow = false)
                    selected = null
                }) { Text(stringResource(R.string.action_deny)) }
            },
        )
    }
}

private fun Decision.labelRes(): Int = when (this) {
    Decision.ALLOWED -> R.string.log_allowed
    Decision.ALLOWED_BY_USER -> R.string.log_allowed_by_user
    Decision.BLOCKED -> R.string.log_blocked
    Decision.BLOCKED_BY_USER -> R.string.log_blocked_by_user
    Decision.BLOCKED_BY_CNAME -> R.string.log_blocked_by_cname
}

@Composable
private fun LogRow(entry: QueryLogEntry, time: String, onClick: () -> Unit) {
    val blocked = entry.decision.blocked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.host, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = DnsType.name(entry.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(entry.decision.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
