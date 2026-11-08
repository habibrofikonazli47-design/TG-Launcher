package com.israadev.nuxlauncher.ui.screens

import android.media.MediaMetadataRetriever
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.israadev.nuxlauncher.R
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.download.MinecraftDownloader
import com.israadev.nuxlauncher.core.instance.InstanceManager
import com.israadev.nuxlauncher.core.launch.GameLauncher
import com.israadev.nuxlauncher.core.runtime.JavaRuntimeManager
import com.israadev.nuxlauncher.core.crash.CrashManager
import com.israadev.nuxlauncher.core.settings.SettingsManager
import com.israadev.nuxlauncher.ui.components.*
import com.israadev.nuxlauncher.core.renderer.NuxRendererRegistry
import com.israadev.nuxlauncher.core.renderer.NuxRendererInfo
import com.israadev.nuxlauncher.ui.dialogs.NuxRendererWarningDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxAddInstanceDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxEditInstanceDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxCrashDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxDeleteInstanceDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxDownloadProgressDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxUpdateDialog
import com.israadev.nuxlauncher.core.update.AndroidUpdateInfo
import com.israadev.nuxlauncher.core.update.UpdateManager
import com.israadev.nuxlauncher.core.mods.NuxAddonImportManager
import com.israadev.nuxlauncher.ui.dialogs.NuxAddonImportDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxAboutDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxPremiumDialog
import com.israadev.nuxlauncher.core.announcement.AnnouncementManager
import com.israadev.nuxlauncher.core.announcement.NuxAnnouncement
import com.israadev.nuxlauncher.ui.dialogs.NuxAnnouncementDialog
import com.israadev.nuxlauncher.ui.theme.NuxColors
import com.israadev.nuxlauncher.ui.theme.resp
import com.israadev.nuxlauncher.ui.theme.LocalNuxScale
import com.israadev.nuxlauncher.core.lang.tr
import com.israadev.nuxlauncher.core.lang.trStr
import kotlinx.coroutines.launch
import com.israadev.nuxlauncher.core.social.NuxSocialManager

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf("home") }

    val activeCrash by CrashManager.activeCrash.collectAsState()

    val instances by InstanceManager.instances.collectAsState()
    val selectedInstance by InstanceManager.selectedInstance.collectAsState()
    val currentAccount by AccountManager.currentAccount.collectAsState()
    val launcherUser by AccountManager.launcherUser.collectAsState()
    val launcherSettings by SettingsManager.settings.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditInstanceDialog by remember { mutableStateOf(false) }
    var instanceToEdit by remember { mutableStateOf<com.israadev.nuxlauncher.core.models.Instance?>(null) }
    var instanceToDelete by remember { mutableStateOf<com.israadev.nuxlauncher.core.models.Instance?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogInfo by remember { mutableStateOf<AndroidUpdateInfo?>(null) }
    var announcementDialogInfo by remember { mutableStateOf<NuxAnnouncement?>(null) }
    var pendingAnnouncement by remember { mutableStateOf<NuxAnnouncement?>(null) }
    var isUpdateCheckDone by remember { mutableStateOf(false) }
    var pendingLaunchInstance by remember { mutableStateOf<com.israadev.nuxlauncher.core.models.Instance?>(null) }
    var unsupportedRendererInfo by remember { mutableStateOf<NuxRendererInfo?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPremiumDialog by remember { mutableStateOf(false) }
    var premiumInitialPrompt by remember { mutableStateOf<String?>(null) }
    val pendingImport by NuxAddonImportManager.pendingImport.collectAsState()

    val handleRequestCreateInstance = {
        if (instances.size >= 3 && launcherUser?.isActivated != true) {
            premiumInitialPrompt = "Free accounts are limited to a maximum of 3 instances. Upgrade to NUX Premium to create unlimited instances!"
            showPremiumDialog = true
        } else {
            showAddDialog = true
        }
    }

    // Auto check update and announcement on launch
    LaunchedEffect(Unit) {
        // 0. Sinkronisasi status social & friends di background
        launch {
            try {
                NuxSocialManager.startSync()
            } catch (_: Exception) {}
        }

        // 1. Ambil announcement secara asynchronous di background
        launch {
            try {
                val ann = AnnouncementManager.fetchAnnouncement()
                if (ann != null && !AnnouncementManager.isDismissed(context, ann.id)) {
                    pendingAnnouncement = ann
                }
            } catch (e: Exception) {
                // Ignore announcement fetch failures
            }
        }

        // 2. Periksa ketersediaan update launcher
        try {
            val result = UpdateManager.checkForUpdate(context)
            result.onSuccess { info ->
                if (info.isUpdateAvailable) {
                    updateDialogInfo = info
                }
            }
        } catch (e: Exception) {
            // Ignore update check failures
        } finally {
            isUpdateCheckDone = true
        }
    }

    // Sequential Modal Coordinator: bila update check selesai dan TIDAK ADA update, segera tampilkan announcement
    LaunchedEffect(isUpdateCheckDone, updateDialogInfo, pendingAnnouncement) {
        if (isUpdateCheckDone && updateDialogInfo == null && pendingAnnouncement != null) {
            announcementDialogInfo = pendingAnnouncement
            pendingAnnouncement = null
        }
    }

    // Download progress state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadTargetName by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadMessage by remember { mutableStateOf("") }

    val downloader = remember { MinecraftDownloader(context) }

    var isVersionDropdownExpanded by remember { mutableStateOf(false) }
    var isAccountDropdownExpanded by remember { mutableStateOf(false) }

    // Instant Background Video Animation Picker (zero preview, instant swap)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    retriever.release()

                    if (durationMs > 20_500L) {
                        val seconds = (durationMs / 1000f).roundToInt()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Video duration is too long (${seconds}s)! Maximum is 20 seconds.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val destFile = File(context.filesDir, "hero_banner_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Delete old hero_banner files to save storage
                    context.filesDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("hero_banner") && file.absolutePath != destFile.absolutePath) {
                            file.delete()
                        }
                    }

                    val updated = launcherSettings.copy(
                        heroAnimationEnabled = true,
                        heroAnimationVideoPath = destFile.absolutePath
                    )
                    SettingsManager.updateSettings(context, updated)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Background animation updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to process video: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (currentTab == "gui_editor") {
        CustomGuiEditorScreen(
            onNavigateBack = { currentTab = "settings" },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // Outer Container: NUX Launcher Cyber-Emerald Green space
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuxColors.ForestGreen)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- 1. LEFT SIDEBAR (FLUSH TO LEFT SCREEN EDGE, ROUNDED ON RIGHT) ---
                NuxSidebar(
                    activeTab = currentTab,
                    onTabSelected = { tabId ->
                        if (tabId == "home" || tabId == "accounts" || tabId == "settings" || tabId == "mods" || tabId == "ai" || tabId == "friends") {
                            currentTab = tabId
                        } else {
                            Toast.makeText(context, "${tabId.replaceFirstChar { it.uppercase() }} feature coming soon to mobile!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenAbout = { showAboutDialog = true },
                    currentAccount = currentAccount,
                    launcherUser = launcherUser,
                    modifier = Modifier
                        .width((64.dp).resp())
                        .fillMaxHeight()
                )

                // Space between sidebar and main card (Nux green gap)
                Spacer(modifier = Modifier.width((8.dp).resp()))

                // --- 2. MAIN CARD (SURROUNDED BY GREEN ON TOP, END, BOTTOM) ---
                val mainCardShape = RoundedCornerShape((24.dp).resp())
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = (8.dp).resp(), bottom = (8.dp).resp(), end = (8.dp).resp())
                        .clip(mainCardShape)
                        .background(Color(0xFF09090B), mainCardShape)
                        .border(2.dp, Color(0xFF14171E), mainCardShape)
                ) {
                    val hasValidHeroVideo = launcherSettings.heroAnimationEnabled &&
                            launcherSettings.heroAnimationVideoPath.isNotBlank() &&
                            File(launcherSettings.heroAnimationVideoPath).exists()
                    val isHomeTab = currentTab == "home"

                    // --- SHARED VIDEO ANIMATION BACKGROUND FOR ALL TABS ---
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (!isHomeTab) Modifier.blur((18.dp).resp()) else Modifier)
                    ) {
                        if (hasValidHeroVideo) {
                            key(launcherSettings.heroAnimationVideoPath) {
                                HeroBannerVideoPlayer(
                                    videoPath = launcherSettings.heroAnimationVideoPath,
                                    rotationDegrees = launcherSettings.heroAnimationRotation,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.mc_hero_bg),
                                contentDescription = "Minecraft Scenery",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                alpha = if (isHomeTab) 0.45f else 0.25f
                            )
                        }
                    }

                    // Frosted dark overlay for non-home tabs (Accounts, Mods, AI, Settings)
                    if (!isHomeTab) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xD90A0D14))
                        )
                    }

                    if (currentTab == "accounts") {
                        AccountsScreen(
                            onNavigateBack = { currentTab = "home" },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (currentTab == "mods") {
                        ModsScreen(
                            onNavigateBack = { currentTab = "home" },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (currentTab == "ai") {
                        AIScreen(
                            onNavigateBack = { currentTab = "home" },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (currentTab == "settings") {
                        SettingsScreen(
                            onNavigateBack = { currentTab = "home" },
                            onOpenGuiEditor = { currentTab = "gui_editor" },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (currentTab == "friends") {
                        FriendsScreen(
                            onNavigateBack = { currentTab = "home" },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // --- CINEMATIC HOME BANNER VIEW (Matching Reference Screenshot) ---
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {

                            // 2. Gradients for Legibility
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((80.dp).resp())
                                    .align(Alignment.TopCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color(0x99000000), Color.Transparent)
                                        )
                                    )
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((130.dp).resp())
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color(0xCC000000))
                                        )
                                    )
                            )

                            // 3. TOP BAR INSIDE MAIN CARD (Frosted Glass Blur Items)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = (16.dp).resp(), vertical = (12.dp).resp()),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Top-Left: Account Switcher Pill [ (V) Username ˅ ]
                                val accounts by AccountManager.accounts.collectAsState()
                                val activeUsername = currentAccount?.username ?: launcherUser?.username ?: tr("dashboard.select_account")
                                val initialLetter = activeUsername.firstOrNull()?.uppercase() ?: "U"

                                Box {
                                    val accPillShape = RoundedCornerShape((20.dp).resp())
                                    Row(
                                        modifier = Modifier
                                            .clip(accPillShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(0xA6181B22), Color(0xBF0E1015))
                                                ),
                                                accPillShape
                                            )
                                            .border(
                                                1.dp,
                                                Brush.verticalGradient(
                                                    listOf(Color(0x66FFFFFF), Color(0x1F000000))
                                                ),
                                                accPillShape
                                            )
                                            .clickable { isAccountDropdownExpanded = true }
                                            .padding(horizontal = (8.dp).resp(), vertical = (5.dp).resp()),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy((8.dp).resp())
                                    ) {
                                        val photo = launcherUser?.photoURL ?: currentAccount?.photoUrl
                                        if (!photo.isNullOrBlank()) {
                                            NuxNetworkImage(
                                                model = photo,
                                                contentDescription = "Profile",
                                                fallbackInitials = activeUsername,
                                                modifier = Modifier.size((22.dp).resp()),
                                                shape = CircleShape
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size((22.dp).resp())
                                                    .background(NuxColors.ForestGreen, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = initialLetter,
                                                    color = Color(0xFF09090B),
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = (11.5.sp).resp()
                                                )
                                            }
                                        }

                                        Text(
                                            text = activeUsername,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (11.sp).resp(),
                                            maxLines = 1
                                        )

                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = tr("dashboard.switch_account"),
                                            tint = Color(0xFFA1A1AA),
                                            modifier = Modifier.size((16.dp).resp())
                                        )
                                    }

                                    // Account Dropdown with proper rounded corners and frosted glass
                                    DropdownMenu(
                                        expanded = isAccountDropdownExpanded,
                                        onDismissRequest = { isAccountDropdownExpanded = false },
                                        shape = RoundedCornerShape((18.dp).resp()),
                                        containerColor = Color(0xF212141C),
                                        shadowElevation = (16.dp).resp(),
                                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                                        modifier = Modifier.widthIn(min = (210.dp).resp())
                                    ) {
                                        if (accounts.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(tr("dashboard.no_accounts"), color = Color(0xFFA1A1AA), fontSize = (11.sp).resp()) },
                                                onClick = { }
                                            )
                                        } else {
                                            accounts.forEach { acc ->
                                                val isSelected = acc.id == currentAccount?.id
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = acc.username,
                                                                color = if (isSelected) NuxColors.MintGreen else Color.White,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                                fontSize = (12.sp).resp()
                                                            )
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = "Selected",
                                                                    tint = NuxColors.ForestGreen,
                                                                    modifier = Modifier.size((16.dp).resp())
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        AccountManager.selectAccount(acc)
                                                        isAccountDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }

                                        HorizontalDivider(color = Color(0x26FFFFFF), thickness = 1.dp)

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = tr("dashboard.manage_accounts"),
                                                    color = NuxColors.ForestGreen,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = (12.sp).resp()
                                                )
                                            },
                                            onClick = {
                                                isAccountDropdownExpanded = false
                                                currentTab = "accounts"
                                            }
                                        )
                                    }
                                }

                                // Top-Right: Frosted Glass Badges
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy((8.dp).resp())
                                ) {
                                    val badgeShape = RoundedCornerShape((16.dp).resp())
                                    Box(
                                        modifier = Modifier
                                            .clip(badgeShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(0xA6181B22), Color(0xBF0E1015))
                                                ),
                                                badgeShape
                                            )
                                            .border(
                                                1.dp,
                                                Brush.verticalGradient(
                                                    listOf(Color(0x66FFFFFF), Color(0x1F000000))
                                                ),
                                                badgeShape
                                            )
                                            .clickable { showAboutDialog = true }
                                            .padding(horizontal = (10.dp).resp(), vertical = (5.5.dp).resp()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy((5.dp).resp())
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size((5.5.dp).resp())
                                                    .background(NuxColors.Amber, CircleShape)
                                            )
                                            Text(
                                                text = tr("dashboard.unofficial_notice"),
                                                color = Color(0xFFD4D4D8),
                                                fontSize = (8.5.sp).resp(),
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (0.5.sp).resp()
                                            )
                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = "About & License",
                                                tint = Color(0xFFA1A1AA),
                                                modifier = Modifier.size((11.5.dp).resp())
                                            )
                                        }
                                    }

                                    // Button to rotate background video animation (0° -> 90° -> 180° -> 270°)
                                    val rotateBtnShape = RoundedCornerShape((12.dp).resp())
                                    Box(
                                        modifier = Modifier
                                            .size((32.dp).resp())
                                            .clip(rotateBtnShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(0xA6181B22), Color(0xBF0E1015))
                                                ),
                                                rotateBtnShape
                                            )
                                            .border(
                                                1.dp,
                                                Brush.verticalGradient(
                                                    listOf(Color(0x66FFFFFF), Color(0x1F000000))
                                                ),
                                                rotateBtnShape
                                            )
                                            .clickable {
                                                val nextRotation = (launcherSettings.heroAnimationRotation + 90) % 360
                                                SettingsManager.updateSettings(
                                                    context,
                                                    launcherSettings.copy(heroAnimationRotation = nextRotation)
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                                            contentDescription = tr("dashboard.rotate_video"),
                                            tint = Color.White,
                                            modifier = Modifier.size((17.dp).resp())
                                        )
                                    }

                                    // Button to change background animation instantly
                                    val imgBtnShape = RoundedCornerShape((12.dp).resp())
                                    Box(
                                        modifier = Modifier
                                            .size((32.dp).resp())
                                            .clip(imgBtnShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(0xA6181B22), Color(0xBF0E1015))
                                                ),
                                                imgBtnShape
                                            )
                                            .border(
                                                1.dp,
                                                Brush.verticalGradient(
                                                    listOf(Color(0x66FFFFFF), Color(0x1F000000))
                                                ),
                                                imgBtnShape
                                            )
                                            .clickable {
                                                videoPickerLauncher.launch("video/*")
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = tr("dashboard.change_video"),
                                            tint = Color.White,
                                            modifier = Modifier.size((17.dp).resp())
                                        )
                                    }
                                }
                            }

                            // 4. FLOATING BOTTOM LAUNCH BAR (Frosted Glass Capsule matching Image 3)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = (16.dp).resp(), vertical = (12.dp).resp())
                            ) {
                                val launchBarShape = RoundedCornerShape((32.dp).resp())
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(launchBarShape)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color(0xB3181B22), Color(0xCC0E1015))
                                            ),
                                            launchBarShape
                                        )
                                        .border(
                                            1.dp,
                                            Brush.verticalGradient(
                                                listOf(Color(0x66FFFFFF), Color(0x1F000000))
                                            ),
                                            launchBarShape
                                        )
                                        .padding(horizontal = (12.dp).resp(), vertical = (8.dp).resp()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Instance Index Box "1" (In Nux Green, rounded square matching Image 3)
                                    val instanceIndex = if (selectedInstance != null) {
                                        (instances.indexOfFirst { it.id == selectedInstance!!.id }.takeIf { it >= 0 } ?: 0) + 1
                                    } else {
                                        1
                                    }
                                    val numBoxShape = RoundedCornerShape((16.dp).resp())
                                    Box(
                                        modifier = Modifier
                                            .size((44.dp).resp())
                                            .background(NuxColors.ForestGreen, numBoxShape)
                                            .clip(numBoxShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$instanceIndex",
                                            color = Color(0xFF09090B),
                                            fontWeight = FontWeight.Black,
                                            fontSize = (17.sp).resp()
                                        )
                                    }

                                    Spacer(modifier = Modifier.width((12.dp).resp()))

                                    // Version Section (Clickable to open dropdown)
                                    Box(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape((8.dp).resp()))
                                                .clickable { isVersionDropdownExpanded = true }
                                                .padding(horizontal = (4.dp).resp(), vertical = (2.dp).resp())
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = selectedInstance?.name ?: tr("dashboard.select_instance"),
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = (15.5.sp).resp(),
                                                    letterSpacing = (-0.2).sp,
                                                    maxLines = 1
                                                )
                                                Spacer(modifier = Modifier.width((6.dp).resp()))
                                                Box(
                                                    modifier = Modifier
                                                        .size((18.dp).resp())
                                                        .background(Color(0x33FFFFFF), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.KeyboardArrowDown,
                                                        contentDescription = tr("dashboard.select_version"),
                                                        tint = Color.White,
                                                        modifier = Modifier.size((14.dp).resp())
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height((3.dp).resp()))

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy((5.dp).resp()),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (selectedInstance != null) {
                                                    val inst = selectedInstance!!
                                                    // Loader tag (in NUX green! Was yellow in screenshot)
                                                    Box(
                                                        modifier = Modifier
                                                            .background(NuxColors.ForestGreen, RoundedCornerShape((8.dp).resp()))
                                                            .padding(horizontal = (7.dp).resp(), vertical = (2.dp).resp())
                                                    ) {
                                                        Text(
                                                            text = inst.loader.replaceFirstChar { it.uppercase() },
                                                            color = Color(0xFF09090B),
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = (9.5.sp).resp()
                                                        )
                                                    }

                                                    // Version tag
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0x2EFFFFFF), RoundedCornerShape((8.dp).resp()))
                                                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape((8.dp).resp()))
                                                            .padding(horizontal = (7.dp).resp(), vertical = (2.dp).resp())
                                                    ) {
                                                        Text(
                                                            text = inst.mcVersion,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = (9.5.sp).resp()
                                                        )
                                                    }

                                                    // Java runtime tag
                                                    val activeRuntime = if (inst.javaRuntime != "auto") inst.javaRuntime
                                                        else JavaRuntimeManager.getRecommendedRuntime(inst.mcVersion)
                                                    val jreLabel = activeRuntime.replace("jre-", "Java ")
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0x2EFFFFFF), RoundedCornerShape((8.dp).resp()))
                                                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape((8.dp).resp()))
                                                            .padding(horizontal = (7.dp).resp(), vertical = (2.dp).resp())
                                                    ) {
                                                        Text(
                                                            text = jreLabel,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = (9.5.sp).resp()
                                                        )
                                                    }
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0x2EFFFFFF), RoundedCornerShape((8.dp).resp()))
                                                            .padding(horizontal = (7.dp).resp(), vertical = (2.dp).resp())
                                                    ) {
                                                        Text(
                                                            text = tr("dashboard.no_version_selected"),
                                                            color = Color.White,
                                                            fontSize = (9.5.sp).resp()
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Dropdown Menu for Installed Versions (With proper rounded shape & blur)
                                        DropdownMenu(
                                            expanded = isVersionDropdownExpanded,
                                            onDismissRequest = { isVersionDropdownExpanded = false },
                                            shape = RoundedCornerShape((18.dp).resp()),
                                            containerColor = Color(0xF212141C),
                                            shadowElevation = (16.dp).resp(),
                                            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                                            modifier = Modifier.widthIn(min = (250.dp).resp(), max = (330.dp).resp())
                                        ) {
                                            if (instances.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = tr("dashboard.no_instances"),
                                                            color = Color(0xFFA1A1AA),
                                                            fontSize = (11.sp).resp()
                                                        )
                                                    },
                                                    onClick = { }
                                                )
                                            } else {
                                                instances.forEach { inst ->
                                                    val isSelected = inst.id == selectedInstance?.id
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        text = inst.name,
                                                                        color = if (isSelected) NuxColors.MintGreen else Color.White,
                                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                                        fontSize = (12.sp).resp(),
                                                                        maxLines = 1
                                                                    )
                                                                    Text(
                                                                        text = "${inst.loader.uppercase()} • ${inst.mcVersion}",
                                                                        color = Color(0xFFA1A1AA),
                                                                        fontSize = (9.5.sp).resp()
                                                                    )
                                                                }
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy((6.dp).resp())
                                                                ) {
                                                                    if (isSelected) {
                                                                        Icon(
                                                                            imageVector = Icons.Default.Check,
                                                                            contentDescription = tr("common.selected"),
                                                                            tint = NuxColors.ForestGreen,
                                                                            modifier = Modifier.size((16.dp).resp())
                                                                        )
                                                                    }
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size((28.dp).resp())
                                                                            .clip(CircleShape)
                                                                            .background(Color(0x26FFFFFF), CircleShape)
                                                                            .clickable {
                                                                                isVersionDropdownExpanded = false
                                                                                instanceToEdit = inst
                                                                            },
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Icon(
                                                                            imageVector = Icons.Outlined.Settings,
                                                                            contentDescription = tr("dashboard.instance_settings"),
                                                                            tint = Color(0xFFD4D4D8),
                                                                            modifier = Modifier.size((15.dp).resp())
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        onClick = {
                                                            InstanceManager.selectInstance(inst)
                                                            isVersionDropdownExpanded = false
                                                        }
                                                    )
                                                }
                                            }

                                            HorizontalDivider(color = Color(0x26FFFFFF), thickness = 1.dp)

                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy((8.dp).resp())
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Add,
                                                            contentDescription = tr("common.add"),
                                                            tint = NuxColors.ForestGreen,
                                                            modifier = Modifier.size((18.dp).resp())
                                                        )
                                                        Text(
                                                            text = tr("dashboard.add_new_instance"),
                                                            color = NuxColors.ForestGreen,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = (12.5.sp).resp()
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    isVersionDropdownExpanded = false
                                                    handleRequestCreateInstance()
                                                }
                                            )
                                        }
                                    }

                                    // Big PLAY Button (In Nux Green! Was yellow in screenshot)
                                    val inst = selectedInstance
                                    val isFullyDownloaded = inst != null && inst.isDownloaded && InstanceManager.isInstanceDownloaded(context, inst)
                                    val playBtnShape = RoundedCornerShape((24.dp).resp())

                                    Row(
                                        modifier = Modifier
                                            .clip(playBtnShape)
                                            .background(NuxColors.ForestGreen, playBtnShape)
                                            .clickable {
                                                if (inst == null) {
                                                    handleRequestCreateInstance()
                                                    return@clickable
                                                }

                                                val account = currentAccount
                                                if (account == null) {
                                                    Toast.makeText(context, trStr("dashboard.toast_select_account"), Toast.LENGTH_SHORT).show()
                                                    currentTab = "accounts"
                                                    return@clickable
                                                }

                                                if (!isFullyDownloaded) {
                                                    val targetRuntime = JavaRuntimeManager.getRecommendedRuntime(inst.mcVersion)
                                                    isDownloading = true
                                                    downloadTargetName = inst.name
                                                    downloadProgress = 0f
                                                    downloadMessage = trStr("dashboard.download_preparing_openjdk", JavaRuntimeManager.getRuntimeDisplayName(targetRuntime))

                                                    scope.launch {
                                                        JavaRuntimeManager.extractRuntime(context, targetRuntime) { p, msg ->
                                                            downloadProgress = p
                                                            downloadMessage = msg
                                                        }

                                                        val res = downloader.downloadInstance(inst) { p, msg ->
                                                            downloadProgress = p
                                                            downloadMessage = msg
                                                        }
                                                        isDownloading = false
                                                        if (res.isSuccess) {
                                                            Toast.makeText(context, trStr("dashboard.toast_install_complete"), Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, trStr("dashboard.toast_download_failed", res.exceptionOrNull()?.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                } else {
                                                    val targetRuntime = selectedInstance?.let { JavaRuntimeManager.getRecommendedRuntime(it.mcVersion) } ?: "jre-21"
                                                    if (!JavaRuntimeManager.isRuntimeInstalled(context, targetRuntime)) {
                                                        isDownloading = true
                                                        downloadTargetName = inst.name
                                                        downloadProgress = 0f
                                                        downloadMessage = trStr("dashboard.download_preparing_openjdk", JavaRuntimeManager.getRuntimeDisplayName(targetRuntime))
                                                        scope.launch {
                                                            val extRes = JavaRuntimeManager.extractRuntime(context, targetRuntime) { p, msg ->
                                                                downloadProgress = p
                                                                downloadMessage = msg
                                                            }
                                                            isDownloading = false
                                                            if (extRes.isSuccess) {
                                                                Toast.makeText(context, trStr("dashboard.toast_launching", inst.name), Toast.LENGTH_SHORT).show()
                                                                GameLauncher.launch(context, inst, account)
                                                            } else {
                                                                Toast.makeText(context, trStr("dashboard.toast_openjdk_failed", extRes.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                        return@clickable
                                                    }

                                                    val currentRendererInfo = NuxRendererRegistry.findRendererById(launcherSettings.selectedRenderer)
                                                    val isSupported = NuxRendererRegistry.isSupportedForVersion(currentRendererInfo, inst.mcVersion)
                                                    if (!isSupported) {
                                                        unsupportedRendererInfo = currentRendererInfo
                                                        pendingLaunchInstance = inst
                                                    } else {
                                                        Toast.makeText(context, trStr("dashboard.toast_launching", inst.name), Toast.LENGTH_SHORT).show()
                                                        GameLauncher.launch(context, inst, account)
                                                    }
                                                }
                                            }
                                            .padding(horizontal = (26.dp).resp(), vertical = (12.dp).resp()),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy((6.dp).resp())
                                    ) {
                                        Icon(
                                            imageVector = if (inst == null) Icons.Default.Add else if (isFullyDownloaded) Icons.Default.PlayArrow else Icons.Default.Download,
                                            contentDescription = "Action",
                                            tint = Color(0xFF09090B),
                                            modifier = Modifier.size((20.dp).resp())
                                        )
                                        Text(
                                            text = if (inst == null) tr("dashboard.play_new_instance") else if (isFullyDownloaded) tr("dashboard.play_button") else tr("dashboard.play_download"),
                                            color = Color(0xFF09090B),
                                            fontWeight = FontWeight.Black,
                                            fontSize = (15.sp).resp(),
                                            letterSpacing = (0.5.sp).resp()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Voice Bar
            if (currentTab != "friends") {
                FloatingVoiceBar(
                    onOpenVoiceRoom = { currentTab = "friends" },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (14.dp).resp(), end = (18.dp).resp())
                )
            }
        }

        // Add Instance Dialog
        if (showAddDialog) {
            NuxAddInstanceDialog(
                onDismiss = { showAddDialog = false },
                onInstanceCreated = { newInst ->
                    showAddDialog = false
                    InstanceManager.createInstance(context, newInst)
                    Toast.makeText(context, trStr("dialogs.add_instance.toast_created", newInst.name), Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Edit Instance Dialog
        val targetEditInstance = instanceToEdit ?: if (showEditInstanceDialog) selectedInstance else null
        targetEditInstance?.let { inst ->
            NuxEditInstanceDialog(
                instance = inst,
                onDismiss = {
                    instanceToEdit = null
                    showEditInstanceDialog = false
                },
                onInstanceUpdated = { updated ->
                    instanceToEdit = null
                    showEditInstanceDialog = false
                    InstanceManager.updateInstance(context, updated)
                },
                onInstanceDeleted = { toDelete ->
                    instanceToEdit = null
                    showEditInstanceDialog = false
                    InstanceManager.deleteInstance(context, toDelete.id)
                    Toast.makeText(context, trStr("dialogs.delete_instance.toast_deleted", toDelete.name), Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Delete Instance Confirmation Dialog
        instanceToDelete?.let { inst ->
            NuxDeleteInstanceDialog(
                instance = inst,
                onConfirm = {
                    instanceToDelete = null
                    InstanceManager.deleteInstance(context, inst.id)
                    Toast.makeText(context, trStr("dialogs.delete_instance.toast_deleted", inst.name), Toast.LENGTH_SHORT).show()
                },
                onDismiss = { instanceToDelete = null }
            )
        }

        // Download Progress Dialog
        if (isDownloading) {
            NuxDownloadProgressDialog(
                instanceName = downloadTargetName,
                progress = downloadProgress,
                message = downloadMessage
            )
        }

        // Launcher Update Dialog
        updateDialogInfo?.let { info ->
            NuxUpdateDialog(
                updateInfo = info,
                onDismiss = {
                    updateDialogInfo = null
                    // Sequential: setelah user menutup/menolak update, tampilkan announcement jika ada
                    pendingAnnouncement?.let { ann ->
                        announcementDialogInfo = ann
                        pendingAnnouncement = null
                    }
                }
            )
        }

        // Launcher Announcement Dialog (Anti-Bentrok: hanya tampil jika tidak ada update dialog yang aktif)
        if (updateDialogInfo == null) {
            announcementDialogInfo?.let { ann ->
                NuxAnnouncementDialog(
                    announcement = ann,
                    onDismiss = {
                        announcementDialogInfo = null
                    }
                )
            }
        }

        // Unsupported Renderer Warning Dialog (Persis seperti Zalith)
        val warnRenderer = unsupportedRendererInfo
        val launchInst = pendingLaunchInstance
        if (warnRenderer != null && launchInst != null) {
            NuxRendererWarningDialog(
                renderer = warnRenderer,
                mcVersion = launchInst.mcVersion,
                onConfirm = {
                    val toLaunch = pendingLaunchInstance
                    val account = currentAccount
                    unsupportedRendererInfo = null
                    pendingLaunchInstance = null
                    if (toLaunch != null && account != null) {
                        Toast.makeText(context, trStr("dashboard.toast_launching", toLaunch.name), Toast.LENGTH_SHORT).show()
                        GameLauncher.launch(context, toLaunch, account)
                    }
                },
                onDismiss = {
                    unsupportedRendererInfo = null
                    pendingLaunchInstance = null
                }
            )
        }

        // Game Crash Popup Dialog
        activeCrash?.let { crash ->
            NuxCrashDialog(
                crashInfo = crash,
                onDismiss = {
                    CrashManager.dismissCrash(context)
                }
            )
        }

        // External Addon Import Dialog (Open With from File Manager)
        pendingImport?.let { importItem ->
            NuxAddonImportDialog(
                pendingImport = importItem,
                onDismiss = {
                    NuxAddonImportManager.clearPendingImport()
                }
            )
        }

        // About & Open Source Licenses Dialog (GPL-3.0 & Zalith Compliance)
        if (showAboutDialog) {
            NuxAboutDialog(
                onDismissRequest = { showAboutDialog = false }
            )
        }

        // NUX Premium & Showcase Dialog (Fitur 1 - 7 Eksklusif)
        if (showPremiumDialog) {
            NuxPremiumDialog(
                initialPrompt = premiumInitialPrompt,
                onDismissRequest = {
                    showPremiumDialog = false
                    premiumInitialPrompt = null
                }
            )
        }
    }
}

/**
 * Quick Action Card matching the PC Launcher-Windows aesthetic (Image 2)
 */
@Composable
fun QuickActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    val cardShape = RoundedCornerShape((11.dp).resp())
    val bgColor = if (isDestructive) Color(0xFF191116) else Color(0xFF12141A)
    val borderColor = if (isDestructive) Color(0xFFF43F5E).copy(alpha = 0.25f) else Color(0x1FFFFFFF)

    Box(
        modifier = modifier
            .clip(cardShape)
            .background(bgColor, cardShape)
            .border(1.dp, borderColor, cardShape)
            .clickable { onClick() }
            .padding((8.dp).resp())
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Top icon container
                val iconShape = RoundedCornerShape((6.dp).resp())
                Box(
                    modifier = Modifier
                        .size((24.dp).resp())
                        .clip(iconShape)
                        .background(accentColor.copy(alpha = 0.12f), iconShape)
                        .border(1.dp, accentColor.copy(alpha = 0.25f), iconShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size((14.dp).resp())
                    )
                }

                Spacer(modifier = Modifier.height((4.dp).resp()))

                // Title
                Text(
                    text = title,
                    color = if (isDestructive) Color(0xFFFECDD3) else Color.White,
                    fontSize = (9.5.sp).resp(),
                    fontWeight = FontWeight.Black,
                    lineHeight = (11.5.sp).resp(),
                    letterSpacing = (0.3.sp).resp()
                )

                Spacer(modifier = Modifier.height((2.dp).resp()))

                // Accent line
                Box(
                    modifier = Modifier
                        .size(width = (16.dp).resp(), height = (1.5.dp).resp())
                        .background(accentColor.copy(alpha = 0.45f), CircleShape)
                )
            }

            // Description
            Text(
                text = description,
                color = Color(0xFFA1A1AA),
                fontSize = (8.sp).resp(),
                lineHeight = (9.5.sp).resp(),
                maxLines = 2
            )
        }
    }
}