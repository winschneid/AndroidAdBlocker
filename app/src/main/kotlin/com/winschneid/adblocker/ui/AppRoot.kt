package com.winschneid.adblocker.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winschneid.adblocker.R
import com.winschneid.adblocker.ui.screens.HomeScreen
import com.winschneid.adblocker.ui.screens.ListsScreen
import com.winschneid.adblocker.ui.screens.LogScreen
import com.winschneid.adblocker.ui.screens.SettingsScreen

enum class Destination(@StringRes val labelRes: Int) {
    HOME(R.string.nav_home),
    LISTS(R.string.nav_lists),
    LOG(R.string.nav_log),
    SETTINGS(R.string.nav_settings),
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.HOME -> Icons.Filled.Home
    Destination.LISTS -> Icons.AutoMirrored.Filled.List
    Destination.LOG -> Icons.Filled.Info
    Destination.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(viewModel: MainViewModel, onRequestStart: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(context.getString(message.resId, *message.args.toTypedArray()))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon(), contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (destination) {
                Destination.HOME -> HomeScreen(viewModel, onRequestStart)
                Destination.LISTS -> ListsScreen(viewModel)
                Destination.LOG -> LogScreen(viewModel)
                Destination.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
