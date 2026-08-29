package com.lenix.pocketvm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenix.pocketvm.data.VmInstance
import com.lenix.pocketvm.data.VmStatus
import com.lenix.pocketvm.ui.theme.LenixTheme
import kotlinx.coroutines.launch

// Simple in-app screens that the home button actions navigate to.
private enum class Screen(val title: String) {
    HOME("Lenix"),
    CREATE_INSTANCE("Create Instance"),
    START_LINUX("Start Linux"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LenixTheme {
                LenixApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LenixApp() {
    // In-memory instance store (a real implementation would persist to disk).
    val instances = remember { mutableStateListOf<VmInstance>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var activeScreen by rememberSaveable { mutableStateOf(Screen.HOME.ordinal) }

    val screen = Screen.entries[activeScreen]

    fun navigateTo(target: Screen) {
        activeScreen = target.ordinal
    }

    // Android system back (and hardware back key) leaves sub-screens.
    BackHandler(enabled = screen != Screen.HOME) {
        navigateTo(Screen.HOME)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { navigateTo(Screen.HOME) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onCreate = { navigateTo(Screen.CREATE_INSTANCE) },
                    onStartLinux = { navigateTo(Screen.START_LINUX) },
                    onSettings = { navigateTo(Screen.SETTINGS) }
                )

                Screen.CREATE_INSTANCE -> CreateInstanceScreen(
                    onCreated = { instance ->
                        instances.add(instance)
                        scope.launch {
                            snackbarHostState.showSnackbar("Instance \"${instance.name}\" created")
                        }
                        navigateTo(Screen.START_LINUX)
                    }
                )

                Screen.START_LINUX -> InstancesScreen(
                    instances = instances,
                    onCreate = { navigateTo(Screen.CREATE_INSTANCE) },
                    onToggle = { instance ->
                        val index = instances.indexOfFirst { it.id == instance.id }
                        if (index >= 0) {
                            val newStatus = if (instance.status == VmStatus.RUNNING) {
                                VmStatus.STOPPED
                            } else {
                                VmStatus.RUNNING
                            }
                            instances[index] = instance.copy(status = newStatus)
                        }
                    }
                )

                Screen.SETTINGS -> SettingsScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home screen
// ---------------------------------------------------------------------------

private data class HomeAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeScreen(
    onCreate: () -> Unit,
    onStartLinux: () -> Unit,
    onSettings: () -> Unit
) {
    val scrollState = rememberScrollState()
    var activeIndex by rememberSaveable { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val actions = listOf(
        HomeAction(Icons.Default.Add, "Create Instance", "Set up a new Linux environment", onCreate),
        HomeAction(Icons.Default.PlayArrow, "Start Linux", "Launch your running instance", onStartLinux),
        HomeAction(Icons.Default.Settings, "Settings", "Configure Lenix preferences", onSettings)
    )

    // Focus the home container so a physical keyboard / D-pad gets key events.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionRight -> {
                            activeIndex = (activeIndex + 1) % actions.size
                            true
                        }
                        Key.DirectionUp, Key.DirectionLeft -> {
                            activeIndex = (activeIndex - 1 + actions.size) % actions.size
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            actions[activeIndex].onClick()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Logo area
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App name
            Text(
                text = "LENIX",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Pocket Linux Environment",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Main action buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    ActionButton(
                        icon = action.icon,
                        title = action.title,
                        subtitle = action.subtitle,
                        active = index == activeIndex,
                        onClick = {
                            activeIndex = index
                            action.onClick()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Version info
            Text(
                text = "Lenix v1.0.0 • Linux Runtime",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = if (active) {
        CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        CardDefaults.outlinedCardColors()
    }
    val border = if (active) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(0.dp, Color.Transparent)
    }

    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        border = border
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Create Instance screen
// ---------------------------------------------------------------------------

@Composable
private fun CreateInstanceScreen(
    onCreated: (VmInstance) -> Unit
) {
    val distros = listOf("Ubuntu 24.04", "Debian 12", "Alpine 3.19", "Fedora 39")
    var name by rememberSaveable { mutableStateOf("") }
    var distroIndex by rememberSaveable { mutableStateOf(0) }
    var memoryMb by rememberSaveable { mutableStateOf(2048) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Instance name") },
            placeholder = { Text("e.g. Dev Ubuntu") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Distribution",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        distros.forEachIndexed { index, distro ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { distroIndex = index }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = distroIndex == index,
                    onClick = { distroIndex = index }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(distro)
            }
        }

        Text(
            text = "Memory: $memoryMb MB",
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = memoryMb.toFloat(),
            onValueChange = { memoryMb = it.toInt() },
            valueRange = 512f..4096f,
            steps = 6
        )

        Button(
            onClick = {
                val resolvedName = name.ifBlank {
                    "Lenix-${System.currentTimeMillis() % 10000}"
                }
                onCreated(
                    VmInstance(
                        id = "inst-${System.currentTimeMillis()}",
                        name = resolvedName,
                        distro = distros[distroIndex],
                        version = "1.0",
                        architecture = "arm64",
                        status = VmStatus.STOPPED,
                        memoryMB = memoryMb
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create Instance")
        }
    }
}

// ---------------------------------------------------------------------------
// Start Linux / instances list screen
// ---------------------------------------------------------------------------

@Composable
private fun InstancesScreen(
    instances: List<VmInstance>,
    onCreate: () -> Unit,
    onToggle: (VmInstance) -> Unit
) {
    if (instances.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No instances yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Create your first Linux environment to get started",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onCreate) {
                Text("Create Instance")
            }
        }
    } else {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(instances, key = { it.id }) { instance ->
                InstanceCard(
                    instance = instance,
                    onToggle = { onToggle(instance) }
                )
            }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: VmInstance,
    onToggle: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${instance.distro} • ${instance.memoryMB} MB",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusLabel(instance.status),
                    fontSize = 12.sp,
                    color = statusColor(instance.status)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onToggle) {
                Text(if (instance.status == VmStatus.RUNNING) "Stop" else "Start")
            }
        }
    }
}

private fun statusLabel(status: VmStatus): String = when (status) {
    VmStatus.NOT_INSTALLED -> "Not installed"
    VmStatus.INSTALLING -> "Installing"
    VmStatus.STOPPED -> "Stopped"
    VmStatus.STARTING -> "Starting"
    VmStatus.RUNNING -> "Running"
    VmStatus.ERROR -> "Error"
}

@Composable
private fun statusColor(status: VmStatus): Color = when (status) {
    VmStatus.RUNNING -> Color(0xFF00C853)
    VmStatus.STARTING, VmStatus.INSTALLING -> Color(0xFFFFB300)
    VmStatus.ERROR -> Color(0xFFD32F2F)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

@Composable
private fun SettingsScreen() {
    var autoStart by rememberSaveable { mutableStateOf(true) }
    var notifications by rememberSaveable { mutableStateOf(false) }
    var keepScreenOn by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingSection(title = "General")
        SwitchRow(
            icon = Icons.Default.PowerSettingsNew,
            title = "Auto-start on boot",
            subtitle = "Launch the last running instance automatically",
            checked = autoStart,
            onCheckedChange = { autoStart = it }
        )
        SwitchRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            subtitle = "Show status notifications for running Linux",
            checked = notifications,
            onCheckedChange = { notifications = it }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingSection(title = "Desktop")
        SwitchRow(
            icon = Icons.Default.BrightnessHigh,
            title = "Keep screen on",
            subtitle = "Prevent the screen from sleeping while Linux is running",
            checked = keepScreenOn,
            onCheckedChange = { keepScreenOn = it }
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Lenix v1.0.0 • Linux Runtime",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
