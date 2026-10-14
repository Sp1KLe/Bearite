package com.bearite.patcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(this)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BeariteScreen(onLaunchGame = { runGameProcess() })
                }
            }
        }
    }

    private fun runGameProcess() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.EarthkwakGames.SuperBearAdventure")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.EarthkwakGames.SuperBearAdventure"))
            startActivity(intent)
        }
    }
}

data class Mod(val title: String, val author: String, var isEnabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeariteScreen(onLaunchGame: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    val installedMods = remember {
        mutableStateListOf(
            Mod("Infinite Coins", "BeariteTeam", true),
            Mod("Fly & Speed Mod", "DevBear", false),
            Mod("Custom Skin Pack", "Community", true)
        )
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Bearite Patcher") },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        kotlinx.coroutines.delay(2500)
                        isProcessing = false
                        onLaunchGame()
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(if (isProcessing) "Инжектирование..." else "Запустить Bear Adventure")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(installedMods) { mod ->
                        ModItemCard(mod)
                    }
                }
            }
        }
    }
}

@Composable
fun ModItemCard(mod: Mod) {
    var state by remember { mutableStateOf(mod.isEnabled) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(mod.title, style = MaterialTheme.typography.titleMedium)
                Text("Автор: ${mod.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state, onCheckedChange = { state = it; mod.isEnabled = it })
        }
    }
}
