package com.winschneid.adblocker.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winschneid.adblocker.R
import com.winschneid.adblocker.core.filter.BlocklistSource
import com.winschneid.adblocker.ui.MainViewModel

private const val RULE_DIALOG_NONE = 0
private const val RULE_DIALOG_DENY = 1
private const val RULE_DIALOG_ALLOW = 2

@Composable
fun ListsScreen(viewModel: MainViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val allowRules by viewModel.userAllow.collectAsStateWithLifecycle()
    val denyRules by viewModel.userDeny.collectAsStateWithLifecycle()

    var showAddSource by rememberSaveable { mutableStateOf(false) }
    var ruleDialog by rememberSaveable { mutableIntStateOf(RULE_DIALOG_NONE) }
    var sourceToDelete by remember { mutableStateOf<BlocklistSource?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(title = stringResource(R.string.lists_title_sources)) {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                IconButton(onClick = { viewModel.updateAllLists() }, enabled = !updating) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_update_all))
                }
                IconButton(onClick = { showAddSource = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lists_add_source))
                }
            }
        }
        items(sources, key = { it.id }) { source ->
            SourceCard(
                source = source,
                updating = updating,
                onToggle = { enabled -> viewModel.setSourceEnabled(source.id, enabled) },
                onUpdate = { viewModel.updateList(source.id) },
                onDelete = if (source.builtIn) null else ({ sourceToDelete = source }),
            )
        }
        item {
            Text(
                text = stringResource(R.string.lists_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(title = stringResource(R.string.rules_deny_title)) {
                IconButton(onClick = { ruleDialog = RULE_DIALOG_DENY }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rules_add_domain))
                }
            }
        }
        ruleItems(rules = denyRules, keyPrefix = "deny", onRemove = { viewModel.removeRule(it, allow = false) })

        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(title = stringResource(R.string.rules_allow_title)) {
                IconButton(onClick = { ruleDialog = RULE_DIALOG_ALLOW }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rules_add_domain))
                }
            }
        }
        ruleItems(rules = allowRules, keyPrefix = "allow", onRemove = { viewModel.removeRule(it, allow = true) })

        item {
            Text(
                text = stringResource(R.string.rules_subdomain_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onAdd = { name, url -> if (viewModel.addSource(name, url)) showAddSource = false },
        )
    }

    if (ruleDialog != RULE_DIALOG_NONE) {
        val allow = ruleDialog == RULE_DIALOG_ALLOW
        AddDomainDialog(
            title = stringResource(if (allow) R.string.rules_allow_title else R.string.rules_deny_title),
            onDismiss = { ruleDialog = RULE_DIALOG_NONE },
            onAdd = { domain -> if (viewModel.addRule(domain, allow)) ruleDialog = RULE_DIALOG_NONE },
        )
    }

    sourceToDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text(source.name) },
            text = { Text(source.url) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSource(source.id)
                    sourceToDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun LazyListScope.ruleItems(rules: Set<String>, keyPrefix: String, onRemove: (String) -> Unit) {
    if (rules.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.rules_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        return
    }
    items(rules.toList(), key = { "$keyPrefix-$it" }) { domain ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = domain, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onRemove(domain) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun SourceCard(
    source: BlocklistSource,
    updating: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val statusText = when {
        source.lastError != null -> stringResource(R.string.lists_error, source.lastError ?: "")
        source.lastUpdated == 0L -> stringResource(R.string.lists_never_updated)
        else -> {
            val relative = DateUtils.getRelativeTimeSpanString(
                source.lastUpdated,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            stringResource(R.string.lists_entries, source.entryCount) + " · " + stringResource(R.string.lists_updated_at, relative)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = source.name, style = MaterialTheme.typography.titleMedium)
                    if (source.description.isNotEmpty()) {
                        Text(
                            text = source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (source.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onUpdate, enabled = !updating) { Text(stringResource(R.string.action_update)) }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lists_add_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.lists_source_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.lists_source_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.lists_formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AddDomainDialog(title: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var domain by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.rules_domain_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.rules_subdomain_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(domain) }, enabled = domain.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
