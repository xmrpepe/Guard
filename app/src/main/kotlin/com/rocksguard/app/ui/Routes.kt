package com.rocksguard.app.ui

import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rocksguard.app.AppGraph
import com.rocksguard.app.BuildConfig
import com.rocksguard.app.data.ActivityEntry
import com.rocksguard.app.system.ListUpdateJob
import com.rocksguard.app.ui.screens.ArmouryScreen
import com.rocksguard.app.ui.screens.CheckRow
import com.rocksguard.app.ui.screens.FortressScreen
import com.rocksguard.app.ui.screens.FortressUi
import com.rocksguard.app.ui.screens.KeepScreen
import com.rocksguard.app.ui.screens.LookoutScreen
import com.rocksguard.app.ui.screens.RampartsScreen
import com.rocksguard.app.ui.screens.ShieldScreen
import com.rocksguard.app.ui.screens.WatchtowerScreen
import com.rocksguard.app.ui.screens.WorkshopScreen
import com.rocksguard.app.ui.screens.normaliseRule
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.vpn.GuardController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@Composable
fun Route(dest: Dest, graph: AppGraph, navigate: (Dest) -> Unit, toggle: () -> Unit) {
    when (dest) {
        Dest.FORTRESS -> FortressRoute(graph, navigate, toggle)
        Dest.LOOKOUT -> LookoutRoute(graph, navigate)
        Dest.RAMPARTS -> RampartsRoute(graph)
        Dest.WATCHTOWER -> WatchtowerRoute(graph)
        Dest.SHIELD -> ShieldRoute(graph)
        Dest.ARMOURY -> ArmouryRoute(graph)
        Dest.KEEP -> KeepRoute(graph)
        Dest.WORKSHOP -> WorkshopRoute(graph, navigate)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FortressRoute(graph: AppGraph, navigate: (Dest) -> Unit, toggle: () -> Unit) {
    val context = LocalContext.current
    val guard by GuardController.state.collectAsStateWithLifecycle()
    val stats by graph.stats.flow.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val compile by graph.filters.compileState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Check>?>(null) }
    // Pulse the shield when a threat is repelled, at most once every 1.5 s.
    var pulseKey by remember { mutableLongStateOf(0L) }
    var lastThreats by remember { mutableLongStateOf(stats.today.threats) }
    var lastPulse by remember { mutableLongStateOf(0L) }
    LaunchedEffect(stats.today.threats) {
        val now = System.currentTimeMillis()
        if (stats.today.threats > lastThreats && now - lastPulse > 1_500) {
            pulseKey++; lastPulse = now
        }
        lastThreats = stats.today.threats
    }
    FortressScreen(
        ui = FortressUi(guard, stats, settings, compile.totalDomains),
        scanning = scanning,
        pulseKey = pulseKey,
        onToggle = toggle,
        onInspect = {
            scope.launch {
                scanning = true
                val start = System.currentTimeMillis()
                val r = inspectFortress(context, graph)
                // Let the radar finish one sweep so the result does not flash past.
                delay((1_600 - (System.currentTimeMillis() - start)).coerceAtLeast(0))
                scanning = false
                results = r
            }
        },
        onOpen = navigate,
        onWidgets = { w -> graph.settings.update { it.copy(widgets = w) } },
    )
    results?.let { checks ->
        val c = Rg.colors
        ModalBottomSheet(onDismissRequest = { results = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("FORTRESS INSPECTION", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                val problems = checks.count { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }
                Text(
                    if (problems == 0) "Fortress Secured" else "$problems matter${if (problems == 1) "" else "s"} need attention",
                    style = MaterialTheme.typography.headlineSmall, color = if (problems == 0) c.protectionText else c.premiumText,
                )
                Spacer(Modifier.height(8.dp))
                checks.forEach { CheckRow(it) { ch -> launchCheck(context, ch) } }
            }
        }
    }
}

private fun launchCheck(context: android.content.Context, check: Check) {
    try {
        check.action?.let { context.startActivity(it) }
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "That settings page is not available on this device.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun LookoutRoute(graph: AppGraph, navigate: (Dest) -> Unit) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var paused by remember { mutableStateOf(false) }
    // Sample rather than recompose on every lookup: at most ~1.3 list refreshes a second.
    val version by remember { graph.activity.version.sample(750) }.collectAsStateWithLifecycle(-1L)
    var entries by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    LaunchedEffect(version, paused) { if (!paused || entries.isEmpty()) entries = graph.activity.snapshot() }
    val labels = remember { HashMap<Int, String?>() }
    LookoutScreen(
        entries = entries,
        recording = settings.recordActivity,
        paused = paused,
        appLabel = { uid -> if (uid < 0) null else labels.getOrPut(uid) { graph.apps.labelForUid(uid) } },
        onPause = { paused = it },
        onClear = { graph.activity.clear(); entries = emptyList() },
        onAllow = { d -> graph.settings.update { it.copy(userAllowed = it.userAllowed + d, userBlocked = it.userBlocked - d) } },
        onBlock = { d -> graph.settings.update { it.copy(userBlocked = it.userBlocked + d, userAllowed = it.userAllowed - d) } },
        onCopy = { clipboard.setText(AnnotatedString(it)) },
        onOpen = navigate,
    )
}

@Composable
private fun RampartsRoute(graph: AppGraph) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val rules by graph.rules.rules.collectAsStateWithLifecycle()
    val apps by produceState<List<com.rocksguard.app.data.InstalledApp>?>(null) { value = graph.apps.installed() }
    RampartsScreen(
        apps = apps,
        rules = rules,
        gateMode = settings.gateMode,
        perAppDnsSupported = Build.VERSION.SDK_INT >= 29,
        iconLoader = graph.apps::icon,
        onGateMode = { m -> graph.settings.update { it.copy(gateMode = m) } },
        onRule = { pkg, t -> graph.rules.update(pkg, t) },
    )
}

@Composable
private fun WatchtowerRoute(graph: AppGraph) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val guard by GuardController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var measured by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var measuring by remember { mutableStateOf(false) }
    WatchtowerScreen(
        settings = settings,
        guard = guard,
        measured = measured,
        measuring = measuring,
        onMeasure = {
            scope.launch {
                measuring = true
                measured = measureAll(settings.protocol)
                measuring = false
            }
        },
        onProvider = { id -> graph.settings.update { it.copy(providerId = id, fallbackId = if (it.fallbackId == id) null else it.fallbackId) } },
        onFallback = { id -> graph.settings.update { it.copy(fallbackId = id) } },
        onProtocol = { p -> graph.settings.update { it.copy(protocol = p) }; measured = emptyMap() },
        onPlaintextFallback = { v -> graph.settings.update { it.copy(plaintextFallback = v) } },
    )
}

@Composable
private fun ShieldRoute(graph: AppGraph) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val stats by graph.stats.flow.collectAsStateWithLifecycle()
    ShieldScreen(
        settings = settings,
        today = stats.today,
        onCategory = { cat, on -> graph.settings.update { it.copy(categories = if (on) it.categories + cat else it.categories - cat) } },
        onCname = { v -> graph.settings.update { it.copy(cnameUncloaking = v) } },
        onCanary = { v -> graph.settings.update { it.copy(bypassCanary = v) } },
        onBlockResponse = { r -> graph.settings.update { it.copy(blockResponse = r) } },
        onAddRule = { input, block ->
            val d = normaliseRule(input) ?: return@ShieldScreen "Enter a domain such as example.com."
            graph.settings.update {
                if (block) it.copy(userBlocked = it.userBlocked + d, userAllowed = it.userAllowed - d)
                else it.copy(userAllowed = it.userAllowed + d, userBlocked = it.userBlocked - d)
            }
            null
        },
        onRemoveRule = { d, block ->
            graph.settings.update { if (block) it.copy(userBlocked = it.userBlocked - d) else it.copy(userAllowed = it.userAllowed - d) }
        },
    )
}

@Composable
private fun ArmouryRoute(graph: AppGraph) {
    val lists by graph.filters.lists.collectAsStateWithLifecycle()
    val compile by graph.filters.compileState.collectAsStateWithLifecycle()
    ArmouryScreen(
        lists = lists,
        compile = compile,
        onToggle = graph.filters::setEnabled,
        onUpdate = { graph.filters.updateAllAsync() },
        onAddCustom = graph.filters::addCustom,
        onRemoveCustom = graph.filters::removeCustom,
    )
}

@Composable
private fun KeepRoute(graph: AppGraph) {
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val stats by graph.stats.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    val run = {
        scope.launch {
            inspecting = true
            checks = inspectFortress(context, graph)
            inspecting = false
        }
    }
    LaunchedEffect(Unit) { run() }
    KeepScreen(
        checks = checks,
        inspecting = inspecting,
        stats = stats,
        settings = settings,
        appLabel = graph.apps::labelForUid,
        onInspect = { run() },
        onAction = { launchCheck(context, it) },
        onClearActivity = { graph.activity.clear() },
        onResetStats = { graph.stats.reset() },
        onRecord = { v -> graph.settings.update { it.copy(recordActivity = v) }; if (!v) graph.activity.clear() },
        onSecureScreen = { v -> graph.settings.update { it.copy(secureScreen = v) } },
    )
}

@Composable
private fun WorkshopRoute(graph: AppGraph, navigate: (Dest) -> Unit) {
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    WorkshopScreen(
        settings = settings,
        version = BuildConfig.VERSION_NAME,
        onUpdate = { t ->
            graph.settings.update(t)
            ListUpdateJob.schedule(context, graph.settings.value)
        },
        onArrange = { navigate(Dest.FORTRESS) },
    )
}
