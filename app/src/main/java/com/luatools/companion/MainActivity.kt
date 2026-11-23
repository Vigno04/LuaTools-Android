package com.luatools.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.luatools.companion.bridge.ConnectionMode
import com.luatools.companion.bridge.SafValidationResult
import com.luatools.companion.luatools.SteamEmuDownloader
import com.luatools.companion.bridge.TargetAppBridge
import com.luatools.companion.bridge.TargetAppStatus
import com.luatools.companion.cloud.CloudRedirectOAuthService
import com.luatools.companion.cloud.CloudRedirectService
import com.luatools.companion.luatools.AuthService
import com.luatools.companion.luatools.DenuvoFix
import com.luatools.companion.luatools.DenuvoGameListing
import com.luatools.companion.luatools.GameUpdateStatus
import com.luatools.companion.luatools.HubcapService
import com.luatools.companion.luatools.InstalledLuaGame
import com.luatools.companion.luatools.LuaInstaller
import com.luatools.companion.luatools.LuaToolsApiClient
import com.luatools.companion.luatools.SourceAvailability
import com.luatools.companion.luatools.SteamSearchResult
import kotlinx.coroutines.launch
import java.io.File

private enum class AppStage {
    PLATFORM_SELECT,
    PERMISSIONS,
    MAIN_GUI
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LuaToolsAndroidRoot()
            }
        }
    }
}

@Composable
fun LuaToolsAndroidRoot() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val authService = remember { AuthService(context) }
    val apiClient = remember { LuaToolsApiClient(authService) }
    val hubcapService = remember { HubcapService() }
    val luaInstaller = remember { LuaInstaller(context, settings) }
    val cloudService = remember { CloudRedirectService(context) }
    val cloudOAuth = remember { CloudRedirectOAuthService(context) }

    var stage by remember {
        mutableStateOf(if (settings.onboardingDone) AppStage.MAIN_GUI else AppStage.PLATFORM_SELECT)
    }
    var selectedPlatform by remember { mutableStateOf(settings.selectedPlatform) }
    var installedBannerHubs by remember { mutableStateOf(TargetAppBridge.probeAllBannerHub(context)) }
    var targetStatus by remember {
        mutableStateOf(TargetAppBridge.probeBannerHub(context, settings.selectedPackageName))
    }

    val currentSafKey = targetStatus.packageName ?: selectedPlatform.id
    var safTreeUri by remember(currentSafKey) { mutableStateOf(settings.getSafUri(currentSafKey)) }
    var safValidation by remember(safTreeUri, targetStatus.mtAuthority) {
        mutableStateOf(
            safTreeUri?.let {
                TargetAppBridge.validateContainerSafUri(context, it, targetStatus.mtAuthority)
            }
        )
    }

    fun refreshTargetStatus() {
        installedBannerHubs = TargetAppBridge.probeAllBannerHub(context)
        targetStatus = TargetAppBridge.probeBannerHub(context, settings.selectedPackageName)
        val key = targetStatus.packageName ?: selectedPlatform.id
        safTreeUri = settings.getSafUri(key)
        safValidation = safTreeUri?.let {
            TargetAppBridge.validateContainerSafUri(context, it, targetStatus.mtAuthority)
        }
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val key = targetStatus.packageName ?: selectedPlatform.id
            settings.setSafUri(key, uri)
            safTreeUri = uri
            safValidation = TargetAppBridge.validateContainerSafUri(
                context,
                uri,
                targetStatus.mtAuthority
            )
        }
    }

    when (stage) {
        AppStage.PLATFORM_SELECT -> PlatformSelectScreen(
            selected = selectedPlatform,
            installedBannerHubs = installedBannerHubs,
            selectedPackage = targetStatus.packageName,
            onSelect = { platform, pkg ->
                selectedPlatform = platform
                settings.selectedPlatform = platform
                settings.selectedPackageName = pkg
                refreshTargetStatus()
                stage = AppStage.PERMISSIONS
            }
        )

        AppStage.PERMISSIONS -> PermissionsSetupScreen(
            platform = selectedPlatform,
            status = targetStatus,
            safUri = safTreeUri,
            safValidation = safValidation,
            onRefreshBridge = { refreshTargetStatus() },
            onGrantSaf = { safLauncher.launch(buildBannerHubInitialUri(targetStatus)) },
            onClearSaf = {
                settings.setSafUri(currentSafKey, null)
                safTreeUri = null
                safValidation = null
            },
            onBack = { stage = AppStage.PLATFORM_SELECT },
            onContinue = {
                settings.onboardingDone = true
                stage = AppStage.MAIN_GUI
            }
        )

        AppStage.MAIN_GUI -> MainLuaToolsGui(
            settings = settings,
            authService = authService,
            apiClient = apiClient,
            hubcapService = hubcapService,
            luaInstaller = luaInstaller,
            cloudService = cloudService,
            cloudOAuth = cloudOAuth,
            targetStatus = targetStatus,
            safUri = safTreeUri,
            safValidation = safValidation,
            onRefreshBridge = { refreshTargetStatus() },
            onGrantSaf = { safLauncher.launch(buildBannerHubInitialUri(targetStatus)) },
            onClearSaf = {
                settings.setSafUri(currentSafKey, null)
                safTreeUri = null
                safValidation = null
            },
            onOpenSetup = { stage = AppStage.PLATFORM_SELECT }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSelectScreen(
    selected: TargetPlatform,
    installedBannerHubs: List<TargetAppStatus>,
    selectedPackage: String?,
    onSelect: (TargetPlatform, String?) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("LuaTools Android") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Choose your target platform",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "You can have multiple emulator apps installed and switch or configure each one anytime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            if (installedBannerHubs.isNotEmpty()) {
                installedBannerHubs.forEach { bh ->
                    val isSelected = selected == TargetPlatform.BANNERHUB &&
                        (selectedPackage == null || selectedPackage == bh.packageName)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(TargetPlatform.BANNERHUB, bh.packageName) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "BannerHub (${bh.packageName})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (bh.mode == ConnectionMode.FULLY_CONNECTED)
                                        "Bridge v${bh.bridgeVersion} detected (Auto Sync)"
                                    else
                                        "Standard / Unmodified (SAF Manual Fallback)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }

            TargetPlatform.entries.forEach { platform ->
                if (platform == TargetPlatform.BANNERHUB && installedBannerHubs.isNotEmpty()) {
                    return@forEach
                }
                val isSelected = platform == selected
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = platform.supported) { onSelect(platform, null) },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            !platform.supported -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                platform.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                platform.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (platform.supported) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSetupScreen(
    platform: TargetPlatform,
    status: TargetAppStatus,
    safUri: Uri?,
    safValidation: SafValidationResult?,
    onRefreshBridge: () -> Unit,
    onGrantSaf: () -> Unit,
    onClearSaf: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasStorage by remember { mutableStateOf(hasStoragePermission(context)) }
    var detectedFolders by remember(hasStorage) {
        mutableStateOf(if (hasStorage) detectBannerHubFolders() else emptyList())
    }

    // Re-check permissions & auto-detect folders immediately when returning from Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasStoragePermission(context)
                hasStorage = granted
                detectedFolders = if (granted) detectBannerHubFolders() else emptyList()
                onRefreshBridge()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = hasStoragePermission(context)
        hasStorage = granted
        detectedFolders = if (granted) detectBannerHubFolders() else emptyList()
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasStoragePermission(context)
        hasStorage = granted
        detectedFolders = if (granted) detectBannerHubFolders() else emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${platform.displayName} Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        hasStorage = hasStoragePermission(context)
                        onRefreshBridge()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-check")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionStatusCard(status = status, safUri = safUri, onGrantSaf = onGrantSaf)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (hasStorage) Icons.Default.CheckCircle else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (hasStorage) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("1. Storage Access", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Needed to download Steam depots, .lua files, and .manifest files to shared storage.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        enabled = !hasStorage,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                allFilesLauncher.launch(intent)
                            } else {
                                legacyStorageLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            }
                        }
                    ) {
                        if (hasStorage) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Storage Access Active")
                        } else {
                            Text("Grant Storage Permission")
                        }
                    }

                    if (hasStorage) {
                        Text(
                            text = if (detectedFolders.isNotEmpty())
                                "Auto-detected external folders:\n• " + detectedFolders.joinToString("\n• ")
                            else
                                "No external /sdcard/bannerhub folder found yet (created automatically when you download store games).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val safValid = safValidation?.isValid == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                safValid -> Icons.Default.CheckCircle
                                safUri != null -> Icons.Default.Warning
                                else -> Icons.Default.SyncLock
                            },
                            contentDescription = null,
                            tint = when {
                                safValid -> Color(0xFF4CAF50)
                                safUri != null -> Color(0xFFFFB300)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("2. BannerHub Container Access (SAF)", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Why this is separate: BannerHub's Wine prefixes live in private app storage (/data/data/${status.packageName ?: "<pkg>"}/files/), which Android's All-Files permission cannot access directly on non-rooted devices.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Where to find it: In the folder picker, open the ☰ sidebar → select BannerHub (or GameHub Revanced) → open 'data' → 'files' → tap 'Use this folder'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (safValidation != null) {
                        Text(
                            text = safValidation.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (safValidation.isValid) Color(0xFF81C784) else Color(0xFFFFB74D)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onGrantSaf) {
                            if (safValid) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Change Folder")
                            } else if (safUri != null) {
                                Text("Pick Correct Folder")
                            } else {
                                Text("Select BannerHub SAF Folder")
                            }
                        }
                        if (safUri != null) {
                            TextButton(onClick = onClearSaf) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue to LuaTools Android")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLuaToolsGui(
    settings: SettingsStore,
    authService: AuthService,
    apiClient: LuaToolsApiClient,
    hubcapService: HubcapService,
    luaInstaller: LuaInstaller,
    cloudService: CloudRedirectService,
    cloudOAuth: CloudRedirectOAuthService,
    targetStatus: TargetAppStatus,
    safUri: Uri?,
    safValidation: SafValidationResult?,
    onRefreshBridge: () -> Unit,
    onGrantSaf: () -> Unit,
    onClearSaf: () -> Unit,
    onOpenSetup: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LuaTools Android") },
                actions = {
                    IconButton(onClick = onRefreshBridge) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-check Bridge")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AddCircle, contentDescription = "Add") },
                    label = { Text("Add") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Fixes") },
                    label = { Text("Fixes") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.CloudSync, contentDescription = "Cloud") },
                    label = { Text("Cloud") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Options") },
                    label = { Text("Options") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            ConnectionStatusCard(status = targetStatus, safUri = safUri, onGrantSaf = onGrantSaf)
            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> HomeTab(
                    settings = settings,
                    targetStatus = targetStatus,
                    authService = authService,
                    apiClient = apiClient,
                    luaInstaller = luaInstaller,
                    cloudService = cloudService,
                    safUri = safUri,
                    onGoToAdd = { selectedTab = 1 }
                )
                1 -> AddGamesTab(
                    settings = settings,
                    targetStatus = targetStatus,
                    apiClient = apiClient,
                    hubcapService = hubcapService,
                    luaInstaller = luaInstaller,
                    safUri = safUri
                )
                2 -> FixesTab(
                    targetStatus = targetStatus,
                    apiClient = apiClient,
                    luaInstaller = luaInstaller
                )
                3 -> CloudRedirectTab(
                    settings = settings,
                    cloudService = cloudService,
                    cloudOAuth = cloudOAuth
                )
                4 -> MergedOptionsTab(
                    settings = settings,
                    targetStatus = targetStatus,
                    safUri = safUri,
                    safValidation = safValidation,
                    hubcapService = hubcapService,
                    cloudService = cloudService,
                    onRefreshBridge = onRefreshBridge,
                    onGrantSaf = onGrantSaf,
                    onClearSaf = onClearSaf,
                    onOpenSetup = onOpenSetup
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    status: TargetAppStatus,
    safUri: Uri?,
    onGrantSaf: () -> Unit
) {
    val (bgColor, icon, title) = when (status.mode) {
        ConnectionMode.FULLY_CONNECTED -> Triple(
            Color(0xFF1B3B2B),
            Icons.Default.CheckCircle,
            "BannerHub Bridge Connected"
        )
        ConnectionMode.MANUAL_FALLBACK -> Triple(
            Color(0xFF3E2E14),
            Icons.Default.Warning,
            "Manual Fallback Mode"
        )
        ConnectionMode.NOT_INSTALLED -> Triple(
            Color(0xFF2B2B2B),
            Icons.Default.Warning,
            "BannerHub Not Detected"
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(2.dp))
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
            if (status.mode == ConnectionMode.MANUAL_FALLBACK && safUri == null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onGrantSaf) {
                    Text("Link SAF Folder")
                }
            }
        }
    }
}

@Composable
fun HomeTab(
    settings: SettingsStore,
    targetStatus: TargetAppStatus,
    authService: AuthService,
    apiClient: LuaToolsApiClient,
    luaInstaller: LuaInstaller,
    cloudService: CloudRedirectService,
    safUri: Uri?,
    onGoToAdd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(authService.isSignedIn) }
    var botCode by remember { mutableStateOf("") }
    var authStatusMsg by remember { mutableStateOf<String?>(null) }
    var libraryMsg by remember { mutableStateOf<String?>(null) }
    var authBusy by remember { mutableStateOf(false) }
    var pushingAppId by remember { mutableStateOf<Long?>(null) }
    var updatingAppId by remember { mutableStateOf<Long?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var installedGames by remember { mutableStateOf(luaInstaller.listInstalledLuaGames()) }
    var updateStatusByApp by remember { mutableStateOf<Map<Long, GameUpdateStatus>>(emptyMap()) }
    val nativeVer = remember(cloudService.isNativeAvailable) { cloudService.getNativeVersion() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LuaTools Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (signedIn) {
                    Text(
                        "Signed in as ${authService.displayName ?: authService.email ?: "LuaTools User"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF81C784)
                    )
                    OutlinedButton(onClick = {
                        authService.signOut()
                        signedIn = false
                        authStatusMsg = "Signed out"
                    }) {
                        Text("Sign Out")
                    }
                } else {
                    Text(
                        "Sign in to download manifests, .lua files, and game fixes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !authBusy,
                            onClick = {
                                scope.launch {
                                    authBusy = true
                                    val res = authService.signInWithDiscordPkceAsync()
                                    signedIn = authService.isSignedIn
                                    authStatusMsg = res.fold(
                                        onSuccess = { "Signed in as $it" },
                                        onFailure = { it.message ?: "Discord login failed" }
                                    )
                                    authBusy = false
                                }
                            }
                        ) {
                            Text("Discord Sign In")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = botCode,
                            onValueChange = { botCode = it },
                            label = { Text("6-char /login Bot Code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = botCode.trim().length >= 4 && !authBusy,
                            onClick = {
                                scope.launch {
                                    authBusy = true
                                    val res = authService.signInWithBotCodeAsync(botCode)
                                    signedIn = authService.isSignedIn
                                    authStatusMsg = res.fold(
                                        onSuccess = { "Signed in as $it" },
                                        onFailure = { it.message ?: "Code sign-in failed" }
                                    )
                                    authBusy = false
                                }
                            }
                        ) {
                            Text("Redeem")
                        }
                    }
                }
                if (authStatusMsg != null) {
                    Text(authStatusMsg!!, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed .lua Library (${installedGames.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Folder: ${settings.downloadFolder}/stplug-in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (installedGames.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !checkingUpdates && updatingAppId == null,
                            onClick = {
                                scope.launch {
                                    checkingUpdates = true
                                    libraryMsg = "Checking Steam for depot updates (free, 0 quota)..."
                                    val results = mutableMapOf<Long, GameUpdateStatus>()
                                    for (g in installedGames) {
                                        results[g.appId] = luaInstaller.checkGameUpdateAsync(g.appId)
                                    }
                                    updateStatusByApp = results
                                    val needUpdate = results.values.count { it.hasUpdate }
                                    libraryMsg = if (needUpdate > 0) {
                                        "$needUpdate game(s) have updates or missing manifests."
                                    } else {
                                        "All ${installedGames.size} game(s) are up to date."
                                    }
                                    checkingUpdates = false
                                }
                            }
                        ) {
                            Text(if (checkingUpdates) "Checking..." else "Check Updates")
                        }
                        if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                            OutlinedButton(
                                enabled = pushingAppId == null && !checkingUpdates,
                                onClick = {
                                    scope.launch {
                                        var okCount = 0
                                        val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                        for (g in installedGames) {
                                            pushingAppId = g.appId
                                            val luaFile = File(settings.downloadFolder, "stplug-in/${g.appId}.lua")
                                            val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                            val res = TargetAppBridge.pushLuaGame(
                                                context = context,
                                                pkg = targetStatus.packageName,
                                                appId = g.appId,
                                                name = g.name,
                                                luaContent = content,
                                                depotCacheDir = depotCacheDir
                                            )
                                            if (res.isSuccess) okCount++
                                        }
                                        pushingAppId = null
                                        libraryMsg = "Pushed $okCount/${installedGames.size} saved game(s) to BannerHub (0 fetches)."
                                    }
                                }
                            ) {
                                Text("Push All")
                            }
                        }
                    }
                }
                if (libraryMsg != null) {
                    Text(libraryMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (installedGames.isEmpty()) {
                    Text("No games installed yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    installedGames.forEach { game ->
                        val id = game.appId
                        val upd = updateStatusByApp[id]
                        val needsUpdate = upd?.hasUpdate == true ||
                            (game.expectedWindowsManifests > 0 && game.cachedManifests < game.expectedWindowsManifests)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AsyncImage(
                                    model = "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg",
                                    contentDescription = game.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(34.dp)
                                        .clip(MaterialTheme.shapes.small)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        game.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = buildString {
                                            append("AppID $id • ${game.cachedManifests}/${game.expectedWindowsManifests} manifests")
                                            if (upd != null) append(" • ${upd.summary}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (needsUpdate) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                                    OutlinedButton(
                                        enabled = pushingAppId != id && updatingAppId != id,
                                        onClick = {
                                            scope.launch {
                                                pushingAppId = id
                                                val luaFile = File(settings.downloadFolder, "stplug-in/$id.lua")
                                                val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                                val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                                val res = TargetAppBridge.pushLuaGame(
                                                    context = context,
                                                    pkg = targetStatus.packageName,
                                                    appId = id,
                                                    name = game.name,
                                                    luaContent = content,
                                                    depotCacheDir = depotCacheDir
                                                )
                                                libraryMsg = res.fold(
                                                    onSuccess = { "Pushed ${game.name} from local cache (0 fetches): $it" },
                                                    onFailure = { "Bridge push failed: ${it.message}" }
                                                )
                                                pushingAppId = null
                                            }
                                        }
                                    ) {
                                        Text(if (pushingAppId == id) "..." else "Push")
                                    }
                                }
                                if (needsUpdate) {
                                    Button(
                                        enabled = updatingAppId != id && pushingAppId != id,
                                        onClick = {
                                            scope.launch {
                                                updatingAppId = id
                                                libraryMsg = "Updating ${game.name}..."
                                                val latest = upd?.latestWindowsGids.orEmpty()
                                                if (latest.isNotEmpty()) {
                                                    luaInstaller.applyUpdatedManifestPins(id, latest)
                                                }
                                                val readyCount = luaInstaller.ensureDepotManifests(id, apiClient, safUri)
                                                installedGames = luaInstaller.listInstalledLuaGames()
                                                updateStatusByApp = updateStatusByApp + (id to luaInstaller.checkGameUpdateAsync(id))
                                                var pushNote = ""
                                                if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                                                    val luaFile = File(settings.downloadFolder, "stplug-in/$id.lua")
                                                    val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                                    val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                                    val pushRes = TargetAppBridge.pushLuaGame(
                                                        context = context,
                                                        pkg = targetStatus.packageName,
                                                        appId = id,
                                                        name = game.name,
                                                        luaContent = content,
                                                        depotCacheDir = depotCacheDir
                                                    )
                                                    pushNote = pushRes.fold(
                                                        onSuccess = { " → pushed to BannerHub" },
                                                        onFailure = { " (push error: ${it.message})" }
                                                    )
                                                }
                                                libraryMsg = "Updated ${game.name} ($readyCount manifest(s))$pushNote"
                                                updatingAppId = null
                                            }
                                        }
                                    ) {
                                        Text(if (updatingAppId == id) "..." else "Update")
                                    }
                                }
                                TextButton(onClick = {
                                    luaInstaller.removeInstalledLua(id)
                                    installedGames = luaInstaller.listInstalledLuaGames()
                                }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                Button(onClick = onGoToAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Game from LuaTools")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CloudRedirect Core", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Provider: ${settings.cloudProvider} | Account: ${settings.cloudAccountId}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (nativeVer != null) "Native Core: v$nativeVer (loaded)" else "Native Core: not loaded",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun AddGamesTab(
    settings: SettingsStore,
    targetStatus: TargetAppStatus,
    apiClient: LuaToolsApiClient,
    hubcapService: HubcapService,
    luaInstaller: LuaInstaller,
    safUri: Uri?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var busyAppId by remember { mutableStateOf<Long?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SteamSearchResult>>(emptyList()) }
    var sourcesByApp by remember { mutableStateOf<Map<Long, List<SourceAvailability>>>(emptyMap()) }
    val hubcapKey = settings.hubcapApiKey.trim()

    suspend fun runSearch(term: String) {
        val clean = term.trim()
        if (clean.length < 2) {
            results = emptyList()
            return
        }
        searching = true
        results = runCatching { apiClient.searchAsync(clean) }.getOrDefault(emptyList())
        searching = false
    }

    // Auto-search as the user types (debounced)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            if (q.isEmpty()) results = emptyList()
            return@LaunchedEffect
        }
        delay(450)
        runSearch(q)
    }

    suspend fun getGameOneClick(item: SteamSearchResult) {
        busyAppId = item.appId
        statusIsError = false

        // If already saved locally, push from local cache without burning API fetch quota!
        val existingLua = File(settings.downloadFolder, "stplug-in/${item.appId}.lua")
        if (existingLua.isFile && existingLua.length() > 0L) {
            if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                val luaContent = runCatching { existingLua.readText(Charsets.UTF_8) }.getOrDefault("")
                val depotCacheDir = File(settings.downloadFolder, "depotcache")
                val pushRes = TargetAppBridge.pushLuaGame(
                    context = context,
                    pkg = targetStatus.packageName,
                    appId = item.appId,
                    name = item.name,
                    luaContent = luaContent,
                    depotCacheDir = depotCacheDir
                )
                statusIsError = pushRes.isFailure
                statusMsg = pushRes.fold(
                    onSuccess = { "${item.name} already saved locally → pushed to BannerHub (0 fetches)" },
                    onFailure = { "Saved locally, push failed: ${it.message}" }
                )
            } else {
                statusMsg = "${item.name} (${item.appId}) is already saved locally in stplug-in/"
            }
            busyAppId = null
            return
        }

        statusMsg = "Checking sources for ${item.name}..."

        val checked = runCatching { apiClient.checkSourcesAsync(item.appId) }.getOrDefault(emptyList())
        sourcesByApp = sourcesByApp + (item.appId to checked)
        val available = checked.filter { it.isAvailable }

        // Prefer LuaTools sources (e.g. ryuu / kernelos / first available), fall back to Hubcap if configured
        val preferredSource = available.firstOrNull()
        if (preferredSource != null) {
            statusMsg = "Getting ${item.name} from ${preferredSource.source}..."
            val tmpZip = File(context.cacheDir, "${item.appId}.zip")
            val dl = apiClient.downloadManifestZipAsync(
                appId = item.appId,
                source = preferredSource.source,
                gameName = item.name,
                destFile = tmpZip
            )
            if (dl.isSuccess) {
                val res = luaInstaller.installZip(dl.getOrThrow(), item.appId, safUri = safUri)
                if (res.error != null) {
                    statusIsError = true
                    statusMsg = "Install error: ${res.error}"
                } else {
                    statusMsg = "Fetching depot manifests for ${item.name}..."
                    val totalManifests = luaInstaller.ensureDepotManifests(item.appId, apiClient, safUri)
                    var pushSuffix = ""
                    if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                        val luaFile = File(settings.downloadFolder, "stplug-in/${item.appId}.lua")
                        val luaContent = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                        val depotCacheDir = File(settings.downloadFolder, "depotcache")
                        val pushRes = TargetAppBridge.pushLuaGame(
                            context = context,
                            pkg = targetStatus.packageName,
                            appId = item.appId,
                            name = item.name,
                            luaContent = luaContent,
                            depotCacheDir = depotCacheDir
                        )
                        pushSuffix = pushRes.fold(
                            onSuccess = { " → pushed to BannerHub" },
                            onFailure = { " (push error: ${it.message})" }
                        )
                    }
                    statusIsError = false
                    statusMsg = "Added ${item.name} (${item.appId}) via ${preferredSource.source}: lua=${res.luaInstalled}, manifests=$totalManifests$pushSuffix"
                }
                busyAppId = null
                return
            } else if (hubcapKey.isEmpty()) {
                statusIsError = true
                statusMsg = dl.exceptionOrNull()?.message ?: "Download failed"
                busyAppId = null
                return
            }
        }

        if (hubcapKey.isNotEmpty()) {
            statusMsg = "Getting ${item.name} via Hubcap..."
            val tmpZip = File(context.cacheDir, "${item.appId}_hubcap.zip")
            val dl = hubcapService.downloadManifestAsync(item.appId, hubcapKey, tmpZip)
            dl.fold(
                onSuccess = { zip ->
                    val res = luaInstaller.installZip(zip, item.appId, safUri = safUri)
                    if (res.error != null) {
                        statusIsError = true
                        statusMsg = "Install error: ${res.error}"
                    } else {
                        statusIsError = false
                        statusMsg = "Added ${item.name} (${item.appId}) via Hubcap: lua=${res.luaInstalled}, manifests=${res.manifestCount}"
                    }
                },
                onFailure = {
                    statusIsError = true
                    statusMsg = it.message ?: "Hubcap download failed"
                }
            )
        } else {
            statusIsError = true
            statusMsg = "No available LuaTools source for ${item.name} (and no Hubcap key set)."
        }
        busyAppId = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search Steam game or AppID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    scope.launch { runSearch(query) }
                }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = query.isNotBlank() && !searching,
                onClick = { scope.launch { runSearch(query) } }
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }

        if (statusMsg != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (statusIsError) Color(0xFF4A1E1E) else Color(0xFF1B3B2B)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.appId }) { item ->
                val sources = sourcesByApp[item.appId]
                val isBusy = busyAppId == item.appId
                val headerUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.appId}/header.jpg"
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = headerUrl,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(110.dp)
                                .height(52.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                "AppID ${item.appId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (sources != null) {
                                val avail = sources.filter { it.isAvailable }.joinToString { it.source }
                                Text(
                                    text = if (avail.isEmpty()) "No sources" else "Sources: $avail",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        val alreadySaved = remember(item.appId, busyAppId) {
                            File(settings.downloadFolder, "stplug-in/${item.appId}.lua").isFile
                        }
                        Button(
                            enabled = !isBusy,
                            onClick = { scope.launch { getGameOneClick(item) } }
                        ) {
                            Text(if (isBusy) "..." else if (alreadySaved) "Push" else "Get")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FixesTab(
    targetStatus: TargetAppStatus,
    apiClient: LuaToolsApiClient,
    luaInstaller: LuaInstaller
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var listings by remember { mutableStateOf<List<DenuvoGameListing>>(emptyList()) }
    var fixesByApp by remember { mutableStateOf<Map<String, List<DenuvoFix>>>(emptyMap()) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        listings = runCatching { apiClient.getDenuvoListingsAsync() }.getOrDefault(emptyList())
        loading = false
    }

    val filtered = remember(listings, filter) {
        if (filter.isBlank()) listings
        else listings.filter {
            it.name.contains(filter, ignoreCase = true) || it.appId.contains(filter, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter fixes by game or AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (statusMsg != null) {
            Spacer(Modifier.height(6.dp))
            Text(statusMsg!!, style = MaterialTheme.typography.bodySmall, color = Color(0xFF81C784))
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.appId }) { game ->
                val fixes = fixesByApp[game.appId]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${game.name} (${game.appId})", fontWeight = FontWeight.SemiBold)
                        Text("${game.fixCount} fix(es) available", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            scope.launch {
                                val loaded = runCatching { apiClient.getDenuvoFixesAsync(game.appId) }.getOrDefault(emptyList())
                                fixesByApp = fixesByApp + (game.appId to loaded)
                            }
                        }) {
                            Text(if (fixes == null) "Load Fixes" else "Refresh Fixes")
                        }
                        fixes?.forEach { fix ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(fix.title, fontWeight = FontWeight.Medium)
                                    if (!fix.description.isNullOrBlank()) {
                                        Text(fix.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (fix.hasFix) {
                                            Button(onClick = {
                                                scope.launch {
                                                    statusMsg = "Downloading fix..."
                                                    val tmp = File(context.cacheDir, "fix_${game.appId}.zip")
                                                    val dl = apiClient.downloadDenuvoAsync(fix.id, "fix", tmp)
                                                    statusMsg = dl.fold(
                                                        onSuccess = { zip ->
                                                            val localRes = luaInstaller.extractFixZip(zip, game.appId)
                                                                .getOrElse { "Extract error: ${it.message}" }
                                                            val appNum = game.appId.toLongOrNull() ?: 0L
                                                            if (appNum > 0L &&
                                                                targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                                                                targetStatus.packageName != null
                                                            ) {
                                                                val bridgeRes = TargetAppBridge.applyFix(
                                                                    context = context,
                                                                    pkg = targetStatus.packageName,
                                                                    appId = appNum,
                                                                    gameName = game.name,
                                                                    fixZipFile = zip
                                                                )
                                                                bridgeRes.fold(
                                                                    onSuccess = { "$localRes\n$it" },
                                                                    onFailure = { "$localRes\nBridge apply failed: ${it.message}" }
                                                                )
                                                            } else {
                                                                localRes
                                                            }
                                                        },
                                                        onFailure = { "Error: ${it.message}" }
                                                    )
                                                }
                                            }) {
                                                Text("Download Fix")
                                            }
                                        }
                                        if (fix.hasManifest) {
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    statusMsg = "Downloading fix manifest..."
                                                    val tmp = File(context.cacheDir, "fix_manifest_${game.appId}.zip")
                                                    val dl = apiClient.downloadDenuvoAsync(fix.id, "manifest", tmp)
                                                    statusMsg = dl.fold(
                                                        onSuccess = { zip ->
                                                            val appNum = game.appId.toLongOrNull() ?: 0L
                                                            if (appNum > 0L) {
                                                                val res = luaInstaller.installZip(zip, appNum, forceLocked = true)
                                                                "Fix manifest installed: lua=${res.luaInstalled}, manifests=${res.manifestCount}"
                                                            } else {
                                                                "Saved to ${zip.absolutePath}"
                                                            }
                                                        },
                                                        onFailure = { "Error: ${it.message}" }
                                                    )
                                                }
                                            }) {
                                                Text("Manifest")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DetectedCloudGame(
    val accountId: String,
    val appId: String
)

private data class CloudProviderOption(
    val id: String,
    val label: String,
    val usesOAuth: Boolean
)

private val cloudProviderOptions = listOf(
    CloudProviderOption("gdrive", "Google Drive", true),
    CloudProviderOption("onedrive", "OneDrive", true),
    CloudProviderOption("r2", "Cloudflare R2", false),
    CloudProviderOption("s3", "Amazon S3", false),
    CloudProviderOption("webdav", "WebDAV", false),
    CloudProviderOption("folder", "Local Folder", false)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudRedirectTab(
    settings: SettingsStore,
    cloudService: CloudRedirectService,
    cloudOAuth: CloudRedirectOAuthService
) {
    val scope = rememberCoroutineScope()
    var selectedProviderId by remember { mutableStateOf(settings.cloudProvider) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var authenticated by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var oauthBusy by remember { mutableStateOf(false) }
    var detectedGames by remember { mutableStateOf<List<DetectedCloudGame>>(emptyList()) }
    var statusMsg by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var manualAppId by remember { mutableStateOf("") }
    var manualCloudRoot by remember { mutableStateOf("") }

    val selectedOption = cloudProviderOptions.firstOrNull { it.id == selectedProviderId }
        ?: cloudProviderOptions.first()

    suspend fun refreshCloudState(providerId: String = selectedProviderId) {
        val status = cloudService.authStatus(providerId)
        authenticated = status.optBoolean("authenticated", false)
        if (authenticated) {
            scanning = true
            val scan = cloudService.discoverCloudGames(providerId)
            if (scan.optBoolean("success", false)) {
                val arr = scan.optJSONArray("games")
                val found = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val g = arr.getJSONObject(i)
                            add(
                                DetectedCloudGame(
                                    accountId = g.optString("account_id"),
                                    appId = g.optString("app_id")
                                )
                            )
                        }
                    }
                }
                detectedGames = found
                if (found.isNotEmpty() && settings.cloudAccountId == "0") {
                    settings.cloudAccountId = found.first().accountId
                }
                statusMsg = if (found.isEmpty()) {
                    "Connected to ${selectedOption.label}. No existing cloud games found yet."
                } else {
                    "Connected to ${selectedOption.label}. Detected ${found.size} game(s) in cloud."
                }
            } else {
                detectedGames = emptyList()
                statusMsg = scan.optString("error", "Failed to scan cloud games")
            }
            scanning = false
        } else {
            detectedGames = emptyList()
            statusMsg = "Not connected to ${selectedOption.label}."
        }
    }

    LaunchedEffect(selectedProviderId) {
        refreshCloudState(selectedProviderId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Cloud Storage Provider",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedOption.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Storage Type") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        cloudProviderOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    selectedProviderId = option.id
                                    settings.cloudProvider = option.id
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (authenticated) "Status: Connected" else "Status: Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (authenticated) Color(0xFF81C784) else Color(0xFFFFB74D)
                    )
                    if (authenticated) {
                        OutlinedButton(
                            enabled = !scanning,
                            onClick = { scope.launch { refreshCloudState(selectedProviderId) } }
                        ) {
                            Text("Rescan Games")
                        }
                    }
                }

                if (selectedOption.usesOAuth) {
                    Button(
                        enabled = !oauthBusy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                oauthBusy = true
                                val res = cloudOAuth.authorizeAsync(selectedOption.id)
                                statusMsg = res.fold(
                                    onSuccess = { it },
                                    onFailure = { "${selectedOption.label} login error: ${it.message}" }
                                )
                                if (res.isSuccess) refreshCloudState(selectedOption.id)
                                oauthBusy = false
                            }
                        }
                    ) {
                        Text(
                            if (authenticated) "Reconnect ${selectedOption.label}"
                            else "Sign in with ${selectedOption.label}"
                        )
                    }
                } else {
                    Text(
                        "Configure ${selectedOption.label} credentials in CloudRedirect/config.json, then tap Check & Scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        enabled = !scanning,
                        onClick = { scope.launch { refreshCloudState(selectedOption.id) } }
                    ) {
                        Text("Check & Scan ${selectedOption.label}")
                    }
                }
            }
        }

        if (scanning || oauthBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, style = MaterialTheme.typography.bodySmall)
        }

        if (detectedGames.isNotEmpty()) {
            Text(
                "Games Found in Cloud (${detectedGames.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            detectedGames.forEach { game ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = "https://shared.steamstatic.com/store_item_assets/steam/apps/${game.appId}/header.jpg",
                            contentDescription = "AppID ${game.appId}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(84.dp)
                                .height(40.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Column(Modifier.weight(1f)) {
                            Text("AppID ${game.appId}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Account ${game.accountId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = {
                            scope.launch {
                                val targetRoot = "${settings.downloadFolder}/CloudSaves/${game.appId}"
                                File(targetRoot).mkdirs()
                                val res = cloudService.syncRemoteApp(
                                    settings.cloudProvider,
                                    game.accountId,
                                    game.appId,
                                    targetRoot
                                )
                                statusMsg = "Sync AppID ${game.appId}: ${res.toString()}"
                            }
                        }) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Sync")
                        }
                    }
                }
            }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide Advanced Manual Sync" else "Show Advanced Manual Sync")
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = manualAppId,
                onValueChange = { manualAppId = it },
                label = { Text("AppID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = manualCloudRoot,
                onValueChange = { manualCloudRoot = it },
                label = { Text("Cloud Root Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        statusMsg = cloudService.authStatus(settings.cloudProvider).toString(2)
                    }
                }) {
                    Text("Auth Status")
                }
                Button(
                    enabled = manualAppId.isNotBlank() && manualCloudRoot.isNotBlank(),
                    onClick = {
                        scope.launch {
                            statusMsg = cloudService.syncRemoteApp(
                                settings.cloudProvider,
                                settings.cloudAccountId,
                                manualAppId,
                                manualCloudRoot
                            ).toString(2)
                        }
                    }
                ) {
                    Text("Sync Custom Path")
                }
            }
        }
    }
}

@Composable
fun MergedOptionsTab(
    settings: SettingsStore,
    targetStatus: TargetAppStatus,
    safUri: Uri?,
    safValidation: SafValidationResult?,
    hubcapService: HubcapService,
    cloudService: CloudRedirectService,
    onRefreshBridge: () -> Unit,
    onGrantSaf: () -> Unit,
    onClearSaf: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var autoUpdateApps by remember { mutableStateOf(settings.autoUpdateApps) }
    var downloadFolder by remember { mutableStateOf(settings.downloadFolder) }
    var hubcapKey by remember { mutableStateOf(settings.hubcapApiKey) }
    var hubcapStatus by remember { mutableStateOf<String?>(null) }
    var cloudProvider by remember { mutableStateOf(settings.cloudProvider) }
    var cloudAccountId by remember { mutableStateOf(settings.cloudAccountId) }
    var autoSync by remember { mutableStateOf(settings.autoSyncOnLaunch) }

    val nativeVer = remember(cloudService.isNativeAvailable) { cloudService.getNativeVersion() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Platform, Bridge & SAF Folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "Active: ${settings.selectedPlatform.displayName}" +
                (targetStatus.packageName?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        if (safValidation != null) {
            Text(
                text = "SAF: ${safValidation.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (safValidation.isValid) Color(0xFF81C784) else Color(0xFFFFB74D)
            )
        } else {
            Text(
                text = "SAF Folder: Not linked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGrantSaf) {
                Text(if (safUri == null) "Select SAF Folder" else "Change SAF Folder")
            }
            if (safUri != null) {
                TextButton(onClick = onClearSaf) {
                    Text("Clear")
                }
            }
            OutlinedButton(onClick = onRefreshBridge) {
                Text("Re-check Bridge")
            }
        }
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Switch Platform / Re-run Setup")
        }

        if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
            var steamEmuStatus by remember { mutableStateOf<String?>(null) }
            var steamEmuBusy by remember { mutableStateOf(false) }
            var autoCloseWine by remember { mutableStateOf(false) }

            LaunchedEffect(targetStatus.packageName) {
                TargetAppBridge.getBridgeStatus(context, targetStatus.packageName).onSuccess { jsonStr ->
                    runCatching {
                        val obj = org.json.JSONObject(jsonStr)
                        autoCloseWine = obj.optBoolean("autoclose_wine", false)
                    }
                }
            }

            HorizontalDivider()
            Text("Steam Unlocker (BST / OST)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Download BetterSteamTools or OST-Nightly from GitHub and push to ${targetStatus.appName} over the bridge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !steamEmuBusy,
                    onClick = {
                        scope.launch {
                            steamEmuBusy = true
                            steamEmuStatus = "Downloading & pushing BST..."
                            val pkg = targetStatus.packageName
                            val dl = SteamEmuDownloader.downloadPack(context, "bst")
                            steamEmuStatus = dl.fold(
                                onSuccess = { dir ->
                                    val push = TargetAppBridge.pushSteamEmuPack(context, pkg, "bst", dir)
                                    push.fold(
                                        onSuccess = { n ->
                                            val set = TargetAppBridge.setSteamEmu(context, pkg, "bst", realSteam = false)
                                            set.fold(
                                                onSuccess = { "BST pushed ($n DLLs) & enabled" },
                                                onFailure = { "BST pushed, set_steam_emu failed: ${it.message}" }
                                            )
                                        },
                                        onFailure = { "Push failed: ${it.message}" }
                                    )
                                },
                                onFailure = { "Download failed: ${it.message}" }
                            )
                            steamEmuBusy = false
                        }
                    }
                ) { Text("Install BST") }

                OutlinedButton(
                    enabled = !steamEmuBusy,
                    onClick = {
                        scope.launch {
                            steamEmuBusy = true
                            steamEmuStatus = "Downloading & pushing OST..."
                            val pkg = targetStatus.packageName
                            val dl = SteamEmuDownloader.downloadPack(context, "ost")
                            steamEmuStatus = dl.fold(
                                onSuccess = { dir ->
                                    val push = TargetAppBridge.pushSteamEmuPack(context, pkg, "ost", dir)
                                    push.fold(
                                        onSuccess = { n ->
                                            val set = TargetAppBridge.setSteamEmu(context, pkg, "ost", realSteam = false)
                                            set.fold(
                                                onSuccess = { "OST pushed ($n DLLs) & enabled" },
                                                onFailure = { "OST pushed, set_steam_emu failed: ${it.message}" }
                                            )
                                        },
                                        onFailure = { "Push failed: ${it.message}" }
                                    )
                                },
                                onFailure = { "Download failed: ${it.message}" }
                            )
                            steamEmuBusy = false
                        }
                    }
                ) { Text("Install OST") }

                OutlinedButton(
                    enabled = !steamEmuBusy,
                    onClick = {
                        scope.launch {
                            steamEmuBusy = true
                            val res = TargetAppBridge.setSteamEmu(context, targetStatus.packageName, "off", realSteam = false)
                            steamEmuStatus = res.fold(
                                onSuccess = { "Steam unlocker set to OFF" },
                                onFailure = { "Error: ${it.message}" }
                            )
                            steamEmuBusy = false
                        }
                    }
                ) { Text("Off") }
            }
            steamEmuStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-close Wine Container")
                    Text(
                        "Close container automatically when the game .exe exits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCloseWine,
                    onCheckedChange = { enabled ->
                        autoCloseWine = enabled
                        scope.launch {
                            TargetAppBridge.setSteamEmu(
                                context,
                                targetStatus.packageName,
                                autoCloseWine = enabled
                            )
                        }
                    }
                )
            }
        }

        HorizontalDivider()

        Text("LuaTools Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-Update Apps")
                Text(
                    "Comment out setManifestid() pins in installed .lua files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoUpdateApps,
                onCheckedChange = {
                    autoUpdateApps = it
                    settings.autoUpdateApps = it
                }
            )
        }

        OutlinedTextField(
            value = downloadFolder,
            onValueChange = {
                downloadFolder = it
                settings.downloadFolder = it
            },
            label = { Text("Depot Download Folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = hubcapKey,
            onValueChange = {
                hubcapKey = it
                settings.hubcapApiKey = it.trim()
            },
            label = { Text("Hubcap API Key (smm_...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = hubcapKey.isNotBlank(),
                onClick = {
                    scope.launch {
                        val clean = hubcapKey.trim()
                        val stats = hubcapService.getStatsAsync(clean)
                        hubcapStatus = if (stats != null) {
                            "Hubcap OK: ${stats.used}/${stats.limit} used (${stats.remaining} remaining)"
                        } else if (!hubcapService.isValidKeyFormat(clean)) {
                            "Invalid key format (expected smm_ + 96 hex chars)"
                        } else {
                            "Could not verify Hubcap key (invalid key or network error)"
                        }
                    }
                }
            ) {
                Text("Verify Hubcap Key")
            }
            if (hubcapStatus != null) {
                Text(hubcapStatus!!, style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()

        Text("CloudRedirect Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (nativeVer != null) "Native Core: v$nativeVer" else "Native Core: not loaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-Sync on Launch / Exit")
                Text(
                    "Runs automatically when BannerHub Bridge is connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoSync,
                onCheckedChange = {
                    autoSync = it
                    settings.autoSyncOnLaunch = it
                }
            )
        }

        OutlinedTextField(
            value = cloudProvider,
            onValueChange = {
                cloudProvider = it
                settings.cloudProvider = it
            },
            label = { Text("Cloud Provider (gdrive, onedrive, r2, s3, webdav, folder)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = cloudAccountId,
            onValueChange = {
                cloudAccountId = it
                settings.cloudAccountId = it
            },
            label = { Text("Steam Account ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
    }
}

private fun hasStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun buildBannerHubInitialUri(status: TargetAppStatus): Uri? {
    val auth = status.mtAuthority ?: return null
    val pkg = status.packageName ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        DocumentsContract.buildRootUri(auth, pkg)
    } else {
        null
    }
}

private fun detectBannerHubFolders(): List<String> {
    val candidates = mutableListOf<File>()
    val primary = Environment.getExternalStorageDirectory()
    candidates += File(primary, "bannerhub")
    candidates += File(primary, "BannerHub")
    candidates += File(primary, "GameHub")
    candidates += File(primary, "Games")

    // Check removable SD cards under /storage/<uuid>/bannerhub
    File("/storage").listFiles()?.forEach { vol ->
        if (vol.isDirectory && vol.name != "emulated" && vol.name != "self") {
            candidates += File(vol, "bannerhub")
            candidates += File(vol, "Games")
        }
    }

    return candidates
        .filter { it.exists() && it.isDirectory }
        .map { it.absolutePath }
        .distinct()
}
