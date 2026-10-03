package app.lawnchair.ui.preferences.destinations

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lawnchair.oea.callblocker.OeaCallBlockRules
import app.lawnchair.oea.gameboost.OeaGameBoostService
import app.lawnchair.oea.gameboost.OeaGameBoostStore
import app.lawnchair.oea.split.OeaSplitLauncher
import com.android.launcher3.R

@Composable
fun OeaSystemsPreferences(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var callEnabled by remember { mutableStateOf(OeaCallBlockRules.enabled(context)) }
    var exactText by remember { mutableStateOf(OeaCallBlockRules.getExact(context).firstOrNull().orEmpty()) }
    var prefixText by remember { mutableStateOf(OeaCallBlockRules.getPrefix(context).firstOrNull().orEmpty()) }
    var suffixText by remember { mutableStateOf(OeaCallBlockRules.getSuffix(context).firstOrNull().orEmpty()) }
    var allowContacts by remember { mutableStateOf(OeaCallBlockRules.allowContacts(context)) }
    var allowStarred by remember { mutableStateOf(OeaCallBlockRules.allowStarred(context)) }
    var gameEnabled by remember { mutableStateOf(OeaGameBoostStore.enabled(context)) }
    var games by remember { mutableStateOf(OeaGameBoostStore.games(context)) }
    var dnd by remember { mutableStateOf(OeaGameBoostStore.prefs(context).getBoolean("dnd", true)) }
    var firstPackage by remember { mutableStateOf(games.firstOrNull().orEmpty()) }
    var secondPackage by remember { mutableStateOf(games.drop(1).firstOrNull().orEmpty()) }

    fun openSettings(action: String) = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun toggleGame(packageName: String) {
        if (packageName.isBlank()) return
        val updated = games.toMutableSet()
        if (!updated.add(packageName)) updated.remove(packageName)
        games = updated
        OeaGameBoostStore.setGames(context, updated)
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("OEA Systems", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Call blocker", style = MaterialTheme.typography.titleLarge)
                    Text("Exact numbers plus 1–5 digit prefix/suffix rules. Contacts and starred contacts can be exceptions.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Blocking enabled")
                        Switch(checked = callEnabled, onCheckedChange = { callEnabled = it; OeaCallBlockRules.setEnabled(context, it) })
                    }
                    OutlinedTextField(exactText, { exactText = it.filter(Char::isDigit) }, label = { Text("Exact number") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(prefixText, { prefixText = it.filter(Char::isDigit).take(5) }, label = { Text("Prefix (max 5 digits)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(suffixText, { suffixText = it.filter(Char::isDigit).take(5) }, label = { Text("Suffix (max 5 digits)") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Allow contacts")
                        Switch(checked = allowContacts, onCheckedChange = { allowContacts = it; OeaCallBlockRules.setAllowContacts(context, it) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Allow starred/favorites")
                        Switch(checked = allowStarred, onCheckedChange = { allowStarred = it; OeaCallBlockRules.setAllowStarred(context, it) })
                    }
                    Button(onClick = { OeaCallBlockRules.setRules(context, setOf(exactText), setOf(prefixText), setOf(suffixText)) }) {
                        Text("Save blocking rules")
                    }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val rm = context.getSystemService(RoleManager::class.java)
                            if (rm?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                                context.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                            }
                        }
                    }) { Text("Enable OEA call-screening role") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Split screen", style = MaterialTheme.typography.titleLarge)
                    Text("Uses Android adjacent-window launching. OEA already contains Launcher3 split-selection support; this adds an OEA pair launcher.")
                    OutlinedTextField(firstPackage, { firstPackage = it }, label = { Text("First package name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secondPackage, { secondPackage = it }, label = { Text("Second package name") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { OeaSplitLauncher.launchPair(context, firstPackage.trim(), secondPackage.trim()) }) {
                        Text("Launch pair in split screen")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Game Boost", style = MaterialTheme.typography.titleLarge)
                    Text("Auto-detects selected games and activates while they are foreground. Other apps are not closed.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Automatic Game Boost")
                        Switch(checked = gameEnabled, onCheckedChange = {
                            gameEnabled = it
                            OeaGameBoostStore.setEnabled(context, it)
                            if (it) {
                                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                runCatching { context.startForegroundService(Intent(context, OeaGameBoostService::class.java)) }
                            } else context.stopService(Intent(context, OeaGameBoostService::class.java))
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("DND during games")
                        Switch(checked = dnd, onCheckedChange = { dnd = it; OeaGameBoostStore.prefs(context).edit().putBoolean("dnd", it).apply() })
                    }
                    Text("Selected games: " + games.size)
                    Text("RAM focus: OEA monitors RAM pressure but does not kill or force-close other apps. Android does not expose a normal-app API that reallocates another app's RAM.")
                    Button(onClick = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("Grant usage access") }
                    Button(onClick = { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION + "?package=" + context.packageName) }) { Text("Allow game overlay") }
                    Button(onClick = { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) { Text("Allow DND access") }
                    OutlinedTextField(firstPackage, { firstPackage = it }, label = { Text("Game package") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { toggleGame(firstPackage.trim()) }) { Text("Add/remove game") }
                }
            }
        }
    }
}
