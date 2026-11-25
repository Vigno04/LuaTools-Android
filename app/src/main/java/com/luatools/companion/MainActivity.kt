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
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(cacheDir, "image_cache"))
                        .maxSizeBytes(50L * 1024L * 1024L)
                        .build()
                }
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .respectCacheHeaders(false)
                .build()
        )
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
        mutableStateOf(TargetAppBridge.probeBannerHub(context, settings.selectedPackageName, selectedPlatform))
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
        targetStatus = TargetAppBridge.probeBannerHub(context, settings.selectedPackageName, selectedPlatform)
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
            installedApps = installedBannerHubs,
            safUri = safTreeUri,
            safValidation = safValidation,
            onSelectTarget = { pkg ->
                settings.selectedPackageName = pkg
                val matched = installedBannerHubs.firstOrNull { it.packageName == pkg }
                val plat = matched?.platform ?: when (pkg) {
                    "app.gamenative" -> TargetPlatform.WINNATIVE
                    "com.winlator" -> TargetPlatform.WINLATOR
                    "banner.hub.v6" -> TargetPlatform.BANNERHUB_V6
                    else -> TargetPlatform.BANNERHUB
                }
                selectedPlatform = plat
                settings.selectedPlatform = plat
                settings.addPlatform(plat.id)
                refreshTargetStatus()
            },
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
                    val isSelected = selected == bh.platform &&
                        (selectedPackage == null || selectedPackage == bh.packageName)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(bh.platform, bh.packageName) },
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
                                    "${bh.appName} (${bh.packageName})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (bh.mode == ConnectionMode.FULLY_CONNECTED)
                                        "${bh.platform.displayName} • Bridge v${bh.bridgeVersion} detected (Auto Sync)"
                                    else
                                        "${bh.platform.displayName} • Standard / Unmodified (SAF Manual Fallback)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }

            val detectedPlatforms = installedBannerHubs.map { it.platform }.toSet()
            TargetPlatform.entries.forEach { platform ->
                if (platform in detectedPlatforms) {
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
    installedApps: List<TargetAppStatus>,
    safUri: Uri?,
    safValidation: SafValidationResult?,
    onSelectTarget: (String) -> Unit,
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
            when (selectedTab) {
                0 -> HomeTab(
                    settings = settings,
                    targetStatus = targetStatus,
                    installedApps = installedApps,
                    luaInstaller = luaInstaller,
                    apiClient = apiClient,
                    safUri = safUri,
                    onSelectTarget = onSelectTarget,
                    onGrantSaf = onGrantSaf,
                    onGoToAdd = { selectedTab = 1 }
                )
                1 -> AddGamesTab(
                    settings = settings,
                    targetStatus = targetStatus,
                    installedApps = installedApps,
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
                    authService = authService,
                    targetStatus = targetStatus,
                    installedApps = installedApps,
                    safUri = safUri,
                    safValidation = safValidation,
                    hubcapService = hubcapService,
                    luaInstaller = luaInstaller,
                    onSelectTarget = onSelectTarget,
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
            "${status.appName} Bridge Connected"
        )
        ConnectionMode.MANUAL_FALLBACK -> Triple(
            Color(0xFF3E2E14),
            Icons.Default.Warning,
            "Manual Fallback Mode"
        )
        ConnectionMode.NOT_INSTALLED -> Triple(
            Color(0xFF2B2B2B),
            Icons.Default.Warning,
            "${status.appName} Not Detected"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    settings: SettingsStore,
    targetStatus: TargetAppStatus,
    installedApps: List<TargetAppStatus>,
    luaInstaller: LuaInstaller,
    apiClient: LuaToolsApiClient,
    safUri: Uri?,
    onSelectTarget: (String) -> Unit,
    onGrantSaf: () -> Unit,
    onGoToAdd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appDropdownExpanded by remember { mutableStateOf(false) }
    var libraryMsg by remember { mutableStateOf<String?>(null) }
    var pushingAppId by remember { mutableStateOf<Long?>(null) }
    var rePushAllBusy by remember { mutableStateOf(false) }
    var rePushProgress by remember { mutableFloatStateOf(0f) }
    var rePushLabel by remember { mutableStateOf("") }
    var updatingAppId by remember { mutableStateOf<Long?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var allCachedGames by remember { mutableStateOf(luaInstaller.listInstalledLuaGames()) }
    var appInstalledIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var updateStatusByApp by remember { mutableStateOf<Map<Long, GameUpdateStatus>>(emptyMap()) }

    val syncAllActive = settings.syncAllConnectedApps

    fun refreshAppGames() {
        allCachedGames = luaInstaller.listInstalledLuaGames()
        val targetPkgs = if (syncAllActive) {
            installedApps.filter { it.mode == ConnectionMode.FULLY_CONNECTED && it.packageName != null }
                .mapNotNull { it.packageName }
                .ifEmpty { listOfNotNull(targetStatus.packageName) }
        } else {
            listOfNotNull(targetStatus.packageName)
        }
        if (targetPkgs.isEmpty()) {
            appInstalledIds = emptySet()
            return
        }
        val combined = mutableSetOf<Long>()
        for (pkg in targetPkgs) {
            combined.addAll(settings.getAppSyncedIds(pkg))
            if (syncAllActive || targetStatus.mode == ConnectionMode.FULLY_CONNECTED) {
                TargetAppBridge.listLuaApps(context, pkg)?.let { combined.addAll(it) }
                TargetAppBridge.getBridgeStatus(context, pkg).onSuccess { jsonStr ->
                    runCatching {
                        val arr = org.json.JSONObject(jsonStr).optJSONArray("attached")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val id = arr.getJSONObject(i).optLong("app_id", 0L)
                                if (id > 0L) combined.add(id)
                            }
                        }
                    }
                }
            }
        }
        appInstalledIds = combined
    }

    LaunchedEffect(targetStatus.packageName, targetStatus.mode, syncAllActive) {
        refreshAppGames()
    }

    // Only show games that are installed in the currently selected app (or any connected app when synced)
    val visibleGames = remember(allCachedGames, appInstalledIds) {
        allCachedGames.filter { appInstalledIds.contains(it.appId) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App selector dropdown (grayed out when cross-app sync is active)
        ExposedDropdownMenuBox(
            expanded = appDropdownExpanded && !syncAllActive,
            onExpandedChange = { if (!syncAllActive) appDropdownExpanded = !appDropdownExpanded }
        ) {
            val selectedLabel = if (syncAllActive) {
                "Sync active across all apps"
            } else {
                targetStatus.packageName?.let { pkg ->
                    "${targetStatus.appName} ($pkg)"
                } ?: "No target app detected"
            }
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                enabled = !syncAllActive,
                label = { Text("Active App") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = appDropdownExpanded && !syncAllActive)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = appDropdownExpanded,
                onDismissRequest = { appDropdownExpanded = false }
            ) {
                installedApps.forEach { app ->
                    val pkg = app.packageName ?: return@forEach
                    val badge = if (app.mode == ConnectionMode.FULLY_CONNECTED) "Bridge" else "Fallback"
                    DropdownMenuItem(
                        text = { Text("${app.appName} ($pkg) • $badge") },
                        onClick = {
                            appDropdownExpanded = false
                            onSelectTarget(pkg)
                        }
                    )
                }
            }
        }

        // Only show connection status card if there is a problem
        val hasProblem = targetStatus.mode == ConnectionMode.NOT_INSTALLED ||
            (targetStatus.mode == ConnectionMode.MANUAL_FALLBACK && safUri == null)
        if (hasProblem) {
            ConnectionStatusCard(status = targetStatus, safUri = safUri, onGrantSaf = onGrantSaf)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${targetStatus.appName} Games (${visibleGames.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (targetStatus.platform == TargetPlatform.BANNERHUB_V6 &&
                    targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                    targetStatus.packageName != null
                ) {
                    OutlinedButton(onClick = {
                        val pkg = targetStatus.packageName!!
                        val openIntent = Intent("com.luatools.OPEN_LUATOOLS").apply {
                            setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(openIntent) }.onFailure {
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launch)
                            }
                        }
                    }) {
                        Text("Open LuaTools in ${targetStatus.appName}")
                    }
                }
                if (visibleGames.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !checkingUpdates && updatingAppId == null,
                            onClick = {
                                scope.launch {
                                    checkingUpdates = true
                                    libraryMsg = "Checking Steam for depot updates (free, 0 quota)..."
                                    val results = mutableMapOf<Long, GameUpdateStatus>()
                                    for (g in visibleGames) {
                                        results[g.appId] = luaInstaller.checkGameUpdateAsync(g.appId)
                                    }
                                    updateStatusByApp = results
                                    val needUpdate = results.values.count { it.hasUpdate }
                                    libraryMsg = if (needUpdate > 0) {
                                        "$needUpdate game(s) have updates or missing manifests."
                                    } else {
                                        "All ${visibleGames.size} game(s) are up to date."
                                    }
                                    checkingUpdates = false
                                }
                            }
                        ) {
                            Text(if (checkingUpdates) "Checking..." else "Check Updates")
                        }
                        if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
                            OutlinedButton(
                                enabled = pushingAppId == null && !checkingUpdates && !rePushAllBusy,
                                onClick = {
                                    scope.launch {
                                        val targets = if (syncAllActive) {
                                            installedApps.filter { it.mode == ConnectionMode.FULLY_CONNECTED && it.packageName != null }
                                        } else {
                                            listOf(targetStatus)
                                        }
                                        val total = (visibleGames.size * targets.size).coerceAtLeast(1)
                                        var done = 0
                                        var okCount = 0
                                        rePushAllBusy = true
                                        rePushProgress = 0f
                                        val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                        for (t in targets) {
                                            val pkg = t.packageName ?: continue
                                            for (g in visibleGames) {
                                                pushingAppId = g.appId
                                                rePushLabel = "Pushing ${g.name} to ${t.appName} (${done + 1}/$total)..."
                                                val ok = withContext(Dispatchers.IO) {
                                                    val luaFile = File(settings.downloadFolder, "stplug-in/${g.appId}.lua")
                                                    val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                                    if (content.isNotBlank()) {
                                                        TargetAppBridge.pushLuaGame(
                                                            context = context,
                                                            pkg = pkg,
                                                            appId = g.appId,
                                                            name = g.name,
                                                            luaContent = content,
                                                            depotCacheDir = depotCacheDir
                                                        ).isSuccess
                                                    } else false
                                                }
                                                if (ok) okCount++
                                                done++
                                                rePushProgress = done.toFloat() / total.toFloat()
                                            }
                                        }
                                        pushingAppId = null
                                        rePushAllBusy = false
                                        libraryMsg = "Re-pushed $okCount/$total push(es)."
                                    }
                                }
                            ) {
                                Text("Re-push All")
                            }
                        }
                    }
                }
                if (libraryMsg != null) {
                    Text(libraryMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (visibleGames.isEmpty()) {
                    Text(
                        "No games installed in ${targetStatus.appName} yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    visibleGames.forEach { game ->
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
                                        enabled = pushingAppId != id && updatingAppId != id && !rePushAllBusy,
                                        onClick = {
                                            scope.launch {
                                                pushingAppId = id
                                                libraryMsg = "Pushing ${game.name}..."
                                                val res = withContext(Dispatchers.IO) {
                                                    val luaFile = File(settings.downloadFolder, "stplug-in/$id.lua")
                                                    val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                                    val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                                    TargetAppBridge.pushLuaGame(
                                                        context = context,
                                                        pkg = targetStatus.packageName,
                                                        appId = id,
                                                        name = game.name,
                                                        luaContent = content,
                                                        depotCacheDir = depotCacheDir
                                                    )
                                                }
                                                libraryMsg = res.fold(
                                                    onSuccess = {
                                                        settings.addAppSyncedId(targetStatus.packageName!!, id)
                                                        refreshAppGames()
                                                        "Pushed ${game.name} from local cache (0 fetches): $it"
                                                    },
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
                                                refreshAppGames()
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
                                                        onSuccess = {
                                                            settings.addAppSyncedId(targetStatus.packageName, id)
                                                            refreshAppGames()
                                                            " → pushed to ${targetStatus.appName}"
                                                        },
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
                                    targetStatus.packageName?.let { settings.removeAppSyncedId(it, id) }
                                    luaInstaller.removeInstalledLua(id)
                                    refreshAppGames()
                                }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                val unpushedCached = allCachedGames.filter { !appInstalledIds.contains(it.appId) }
                if (unpushedCached.isNotEmpty() &&
                    targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                    targetStatus.packageName != null
                ) {
                    OutlinedButton(
                        enabled = pushingAppId == null && !rePushAllBusy,
                        onClick = {
                            scope.launch {
                                val pkg = targetStatus.packageName
                                val total = unpushedCached.size
                                var done = 0
                                var okCount = 0
                                rePushAllBusy = true
                                rePushProgress = 0f
                                val depotCacheDir = File(settings.downloadFolder, "depotcache")
                                for (g in unpushedCached) {
                                    pushingAppId = g.appId
                                    rePushLabel = "Pushing ${g.name} to ${targetStatus.appName} (${done + 1}/$total)..."
                                    val ok = withContext(Dispatchers.IO) {
                                        val luaFile = File(settings.downloadFolder, "stplug-in/${g.appId}.lua")
                                        val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                                        if (content.isNotBlank()) {
                                            TargetAppBridge.pushLuaGame(
                                                context = context,
                                                pkg = pkg,
                                                appId = g.appId,
                                                name = g.name,
                                                luaContent = content,
                                                depotCacheDir = depotCacheDir
                                            ).isSuccess
                                        } else false
                                    }
                                    if (ok) {
                                        settings.addAppSyncedId(pkg, g.appId)
                                        okCount++
                                    }
                                    done++
                                    rePushProgress = done.toFloat() / total.toFloat()
                                }
                                pushingAppId = null
                                rePushAllBusy = false
                                refreshAppGames()
                                libraryMsg = "Pushed $okCount/$total cached game(s) to ${targetStatus.appName}."
                            }
                        }
                    ) {
                        Text("Push Cached Games (${unpushedCached.size})")
                    }
                }
                Button(onClick = onGoToAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Game from LuaTools")
                }
            }
        }
    }

    if (rePushAllBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pushing Games...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rePushLabel, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { rePushProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun AddGamesTab(
    settings: SettingsStore,
    targetStatus: TargetAppStatus,
    installedApps: List<TargetAppStatus>,
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

    suspend fun pushToActiveTargets(appId: Long, name: String, luaContent: String): Pair<Boolean, String> {
        val depotCacheDir = File(settings.downloadFolder, "depotcache")
        val targets = if (settings.syncAllConnectedApps) {
            installedApps.filter { it.mode == ConnectionMode.FULLY_CONNECTED && it.packageName != null }
        } else {
            listOfNotNull(
                targetStatus.takeIf { it.mode == ConnectionMode.FULLY_CONNECTED && it.packageName != null }
            )
        }
        if (targets.isEmpty()) {
            return false to "$name ($appId) saved locally in stplug-in/"
        }
        var okCount = 0
        var lastError: String? = null
        for (t in targets) {
            val res = TargetAppBridge.pushLuaGame(
                context = context,
                pkg = t.packageName!!,
                appId = appId,
                name = name,
                luaContent = luaContent,
                depotCacheDir = depotCacheDir
            )
            if (res.isSuccess) {
                settings.addAppSyncedId(t.packageName!!, appId)
                okCount++
            } else {
                lastError = res.exceptionOrNull()?.message
            }
        }
        return if (okCount > 0) {
            val names = targets.joinToString { it.appName }
            true to "pushed to $names"
        } else {
            false to "push failed: ${lastError ?: "unknown"}"
        }
    }

    suspend fun getGameOneClick(item: SteamSearchResult) {
        busyAppId = item.appId
        statusIsError = false

        // Smart cache reuse: if already saved locally, check if it's latest before ever hitting LuaTools API
        val existingLua = File(settings.downloadFolder, "stplug-in/${item.appId}.lua")
        if (existingLua.isFile && existingLua.length() > 0L) {
            statusMsg = "Checking local cache for ${item.name}..."
            val upd = luaInstaller.checkGameUpdateAsync(item.appId)
            val needsManifests = upd.missingManifests > 0 || (settings.autoUpdateApps && upd.hasUpdate)
            if (needsManifests) {
                statusMsg = "Updating manifests for ${item.name} from cache/Steam..."
                if (upd.latestWindowsGids.isNotEmpty()) {
                    luaInstaller.applyUpdatedManifestPins(item.appId, upd.latestWindowsGids)
                }
                luaInstaller.ensureDepotManifests(item.appId, apiClient, safUri)
            }
            val luaContent = runCatching { existingLua.readText(Charsets.UTF_8) }.getOrDefault("")
            val (ok, msg) = pushToActiveTargets(item.appId, item.name, luaContent)
            statusIsError = !ok && targetStatus.mode == ConnectionMode.FULLY_CONNECTED
            statusMsg = "${item.name}: reused local cache${if (needsManifests) " (updated manifests)" else " (0 API fetches)"} → $msg"
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
                    val luaFile = File(settings.downloadFolder, "stplug-in/${item.appId}.lua")
                    val luaContent = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                    val (_, pushNote) = pushToActiveTargets(item.appId, item.name, luaContent)
                    statusIsError = false
                    statusMsg = "Added ${item.name} (${item.appId}) via ${preferredSource.source}: lua=${res.luaInstalled}, manifests=$totalManifests → $pushNote"
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

private data class FixGameGroup(
    val appId: String,
    val name: String,
    val communityFixes: List<org.json.JSONObject>,
    val denuvoListing: DenuvoGameListing?
) {
    val totalCount: Int
        get() = communityFixes.size + (denuvoListing?.fixCount ?: 0)
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
    var onlyInstalled by remember { mutableStateOf(true) }
    var installedAppIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedFixes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var listings by remember { mutableStateOf<List<DenuvoGameListing>>(emptyList()) }
    var fixesByApp by remember { mutableStateOf<Map<String, List<DenuvoFix>>>(emptyMap()) }
    var communityFixes by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var selectedGroup by remember { mutableStateOf<FixGameGroup?>(null) }
    var loadingGameFixes by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetStatus.packageName) {
        loading = true
        val localIds = luaInstaller.listInstalledLuaGames().map { it.appId.toString() }.toMutableSet()
        if (targetStatus.mode == ConnectionMode.FULLY_CONNECTED && targetStatus.packageName != null) {
            installedFixes = TargetAppBridge.getInstalledFixes(context, targetStatus.packageName)
            TargetAppBridge.listLuaApps(context, targetStatus.packageName)?.forEach {
                localIds.add(it.toString())
            }
            TargetAppBridge.getBridgeStatus(context, targetStatus.packageName).onSuccess { jsonStr ->
                runCatching {
                    val arr = org.json.JSONObject(jsonStr).optJSONArray("attached")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val id = arr.getJSONObject(i).optInt("app_id", 0)
                            if (id > 0) localIds.add(id.toString())
                        }
                    }
                }
            }
        }
        installedAppIds = localIds
        listings = runCatching { apiClient.getDenuvoListingsAsync() }.getOrDefault(emptyList())
        communityFixes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val client = okhttp3.OkHttpClient()
                val req = okhttp3.Request.Builder()
                    .url("https://raw.githubusercontent.com/Vigno04/LuaTools-Fixes/main/fixes.json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else {
                        val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
                        (0 until arr.length()).map { arr.getJSONObject(it) }
                    }
                }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val groupedGames = remember(listings, communityFixes, filter, onlyInstalled, installedAppIds) {
        val byId = linkedMapOf<String, FixGameGroup>()
        for (cf in communityFixes) {
            val id = cf.optString("appId")
            if (id.isBlank()) continue
            val existing = byId[id]
            val name = cf.optString("name", existing?.name ?: "AppID $id")
            byId[id] = FixGameGroup(
                appId = id,
                name = name,
                communityFixes = (existing?.communityFixes ?: emptyList()) + cf,
                denuvoListing = existing?.denuvoListing
            )
        }
        for (l in listings) {
            val existing = byId[l.appId]
            byId[l.appId] = FixGameGroup(
                appId = l.appId,
                name = l.name.ifBlank { existing?.name ?: "AppID ${l.appId}" },
                communityFixes = existing?.communityFixes ?: emptyList(),
                denuvoListing = l
            )
        }
        byId.values.filter { group ->
            group.totalCount > 0 &&
                (!onlyInstalled || installedAppIds.contains(group.appId)) &&
                (filter.isBlank() ||
                    group.name.contains(filter, ignoreCase = true) ||
                    group.appId.contains(filter, ignoreCase = true))
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Only show installed games (${installedAppIds.size})", style = MaterialTheme.typography.bodySmall)
            Switch(checked = onlyInstalled, onCheckedChange = { onlyInstalled = it })
        }
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
            items(groupedGames, key = { it.appId }) { group ->
                val installedVer = installedFixes[group.appId]
                val countLabel = if (group.totalCount == 1) "1 fix" else "${group.totalCount} fixes"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedGroup = group
                            if (group.denuvoListing != null && fixesByApp[group.appId] == null) {
                                scope.launch {
                                    loadingGameFixes = true
                                    val loaded = runCatching { apiClient.getDenuvoFixesAsync(group.appId) }
                                        .getOrDefault(emptyList())
                                    fixesByApp = fixesByApp + (group.appId to loaded)
                                    loadingGameFixes = false
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = "https://shared.steamstatic.com/store_item_assets/steam/apps/${group.appId}/header.jpg",
                            contentDescription = group.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(88.dp)
                                .height(42.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                text = "AppID ${group.appId} • $countLabel" +
                                    (if (installedVer != null) " • Installed (v$installedVer)" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (installedVer != null) Color(0xFF81C784) else MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "View Fixes")
                    }
                }
            }
        }
    }

    selectedGroup?.let { group ->
        val apiFixes = fixesByApp[group.appId].orEmpty()
        AlertDialog(
            onDismissRequest = { selectedGroup = null },
            title = { Text("${group.name} (${group.appId})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (loadingGameFixes) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    group.communityFixes.forEach { ghFix ->
                        val appIdStr = group.appId
                        val title = ghFix.optString("title", "Community Fix")
                        val desc = ghFix.optString("description", "")
                        val remoteVer = ghFix.optString("version", "1.0.0")
                        val zipUrl = ghFix.optString("zipUrl", "")
                        val installedVer = installedFixes[appIdStr]
                        val isInstalled = installedVer != null
                        val hasUpdate = isInstalled && installedVer != remoteVer

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("$title (v$remoteVer)", fontWeight = FontWeight.SemiBold)
                                if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall)
                                if (zipUrl.isNotBlank()) {
                                    val doApply: () -> Unit = {
                                        scope.launch {
                                            statusMsg = "Downloading fix..."
                                            val tmp = File(context.cacheDir, "gh_fix_$appIdStr.zip")
                                            val dl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                runCatching {
                                                    val client = okhttp3.OkHttpClient()
                                                    val req = okhttp3.Request.Builder().url(zipUrl).build()
                                                    client.newCall(req).execute().use { resp ->
                                                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                                                        resp.body!!.byteStream().use { ins ->
                                                            tmp.outputStream().use { os -> ins.copyTo(os) }
                                                        }
                                                    }
                                                    tmp
                                                }
                                            }
                                            statusMsg = dl.fold(
                                                onSuccess = { zip ->
                                                    val localRes = luaInstaller.extractFixZip(zip, appIdStr)
                                                        .getOrElse { "Extract error: ${it.message}" }
                                                    val appNum = appIdStr.toLongOrNull() ?: 0L
                                                    if (appNum > 0L &&
                                                        targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                                                        targetStatus.packageName != null
                                                    ) {
                                                        TargetAppBridge.applyFix(
                                                            context = context,
                                                            pkg = targetStatus.packageName,
                                                            appId = appNum,
                                                            gameName = group.name,
                                                            fixZipFile = zip,
                                                            fixVersion = remoteVer
                                                        ).fold(
                                                            onSuccess = {
                                                                installedFixes = installedFixes + (appIdStr to remoteVer)
                                                                "$localRes\n$it"
                                                            },
                                                            onFailure = { "$localRes\nBridge apply failed: ${it.message}" }
                                                        )
                                                    } else localRes
                                                },
                                                onFailure = { "Error: ${it.message}" }
                                            )
                                        }
                                    }
                                    if (!isInstalled) {
                                        Button(onClick = doApply) { Text("Apply") }
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = hasUpdate, onClick = doApply) { Text("Update") }
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    val appNum = appIdStr.toLongOrNull() ?: 0L
                                                    if (appNum > 0L &&
                                                        targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                                                        targetStatus.packageName != null
                                                    ) {
                                                        TargetAppBridge.removeFix(context, targetStatus.packageName, appNum).fold(
                                                            onSuccess = {
                                                                installedFixes = installedFixes - appIdStr
                                                                statusMsg = it
                                                            },
                                                            onFailure = { statusMsg = "Remove failed: ${it.message}" }
                                                        )
                                                    }
                                                }
                                            }) { Text("Remove") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    apiFixes.forEach { fix ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(fix.title, fontWeight = FontWeight.SemiBold)
                                if (!fix.description.isNullOrBlank()) {
                                    Text(fix.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (fix.hasFix) {
                                        Button(onClick = {
                                            scope.launch {
                                                statusMsg = "Downloading fix..."
                                                val tmp = File(context.cacheDir, "fix_${group.appId}.zip")
                                                val dl = apiClient.downloadDenuvoAsync(fix.id, "fix", tmp)
                                                statusMsg = dl.fold(
                                                    onSuccess = { zip ->
                                                        val localRes = luaInstaller.extractFixZip(zip, group.appId)
                                                            .getOrElse { "Extract error: ${it.message}" }
                                                        val appNum = group.appId.toLongOrNull() ?: 0L
                                                        if (appNum > 0L &&
                                                            targetStatus.mode == ConnectionMode.FULLY_CONNECTED &&
                                                            targetStatus.packageName != null
                                                        ) {
                                                            TargetAppBridge.applyFix(
                                                                context = context,
                                                                pkg = targetStatus.packageName,
                                                                appId = appNum,
                                                                gameName = group.name,
                                                                fixZipFile = zip
                                                            ).fold(
                                                                onSuccess = { "$localRes\n$it" },
                                                                onFailure = { "$localRes\nBridge apply failed: ${it.message}" }
                                                            )
                                                        } else localRes
                                                    },
                                                    onFailure = { "Error: ${it.message}" }
                                                )
                                            }
                                        }) { Text("Download Fix") }
                                    }
                                    if (fix.hasManifest) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                statusMsg = "Downloading fix manifest..."
                                                val tmp = File(context.cacheDir, "fix_manifest_${group.appId}.zip")
                                                val dl = apiClient.downloadDenuvoAsync(fix.id, "manifest", tmp)
                                                statusMsg = dl.fold(
                                                    onSuccess = { zip ->
                                                        val appNum = group.appId.toLongOrNull() ?: 0L
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
                                        }) { Text("Manifest") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedGroup = null }) {
                    Text("Close")
                }
            }
        )
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
    var detectedGameCount by remember { mutableStateOf(0) }
    var detectedGames by remember { mutableStateOf<List<DetectedCloudGame>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var manualAppId by remember { mutableStateOf("") }
    var manualCloudRoot by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(settings.autoSyncOnLaunch) }
    var cloudAccountId by remember { mutableStateOf(settings.cloudAccountId) }
    val nativeVer = remember(cloudService.isNativeAvailable) { cloudService.getNativeVersion() }

    val selectedOption = cloudProviderOptions.firstOrNull { it.id == selectedProviderId }
        ?: cloudProviderOptions.first()

    suspend fun refreshCloudState(
        providerId: String = selectedProviderId,
        forceRescan: Boolean = false,
        loadDetails: Boolean = false
    ) {
        val status = withContext(Dispatchers.IO) { cloudService.authStatus(providerId) }
        authenticated = status.optBoolean("authenticated", false)
        if (!authenticated) {
            detectedGameCount = 0
            detectedGames = emptyList()
            showDetails = false
            statusMsg = "Not connected to ${selectedOption.label}."
            return
        }

        // Use cached scan unless forceRescan is requested or no cache exists yet
        if (!forceRescan && settings.hasCachedCloudScan(providerId)) {
            val cached = settings.getCachedCloudGames(providerId)
                .map { DetectedCloudGame(accountId = it.first, appId = it.second) }
            detectedGameCount = cached.size
            if (loadDetails) {
                detectedGames = cached
                showDetails = true
            }
            statusMsg = "Connected to ${selectedOption.label}. ${cached.size} game(s) in cloud (cached)."
            return
        }

        scanning = true
        val scan = withContext(Dispatchers.IO) { cloudService.discoverCloudGames(providerId) }
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
            settings.setCachedCloudGames(providerId, found.map { it.accountId to it.appId })
            detectedGameCount = found.size
            if (found.isNotEmpty() && settings.cloudAccountId == "0") {
                settings.cloudAccountId = found.first().accountId
                cloudAccountId = settings.cloudAccountId
            }
            if (loadDetails) {
                detectedGames = found
                showDetails = true
            }
            statusMsg = if (found.isEmpty()) {
                "Connected to ${selectedOption.label}. No existing cloud games found yet."
            } else {
                "Connected to ${selectedOption.label}. ${found.size} game(s) in cloud."
            }
        } else {
            detectedGameCount = 0
            detectedGames = emptyList()
            statusMsg = scan.optString("error", "Failed to scan cloud games")
        }
        scanning = false
    }

    LaunchedEffect(selectedProviderId) {
        showDetails = false
        detectedGames = emptyList()
        refreshCloudState(selectedProviderId, forceRescan = false, loadDetails = false)
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
                    Column {
                        Text(
                            text = if (authenticated) "Status: Connected" else "Status: Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (authenticated) Color(0xFF81C784) else Color(0xFFFFB74D)
                        )
                        if (authenticated) {
                            Text(
                                text = "Cloud Games: $detectedGameCount",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (authenticated) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!showDetails && detectedGameCount > 0) {
                                OutlinedButton(
                                    enabled = !scanning,
                                    onClick = {
                                        scope.launch {
                                            refreshCloudState(selectedProviderId, forceRescan = false, loadDetails = true)
                                        }
                                    }
                                ) {
                                    Text("Show Details")
                                }
                            }
                            OutlinedButton(
                                enabled = !scanning,
                                onClick = {
                                    scope.launch {
                                        refreshCloudState(selectedProviderId, forceRescan = true, loadDetails = true)
                                    }
                                }
                            ) {
                                Text("Rescan")
                            }
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
                        onClick = { scope.launch { refreshCloudState(selectedOption.id, loadDetails = true) } }
                    ) {
                        Text("Check & Scan ${selectedOption.label}")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud Sync Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (nativeVer != null) "Native Core: v$nativeVer (loaded)" else "Native Core: not loaded",
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
                            "Runs automatically on game launch & exit",
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
                    value = cloudAccountId,
                    onValueChange = {
                        cloudAccountId = it
                        settings.cloudAccountId = it
                    },
                    label = { Text("Steam Account ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (scanning || oauthBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, style = MaterialTheme.typography.bodySmall)
        }

        if (showDetails && detectedGames.isNotEmpty()) {
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
                                val res = withContext(Dispatchers.IO) {
                                    File(targetRoot).mkdirs()
                                    cloudService.syncRemoteApp(
                                        settings.cloudProvider,
                                        game.accountId,
                                        game.appId,
                                        targetRoot
                                    )
                                }
                                settings.upsertCachedCloudGame(settings.cloudProvider, game.accountId, game.appId)
                                detectedGameCount = settings.getCachedCloudGames(settings.cloudProvider).size
                                statusMsg = "Sync AppID ${game.appId}: $res"
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
    authService: AuthService,
    targetStatus: TargetAppStatus,
    installedApps: List<TargetAppStatus>,
    safUri: Uri?,
    safValidation: SafValidationResult?,
    hubcapService: HubcapService,
    luaInstaller: LuaInstaller,
    onSelectTarget: (String) -> Unit,
    onRefreshBridge: () -> Unit,
    onGrantSaf: () -> Unit,
    onClearSaf: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var signedIn by remember { mutableStateOf(authService.isSignedIn) }
    var botCode by remember { mutableStateOf("") }
    var authStatusMsg by remember { mutableStateOf<String?>(null) }
    var authBusy by remember { mutableStateOf(false) }

    var autoUpdateApps by remember { mutableStateOf(settings.autoUpdateApps) }
    var downloadFolder by remember { mutableStateOf(settings.downloadFolder) }
    var hubcapKey by remember { mutableStateOf(settings.hubcapApiKey) }
    var hubcapStatus by remember { mutableStateOf<String?>(null) }

    var syncAllApps by remember { mutableStateOf(settings.syncAllConnectedApps) }
    var showSyncConfirmDialog by remember { mutableStateOf(false) }
    var syncingNow by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableFloatStateOf(0f) }
    var syncCurrentLabel by remember { mutableStateOf("") }
    var syncSummaryMsg by remember { mutableStateOf<String?>(null) }

    var showAddPlatformDialog by remember { mutableStateOf(false) }
    var addPlatformSelection by remember { mutableStateOf<TargetPlatform?>(null) }
    var addedPlatformIds by remember { mutableStateOf(settings.addedPlatformIds) }

    suspend fun runCrossAppSync() {
        val connectedTargets = installedApps.filter {
            it.mode == ConnectionMode.FULLY_CONNECTED && it.packageName != null
        }
        if (connectedTargets.isEmpty()) {
            syncSummaryMsg = "No connected bridge apps found to sync."
            return
        }
        syncingNow = true
        syncProgress = 0f
        syncCurrentLabel = "Scanning local games..."
        val localGames = withContext(Dispatchers.IO) { luaInstaller.listInstalledLuaGames() }
        val totalSteps = (localGames.size * connectedTargets.size).coerceAtLeast(1)
        var done = 0
        var okCount = 0
        val depotCacheDir = File(settings.downloadFolder, "depotcache")
        for (target in connectedTargets) {
            val pkg = target.packageName!!
            for (g in localGames) {
                syncCurrentLabel = "Pushing ${g.name} to ${target.appName} (${done + 1}/$totalSteps)..."
                val ok = withContext(Dispatchers.IO) {
                    val luaFile = File(settings.downloadFolder, "stplug-in/${g.appId}.lua")
                    val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
                    if (content.isNotBlank()) {
                        val res = TargetAppBridge.pushLuaGame(
                            context = context,
                            pkg = pkg,
                            appId = g.appId,
                            name = g.name,
                            luaContent = content,
                            depotCacheDir = depotCacheDir
                        )
                        if (res.isSuccess) settings.addAppSyncedId(pkg, g.appId)
                        res.isSuccess
                    } else {
                        false
                    }
                }
                if (ok) okCount++
                done++
                syncProgress = done.toFloat() / totalSteps.toFloat()
            }
        }
        syncingNow = false
        syncSummaryMsg = "Synced $okCount push(es) across ${connectedTargets.size} connected app(s)."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. LuaTools Account Login (moved from Home)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LuaTools Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

        // 2. Platforms & Bridges (multi-platform management)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Platforms & Bridges", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = {
                addPlatformSelection = null
                showAddPlatformDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        if (installedApps.isEmpty()) {
            Text(
                "No target emulator apps detected. Tap Add to see setup instructions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            installedApps.forEach { app ->
                val pkg = app.packageName ?: return@forEach
                val isSelected = syncAllApps || pkg == targetStatus.packageName
                val isConnected = app.mode == ConnectionMode.FULLY_CONNECTED
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected && isConnected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${app.appName} ($pkg)", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = when {
                                    syncAllApps && isConnected -> "Active (Synced v${app.bridgeVersion})"
                                    isConnected -> "Connected v${app.bridgeVersion}"
                                    else -> "Fallback"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) Color(0xFF81C784) else Color(0xFFFFB74D)
                            )
                        }
                        Text(app.detail, style = MaterialTheme.typography.bodySmall)
                        if (pkg == targetStatus.packageName && safValidation != null) {
                            Text(
                                text = "SAF: ${safValidation.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (safValidation.isValid) Color(0xFF81C784) else Color(0xFFFFB74D)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !syncAllApps && pkg != targetStatus.packageName,
                                onClick = { onSelectTarget(pkg) }
                            ) {
                                Text(if (syncAllApps) "Active (All)" else if (pkg == targetStatus.packageName) "Active" else "Set Active")
                            }
                            if (pkg == targetStatus.packageName || syncAllApps) {
                                OutlinedButton(onClick = onGrantSaf) {
                                    Text(if (safUri == null) "Link SAF" else "Change SAF")
                                }
                                if (pkg == targetStatus.packageName && safUri != null) {
                                    TextButton(onClick = onClearSaf) { Text("Clear SAF") }
                                }
                            }
                            OutlinedButton(onClick = onRefreshBridge) {
                                Text("Check")
                            }
                        }
                    }
                }
            }
        }

        // Cross-app synchronization toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sync Games & Options Across Apps")
                Text(
                    "Keep games & settings synchronized across all connected apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = syncAllApps,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        syncAllApps = true
                        showSyncConfirmDialog = true
                    } else {
                        syncAllApps = false
                        settings.syncAllConnectedApps = false
                    }
                }
            )
        }
        syncSummaryMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        // 3. LuaTools Options
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

        Spacer(Modifier.height(16.dp))
    }

    // Confirmation dialog for cross-app synchronization
    if (showSyncConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showSyncConfirmDialog = false
                syncAllApps = false
                settings.syncAllConnectedApps = false
            },
            title = { Text("Synchronize Connected Apps") },
            text = { Text("Start synchronization now?") },
            confirmButton = {
                Button(onClick = {
                    showSyncConfirmDialog = false
                    settings.syncAllConnectedApps = true
                    scope.launch { runCrossAppSync() }
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSyncConfirmDialog = false
                    syncAllApps = false
                    settings.syncAllConnectedApps = false
                }) {
                    Text("No")
                }
            }
        )
    }

    // Loading progress dialog during cross-app sync
    if (syncingNow) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Synchronizing Apps...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(syncCurrentLabel, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { syncProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Add Platform dialog with setup instructions
    if (showAddPlatformDialog) {
        AlertDialog(
            onDismissRequest = { showAddPlatformDialog = false },
            title = {
                Text(addPlatformSelection?.let { "Setup ${it.displayName}" } ?: "Add Platform")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (addPlatformSelection == null) {
                        TargetPlatform.entries.forEach { plat ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        addPlatformSelection = plat
                                        settings.addPlatform(plat.id)
                                        addedPlatformIds = settings.addedPlatformIds
                                    }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(plat.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(plat.subtitle, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        val plat = addPlatformSelection!!
                        val instructions = when (plat) {
                            TargetPlatform.BANNERHUB ->
                                "1. Install BannerHub 3.x APK (GameHub 5.3.5 base with bh_lua_bridge).\n" +
                                "2. Optionally link BannerHub's MTDataFilesProvider SAF folder for manual fallback.\n" +
                                "3. Tap Check Bridge to verify connection."
                            TargetPlatform.BANNERHUB_V6 ->
                                "1. Install BannerHub v6 APK (GameHub 6.3.1 base, banner.hub.v6 with bh_lua_bridge).\n" +
                                "2. Push games from LuaTools, then open Explore → LuaTools inside BannerHub v6 to download.\n" +
                                "3. Tap Check Bridge to verify connection."
                            TargetPlatform.WINNATIVE ->
                                "1. Install the modded GameNative APK (app.gamenative with LuaTools bridge).\n" +
                                "2. Tap Check Bridge to detect app.gamenative.bh_lua_bridge.\n" +
                                "3. Games pushed via LuaTools appear in GameNative automatically."
                            else -> "${plat.displayName} support is coming soon."
                        }
                        Text(instructions, style = MaterialTheme.typography.bodySmall)
                        if (plat == TargetPlatform.BANNERHUB || plat == TargetPlatform.BANNERHUB_V6) {
                            OutlinedButton(onClick = {
                                showAddPlatformDialog = false
                                onGrantSaf()
                            }) {
                                Text("Link SAF Folder")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (addPlatformSelection != null) {
                    Button(onClick = {
                        onRefreshBridge()
                        showAddPlatformDialog = false
                    }) {
                        Text("Check Bridge")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (addPlatformSelection != null) {
                        addPlatformSelection = null
                    } else {
                        showAddPlatformDialog = false
                    }
                }) {
                    Text(if (addPlatformSelection != null) "Back" else "Close")
                }
            }
        )
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
