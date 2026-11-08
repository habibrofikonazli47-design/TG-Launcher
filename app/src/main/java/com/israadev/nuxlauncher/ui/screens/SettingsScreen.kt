package com.israadev.nuxlauncher.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.israadev.nuxlauncher.core.lang.NuxLanguageManager
import com.israadev.nuxlauncher.core.lang.tr
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israadev.nuxlauncher.R
import com.israadev.nuxlauncher.GameActivity
import java.io.File
import java.io.FileOutputStream
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.auth.AuthService
import com.israadev.nuxlauncher.core.crash.AICrashAnalyzer
import com.israadev.nuxlauncher.core.models.LauncherSettings
import com.israadev.nuxlauncher.core.renderer.NuxRendererRegistry
import com.israadev.nuxlauncher.core.renderer.NuxRendererPluginManager
import com.israadev.nuxlauncher.core.renderer.NuxRendererInfo
import com.israadev.nuxlauncher.core.settings.SettingsManager
import com.israadev.nuxlauncher.core.recorder.ScreenRecordManager
import com.israadev.nuxlauncher.core.utils.NuxVersionUtils
import com.israadev.nuxlauncher.ui.components.*
import com.israadev.nuxlauncher.ui.dialogs.NuxRendererV2ConfigDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxAboutDialog
import com.israadev.nuxlauncher.ui.dialogs.NuxPremiumDialog
import com.israadev.nuxlauncher.ui.theme.NuxColors
import com.israadev.nuxlauncher.ui.theme.NuxSizes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenGuiEditor: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSettings by SettingsManager.settings.collectAsState()
    val launcherUser by AccountManager.launcherUser.collectAsState()

    var activeSettingsTab by remember { mutableStateOf("profile") } // "profile" or "game"
    val availableLanguages by NuxLanguageManager.availableLanguages.collectAsState()
    val activeLanguage by NuxLanguageManager.currentLanguage.collectAsState()
    var isTopLangMenuExpanded by remember { mutableStateOf(false) }
    var isCardLangMenuExpanded by remember { mutableStateOf(false) }

    // Profile state
    var editUsername by remember(launcherUser?.username) { mutableStateOf(launcherUser?.username ?: "") }
    var isSavingUsername by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var selectedPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPremiumDialog by remember { mutableStateOf(false) }
    var premiumInitialPrompt by remember { mutableStateOf<String?>(null) }


    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && launcherUser != null) {
            selectedPreviewUri = uri
            scope.launch {
                isUploadingAvatar = true
                val uploadRes = AuthService.uploadProfileImage(context, uri)
                uploadRes.onSuccess { cloudUrl ->
                    val updateRes = AuthService.updateProfile(
                        uid = launcherUser!!.uid,
                        photoURL = cloudUrl
                    )
                    updateRes.onSuccess {
                        AccountManager.updateProfile(context, newPhotoUrl = cloudUrl)
                        Toast.makeText(context, "Profile picture updated successfully!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(context, "Failed to update profile: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(context, "Failed to upload image: ${err.message}", Toast.LENGTH_LONG).show()
                }
                isUploadingAvatar = false
            }
        }
    }

    var aiAutoAnalyze by remember(currentSettings.aiAutoAnalyze) { mutableStateOf(currentSettings.aiAutoAnalyze) }
    var aiApiKey by remember(currentSettings.aiApiKey) { mutableStateOf(currentSettings.aiApiKey) }
    var aiModel by remember(currentSettings.aiModel) { mutableStateOf(currentSettings.aiModel) }
    var recorderResolution by remember(currentSettings.recorderResolution) { mutableStateOf(currentSettings.recorderResolution) }
    var recorderFps by remember(currentSettings.recorderFps) { mutableIntStateOf(currentSettings.recorderFps) }
    var recorderAudioEnabled by remember(currentSettings.recorderAudioEnabled) { mutableStateOf(currentSettings.recorderAudioEnabled) }
    var isTestingAiConnection by remember { mutableStateOf(false) }
    var aiConnectionTestResult by remember { mutableStateOf<String?>(null) }
    var isAiConnectionSuccess by remember { mutableStateOf(false) }
    var showApiKeyPlaintext by remember { mutableStateOf(false) }

    fun handleSaveUsername() {
        val cleanName = editUsername.trim()
        if (cleanName.isBlank()) {
            usernameError = "Username cannot be empty"
            return
        }
        if (cleanName.length < 3 || cleanName.length > 20) {
            usernameError = "Username must be 3 - 20 characters"
            return
        }
        if (!cleanName.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            usernameError = "Letters, numbers, and underscores (_) only"
            return
        }
        val user = launcherUser ?: return
        val uid = user.uid
        if (cleanName == user.username) {
            usernameError = null
            Toast.makeText(context, "Username unchanged.", Toast.LENGTH_SHORT).show()
            return
        }

        usernameError = null
        isSavingUsername = true
        scope.launch {
            val res = AuthService.updateProfile(uid = uid, newUsername = cleanName)
            res.onSuccess {
                AccountManager.updateProfile(context, newUsername = cleanName)
                Toast.makeText(context, "Username successfully changed to $cleanName!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                usernameError = err.message ?: "Failed to update username"
                Toast.makeText(context, err.message ?: "Failed to update username", Toast.LENGTH_LONG).show()
            }
            isSavingUsername = false
        }
    }

    // Local mutable state for instant responsive UI before saving
    var ramMb by remember(currentSettings.ramMb) { mutableIntStateOf(currentSettings.ramMb) }
    var initialHeapMb by remember(currentSettings.initialHeapMb) { mutableIntStateOf(currentSettings.initialHeapMb) }
    var customJvmArgs by remember(currentSettings.customJvmArgs) { mutableStateOf(currentSettings.customJvmArgs) }
    var defaultRuntime by remember(currentSettings.defaultJavaRuntime) { mutableStateOf(currentSettings.defaultJavaRuntime) }

    var mouseMode by remember(currentSettings.mouseControlMode) { mutableStateOf(currentSettings.mouseControlMode) }
    var cursorSensitivity by remember(currentSettings.cursorSensitivity) { mutableIntStateOf(currentSettings.cursorSensitivity) }
    var captureSensitivity by remember(currentSettings.captureSensitivity) { mutableIntStateOf(currentSettings.captureSensitivity) }
    var mouseSizeDp by remember(currentSettings.mouseSizeDp) { mutableIntStateOf(currentSettings.mouseSizeDp) }
    var physicalMouseMode by remember(currentSettings.physicalMouseMode) { mutableStateOf(currentSettings.physicalMouseMode) }

    var resolutionRatio by remember(currentSettings.resolutionRatio) { mutableIntStateOf(currentSettings.resolutionRatio) }
    var autoOptimizeMC by remember(currentSettings.autoOptimizeMinecraft) { mutableStateOf(currentSettings.autoOptimizeMinecraft) }
    var sustainedPerf by remember(currentSettings.sustainedPerformanceMode) { mutableStateOf(currentSettings.sustainedPerformanceMode) }
    var selectedRenderer by remember(currentSettings.selectedRenderer) { mutableStateOf(currentSettings.selectedRenderer) }
    var vulkanDriver by remember(currentSettings.vulkanDriver) { mutableStateOf(currentSettings.vulkanDriver) }
    var graphicsApi by remember(currentSettings.graphicsApi) { mutableStateOf(currentSettings.graphicsApi) }
    var zinkPreferSystemDriver by remember(currentSettings.zinkPreferSystemDriver) { mutableStateOf(currentSettings.zinkPreferSystemDriver) }
    var vsyncInZink by remember(currentSettings.vsyncInZink) { mutableStateOf(currentSettings.vsyncInZink) }
    var showRendererDialog by remember { mutableStateOf(false) }
    var showRendererConfigDialog by remember { mutableStateOf(false) }
    var selectedConfigRenderer by remember { mutableStateOf<NuxRendererInfo?>(null) }
    var showAdrenoWarningDialog by remember { mutableStateOf(false) }

    val totalRamMb = remember { SettingsManager.getTotalDeviceMemoryMb(context) }
    val maxAllocatableRam = remember(totalRamMb) { (totalRamMb * 0.85f).toInt().coerceAtLeast(1024) }
    val cpuCores = remember { SettingsManager.getCpuCoreCount() }

    fun commitSettings() {
        val updated = currentSettings.copy(
            ramMb = ramMb,
            initialHeapMb = initialHeapMb,
            customJvmArgs = customJvmArgs,
            defaultJavaRuntime = defaultRuntime,
            mouseControlMode = mouseMode,
            cursorSensitivity = cursorSensitivity,
            captureSensitivity = captureSensitivity,
            mouseSizeDp = mouseSizeDp,
            physicalMouseMode = physicalMouseMode,
            resolutionRatio = resolutionRatio,
            autoOptimizeMinecraft = autoOptimizeMC,
            sustainedPerformanceMode = sustainedPerf,
            selectedRenderer = selectedRenderer,
            vulkanDriver = vulkanDriver,
            graphicsApi = graphicsApi,
            zinkPreferSystemDriver = zinkPreferSystemDriver,
            vsyncInZink = vsyncInZink,
            heroAnimationEnabled = currentSettings.heroAnimationEnabled,
            heroAnimationVideoPath = currentSettings.heroAnimationVideoPath,
            heroAnimationRotation = currentSettings.heroAnimationRotation,
            aiAutoAnalyze = aiAutoAnalyze,
            aiApiKey = aiApiKey,
            aiModel = aiModel
        )
        SettingsManager.updateSettings(context, updated)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        // =========================================================================
        // 1. TOP HEADER BAR (Minimalist Cyber-Glass matching Home, Accounts & Mods)
        // =========================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Back to Dashboard Button (Standard 26.dp)
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                        .clickable { onNavigateBack() }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = NuxColors.ForestGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr("settings.dashboard_nav"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Text(
                    text = tr("settings.title"),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    letterSpacing = 0.8.sp
                )

                // Segmented Mode Tabs: [ PROFIL AKUN | PREFERENSI GAME ]
                Row(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isProfileTab = activeSettingsTab == "profile"
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isProfileTab) NuxColors.ForestGreen else Color.Transparent
                            )
                            .clickable { activeSettingsTab = "profile" }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = if (isProfileTab) Color.White else NuxColors.GrayNeutral,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tr("settings.tab_profile"),
                                color = if (isProfileTab) Color.White else NuxColors.GrayNeutral,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            )
                        }
                    }

                    val isGameTab = activeSettingsTab == "game"
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isGameTab) NuxColors.ForestGreen else Color.Transparent
                            )
                            .clickable { activeSettingsTab = "game" }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = if (isGameTab) Color.White else NuxColors.GrayNeutral,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tr("settings.tab_game"),
                                color = if (isGameTab) Color.White else NuxColors.GrayNeutral,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            )
                        }
                    }

                    // TAB 3: AI Analitik Crash
                    val isAiTab = activeSettingsTab == "ai"
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isAiTab) NuxColors.ForestGreen else Color.Transparent
                            )
                            .clickable { activeSettingsTab = "ai" }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = null,
                                tint = if (isAiTab) Color.White else NuxColors.GrayNeutral,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tr("settings.tab_ai"),
                                color = if (isAiTab) Color.White else NuxColors.GrayNeutral,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
            }

            // Top Right: Language Dropdown Capsule & Hardware Specs Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive Language Selector Capsule
                Box {
                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                            .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .clickable { isTopLangMenuExpanded = true }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = activeLanguage.flag, fontSize = 12.sp)
                            Text(
                                text = activeLanguage.name,
                                color = NuxColors.MintGreen,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Language",
                                tint = Color(0xFFA1A1AA),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isTopLangMenuExpanded,
                        onDismissRequest = { isTopLangMenuExpanded = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = Color(0xF212141C),
                        shadowElevation = 16.dp,
                        border = BorderStroke(1.dp, Color(0x33FFFFFF))
                    ) {
                        availableLanguages.forEach { lang ->
                            val isSelected = lang.code.equals(activeLanguage.code, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(text = lang.flag, fontSize = 15.sp)
                                            Column {
                                                Text(
                                                    text = lang.name,
                                                    color = if (isSelected) NuxColors.MintGreen else Color.White,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 11.sp
                                                )
                                                if (lang.author.isNotBlank()) {
                                                    Text(
                                                        text = "By ${lang.author}",
                                                        color = Color(0xFFA1A1AA),
                                                        fontSize = 8.5.sp
                                                    )
                                                }
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = NuxColors.ForestGreen,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    NuxLanguageManager.setLanguage(context, lang.code)
                                    isTopLangMenuExpanded = false
                                    Toast.makeText(context, "${lang.flag} ${lang.name}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${totalRamMb / 1024} GB RAM",
                        color = NuxColors.MintGreen,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$cpuCores CORES",
                        color = NuxColors.GrayNeutral,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // =========================================================================
        // 2. MAIN CONTENT PANELS (Profile Tab vs Game Preferences Tab)
        // =========================================================================
        if (activeSettingsTab == "profile") {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT CARD: FOTO PROFIL & IDENTITAS
                NuxCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PROFILE PICTURE & IDENTITY",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        // Avatar Display with change button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Avatar Box
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(8.dp))
                            ) {
                                NuxNetworkImage(
                                    model = selectedPreviewUri ?: launcherUser?.photoURL?.takeIf { it.isNotBlank() },
                                    contentDescription = "Profile Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackInitials = launcherUser?.username ?: "NUX",
                                    shape = RoundedCornerShape(8.dp)
                                )

                                if (isUploadingAvatar) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }

                            // Upload Button & Hint
                            Column(modifier = Modifier.weight(1f)) {
                                NuxButton(
                                    onClick = { photoPickerLauncher.launch("image/*") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp),
                                    backgroundColor = NuxColors.ForestGreen,
                                    contentColor = Color.White,
                                    cornerRadius = 6.dp,
                                    enabled = !isUploadingAvatar
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoCamera,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isUploadingAvatar) tr("common.loading") else tr("settings.btn_change_avatar"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Choose image from device gallery. Automatically synced to cloud & voice rooms.",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        // Identity Specs Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Registered Email", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NuxColors.GrayNeutral)
                                Text(
                                    text = com.israadev.nuxlauncher.core.utils.PrivacyMasker.maskEmail(launcherUser?.email),
                                    fontSize = 10.5.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("License Status", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NuxColors.GrayNeutral)
                                val isVip = launcherUser?.isActivated == true
                                val tierText = if (isVip) {
                                    when (launcherUser?.tier?.lowercase()) {
                                        "monthly" -> "MONTHLY PREMIUM"
                                        "yearly" -> "YEARLY PREMIUM"
                                        else -> "LIFETIME PREMIUM"
                                    }
                                } else {
                                    "FREE MEMBER"
                                }
                                NuxBadge(
                                    text = tierText,
                                    backgroundColor = if (isVip) Color(0xFFF59E0B).copy(alpha = 0.2f) else NuxColors.SurfaceElevated,
                                    textColor = if (isVip) Color(0xFFFBBF24) else Color(0xFF94A3B8)
                                )
                            }

                            // Tombol Buka Showcase & Upgrade NUX Premium
                            val isUserVip = launcherUser?.isActivated == true
                            NuxButton(
                                onClick = {
                                    premiumInitialPrompt = if (isUserVip) "Your NUX Premium status is currently active!" else null
                                    showPremiumDialog = true
                                },
                                backgroundColor = if (isUserVip) Color(0xFF1E1710) else Color(0xFFF59E0B),
                                contentColor = if (isUserVip) Color(0xFFFBBF24) else Color.Black,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (isUserVip) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_vip_crown),
                                            contentDescription = null,
                                            tint = Color(0xFFFBBF24),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_vip_crown),
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isUserVip) "PREMIUM STATUS & MANAGE KEY" else "UPGRADE TO NUX PREMIUM",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 9.sp
                                    )
                                }
                            }

                            HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Account Platform", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NuxColors.GrayNeutral)
                                NuxBadge(
                                    text = "ANDROID MOBILE",
                                    backgroundColor = NuxColors.SurfaceElevated,
                                    textColor = Color.White
                                )
                            }

                            HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Account UID", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NuxColors.GrayNeutral)
                                Text(
                                    text = launcherUser?.uid?.take(16) ?: "-",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }

                            HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                            // Language Settings Card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NuxColors.SurfaceElevated, RoundedCornerShape(6.dp))
                                    .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Language,
                                                contentDescription = null,
                                                tint = NuxColors.ForestGreen,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = tr("settings.language_label"),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = tr("settings.language_desc"),
                                            fontSize = 8.5.sp,
                                            color = NuxColors.GrayNeutral
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Box {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                                .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                                .clickable { isCardLangMenuExpanded = true }
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Text(text = activeLanguage.flag, fontSize = 12.sp)
                                                Text(
                                                    text = activeLanguage.name,
                                                    color = NuxColors.MintGreen,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.5.sp
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowDown,
                                                    contentDescription = "Dropdown",
                                                    tint = Color(0xFFA1A1AA),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = isCardLangMenuExpanded,
                                            onDismissRequest = { isCardLangMenuExpanded = false },
                                            shape = RoundedCornerShape(12.dp),
                                            containerColor = Color(0xF212141C),
                                            shadowElevation = 16.dp,
                                            border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                        ) {
                                            availableLanguages.forEach { lang ->
                                                val isSelected = lang.code.equals(activeLanguage.code, ignoreCase = true)
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Text(text = lang.flag, fontSize = 15.sp)
                                                                Column {
                                                                    Text(
                                                                        text = lang.name,
                                                                        color = if (isSelected) NuxColors.MintGreen else Color.White,
                                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                                        fontSize = 11.sp
                                                                    )
                                                                    if (lang.author.isNotBlank()) {
                                                                        Text(
                                                                            text = "By ${lang.author}",
                                                                            color = Color(0xFFA1A1AA),
                                                                            fontSize = 8.5.sp
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = "Selected",
                                                                    tint = NuxColors.ForestGreen,
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        NuxLanguageManager.setLanguage(context, lang.code)
                                                        isCardLangMenuExpanded = false
                                                        Toast.makeText(context, "${lang.flag} ${lang.name}", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                            // Tombol Dialog Tentang & Lisensi Open Source (GPL-3.0 & Zalith Compliance)
                            NuxButton(
                                onClick = { showAboutDialog = true },
                                backgroundColor = NuxColors.SurfaceInput,
                                contentColor = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = NuxColors.ForestGreen,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "ABOUT & OPEN SOURCE LICENSES",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // RIGHT CARD: GANTI USERNAME & MANAJEMEN AKUN
                NuxCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Badge,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.card_change_username"),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        Text(
                            text = "Update your display name. Username is globally unique and used in Minecraft and voice rooms.",
                            fontSize = 9.5.sp,
                            color = NuxColors.GrayNeutral,
                            lineHeight = 13.sp
                        )

                        // Form Ganti Username
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "New Username",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            NuxTextField(
                                value = editUsername,
                                onValueChange = {
                                    editUsername = it
                                    usernameError = null
                                },
                                placeholder = "e.g. AlexGamer99"
                            )

                            if (usernameError != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = usernameError!!,
                                    color = NuxColors.Coral,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "3 - 20 alphanumeric characters & underscores (_).",
                                fontSize = 9.sp,
                                color = NuxColors.GrayNeutral
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            NuxButton(
                                onClick = { handleSaveUsername() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                backgroundColor = NuxColors.ForestGreen,
                                contentColor = Color.White,
                                cornerRadius = 6.dp,
                                enabled = !isSavingUsername
                            ) {
                                if (isSavingUsername) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SAVING...", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(tr("settings.btn_save_username"), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }

                        HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                        // Logout & Session Management
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "ACCOUNT SESSION MANAGEMENT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Sign out of your active account session on this device.",
                                fontSize = 9.5.sp,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            NuxButton(
                                onClick = { showLogoutDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                backgroundColor = NuxColors.Coral.copy(alpha = 0.15f),
                                contentColor = NuxColors.Coral,
                                borderColor = NuxColors.Coral.copy(alpha = 0.35f),
                                cornerRadius = 6.dp
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                                    contentDescription = null,
                                    tint = NuxColors.Coral,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("sidebar.logout"), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = NuxColors.Coral)
                            }
                        }
                    }
                }
            }
        } else if (activeSettingsTab == "game") {
            // =====================================================================
            // GAME PREFERENCES TAB (Memory/Java/Graphics + Controls/Mouse)
            // =====================================================================
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT COLUMN: Memory & Java + Graphics
                NuxCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section 1: Java Memory (RAM)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Memory,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.section_ram"),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        // RAM Slider & Value Display
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("settings.ram_max_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (ramMb > (totalRamMb * 0.75f)) NuxColors.Coral.copy(alpha = 0.2f) else NuxColors.ForestGreen.copy(alpha = 0.2f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (ramMb > (totalRamMb * 0.75f)) NuxColors.Coral.copy(alpha = 0.4f) else NuxColors.MintGreen.copy(alpha = 0.3f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$ramMb MB (${String.format("%.1f", ramMb / 1024f)} GB)",
                                        color = if (ramMb > (totalRamMb * 0.75f)) NuxColors.Coral else NuxColors.MintGreen,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Slider(
                                value = ramMb.toFloat(),
                                onValueChange = {
                                    ramMb = ((it / 128).roundToInt() * 128).coerceIn(512, maxAllocatableRam)
                                    commitSettings()
                                },
                                valueRange = 512f..maxAllocatableRam.toFloat(),
                                steps = ((maxAllocatableRam - 512) / 128) - 1,
                                colors = SliderDefaults.colors(
                                    thumbColor = NuxColors.MintGreen,
                                    activeTrackColor = NuxColors.ForestGreen,
                                    inactiveTrackColor = NuxColors.CardBorder
                                )
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Quick RAM Presets
                            Text(
                                text = tr("settings.ram_quick_presets"),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(1024, 1536, 2048, 3072, 4096).forEach { preset ->
                                    if (preset <= maxAllocatableRam) {
                                        val isSelected = ramMb == preset
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (isSelected) NuxColors.ForestGreen else NuxColors.SurfaceElevated,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSelected) NuxColors.MintGreen else NuxColors.CardBorder,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .clickable {
                                                    ramMb = preset
                                                    commitSettings()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (preset >= 1024) "${preset / 1024}G" else "${preset}M",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else NuxColors.GrayNeutral
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Custom JVM Arguments
                        Column {
                            Text(
                                text = tr("settings.jvm_args_label"),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            NuxTextField(
                                value = customJvmArgs,
                                onValueChange = {
                                    customJvmArgs = it
                                    commitSettings()
                                },
                                placeholder = tr("settings.jvm_args_placeholder")
                            )
                        }

                        HorizontalDivider(color = NuxColors.CardBorder, thickness = 0.5.dp)

                        // Section 2: Graphics & Rendering
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.section_graphics"),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        // Renderer Selection Card
                        val activeRendererInfo = remember(selectedRenderer) {
                            NuxRendererRegistry.findRendererById(selectedRenderer)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(NuxColors.SurfaceInput)
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            // Top Row: Section label & GANTI button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("settings.renderer_card_title"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (activeRendererInfo.isConfigurable) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(NuxColors.SurfaceElevated)
                                                .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    selectedConfigRenderer = activeRendererInfo
                                                    showRendererConfigDialog = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "Configure Renderer",
                                                tint = NuxColors.MintGreen,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(NuxColors.SurfaceElevated)
                                            .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(4.dp))
                                            .clickable {
                                                NuxRendererPluginManager.scanPlugins(context)
                                                showRendererDialog = true
                                            }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "CHANGE",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NuxColors.MintGreen
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Active Renderer Name & Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = activeRendererInfo.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = NuxColors.MintGreen
                                )
                                Box(
                                    modifier = Modifier
                                        .background(NuxColors.ForestGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                        .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = activeRendererInfo.badge,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NuxColors.MintGreen,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = activeRendererInfo.summary,
                                fontSize = 9.5.sp,
                                color = NuxColors.GrayNeutral,
                                lineHeight = 13.sp
                            )
                        }

                        // Auto Optimize options.txt Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Optimize options.txt",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Automatically optimize visual settings (Clouds, Shadows) for peak FPS",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }
                            Switch(
                                checked = autoOptimizeMC,
                                onCheckedChange = {
                                    autoOptimizeMC = it
                                    commitSettings()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NuxColors.ForestGreen,
                                    uncheckedTrackColor = NuxColors.SurfaceElevated
                                )
                            )
                        }

                        // Resolution Scaling
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = tr("settings.resolution_ratio_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "$resolutionRatio%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = NuxColors.MintGreen
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(50, 75, 100, 125).forEach { ratio ->
                                    val isSelected = resolutionRatio == ratio
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isSelected) NuxColors.ForestGreen else NuxColors.SurfaceElevated,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) NuxColors.MintGreen else NuxColors.CardBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                resolutionRatio = ratio
                                                commitSettings()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$ratio%",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else NuxColors.GrayNeutral
                                        )
                                    }
                                }
                            }
                        }

                        // Driver Vulkan Selector
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Vulkan Driver",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Select Vulkan driver implementation for system/game",
                                fontSize = 9.5.sp,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("auto" to "Auto", "system" to "Sistem", "turnip" to "Turnip").forEach { (id, label) ->
                                    val isSelected = vulkanDriver == id
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isSelected) NuxColors.ForestGreen else NuxColors.SurfaceElevated,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) NuxColors.MintGreen else NuxColors.CardBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                vulkanDriver = id
                                                commitSettings()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else NuxColors.GrayNeutral
                                        )
                                    }
                                }
                            }
                        }

                        // API Grafis Selector
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Graphics API",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Configure graphics API used by modern Minecraft 26.2+",
                                fontSize = 9.5.sp,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("DEFAULT" to "Default", "OPENGL" to "OpenGL", "VULKAN" to "Vulkan").forEach { (id, label) ->
                                    val isSelected = graphicsApi == id
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isSelected) NuxColors.ForestGreen else NuxColors.SurfaceElevated,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) NuxColors.MintGreen else NuxColors.CardBorder,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                graphicsApi = id
                                                commitSettings()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else NuxColors.GrayNeutral
                                        )
                                    }
                                }
                            }
                        }

                        // Gunakan Driver Vulkan Sistem (Zink)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("settings.vulkan_system_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Force device system Vulkan driver instead of Turnip. Affects Zink renderer.",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }
                            Switch(
                                checked = zinkPreferSystemDriver,
                                onCheckedChange = { checked ->
                                    if (checked && NuxVersionUtils.isAdrenoGPU()) {
                                        showAdrenoWarningDialog = true
                                    } else {
                                        zinkPreferSystemDriver = checked
                                        commitSettings()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NuxColors.ForestGreen,
                                    uncheckedTrackColor = NuxColors.SurfaceElevated
                                )
                            )
                        }

                        // Sinkronisasi Vertikal Zink (V-Sync)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("settings.vsync_zink_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Enable vertical synchronization (V-Sync) for Kopper Zink renderer.",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }
                            Switch(
                                checked = vsyncInZink,
                                onCheckedChange = {
                                    vsyncInZink = it
                                    commitSettings()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NuxColors.ForestGreen,
                                    uncheckedTrackColor = NuxColors.SurfaceElevated
                                )
                            )
                        }
                    }
                }

                // RIGHT COLUMN: In-Game Mouse & Touch Controls
                NuxCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header for Mouse settings
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Mouse,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.section_mouse"),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        Text(
                            text = tr("settings.mouse_desc"),
                            fontSize = 9.5.sp,
                            color = NuxColors.GrayNeutral
                        )

                        // CUSTOM CONTROLS / GUI EDITOR ACTION CARD
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = null,
                                        tint = NuxColors.ForestGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = tr("settings.virtual_controls_title"),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = tr("settings.virtual_controls_desc"),
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral,
                                    lineHeight = 12.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                NuxButton(
                                    onClick = onOpenGuiEditor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp),
                                    backgroundColor = NuxColors.ForestGreen,
                                    contentColor = Color.White,
                                    cornerRadius = 6.dp
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(tr("settings.btn_open_gui_editor"), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }

                        // Mouse Control Mode Selection (SLIDE vs CLICK)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Trackpad / Slide Card
                            val isSlide = mouseMode == "SLIDE"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSlide) NuxColors.ForestGreen.copy(alpha = 0.15f) else NuxColors.SurfaceInput,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSlide) NuxColors.MintGreen else NuxColors.CardBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        mouseMode = "SLIDE"
                                        commitSettings()
                                    }
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.TouchApp,
                                            contentDescription = null,
                                            tint = if (isSlide) NuxColors.MintGreen else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = tr("settings.mouse_mode_slide_title"),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isSlide) NuxColors.MintGreen else Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = tr("settings.mouse_mode_slide_desc"),
                                        fontSize = 9.sp,
                                        color = NuxColors.GrayNeutral,
                                        lineHeight = 11.sp
                                    )
                                }
                            }

                            // Direct Touch / Click Card
                            val isClick = mouseMode == "CLICK"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isClick) NuxColors.ForestGreen.copy(alpha = 0.15f) else NuxColors.SurfaceInput,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isClick) NuxColors.MintGreen else NuxColors.CardBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        mouseMode = "CLICK"
                                        commitSettings()
                                    }
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.NearMe,
                                            contentDescription = null,
                                            tint = if (isClick) NuxColors.MintGreen else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = tr("settings.mouse_mode_click_title"),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isClick) NuxColors.MintGreen else Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = tr("settings.mouse_mode_click_desc"),
                                        fontSize = 9.sp,
                                        color = NuxColors.GrayNeutral,
                                        lineHeight = 11.sp
                                    )
                                }
                            }
                        }

                        // In-Game Camera Look Sensitivity
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("settings.camera_sensitivity_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .background(NuxColors.ForestGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                        .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$captureSensitivity%",
                                        color = NuxColors.MintGreen,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Slider(
                                value = captureSensitivity.toFloat(),
                                onValueChange = {
                                    captureSensitivity = it.roundToInt()
                                    commitSettings()
                                },
                                valueRange = 50f..300f,
                                steps = 25,
                                colors = SliderDefaults.colors(
                                    thumbColor = NuxColors.MintGreen,
                                    activeTrackColor = NuxColors.ForestGreen,
                                    inactiveTrackColor = NuxColors.CardBorder
                                )
                            )
                        }

                        // Virtual Cursor Sensitivity
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("settings.mouse_sensitivity_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .background(NuxColors.ForestGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                        .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$cursorSensitivity%",
                                        color = NuxColors.MintGreen,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Slider(
                                value = cursorSensitivity.toFloat(),
                                onValueChange = {
                                    cursorSensitivity = it.roundToInt()
                                    commitSettings()
                                },
                                valueRange = 50f..300f,
                                steps = 25,
                                colors = SliderDefaults.colors(
                                    thumbColor = NuxColors.MintGreen,
                                    activeTrackColor = NuxColors.ForestGreen,
                                    inactiveTrackColor = NuxColors.CardBorder
                                )
                            )
                        }

                        // Virtual Cursor Size with Preview
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr("settings.mouse_size_label"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${mouseSizeDp}dp",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = NuxColors.MintGreen
                                )
                            }
                            Slider(
                                value = mouseSizeDp.toFloat(),
                                onValueChange = {
                                    mouseSizeDp = it.roundToInt()
                                    commitSettings()
                                },
                                valueRange = 16f..48f,
                                steps = 16,
                                colors = SliderDefaults.colors(
                                    thumbColor = NuxColors.MintGreen,
                                    activeTrackColor = NuxColors.ForestGreen,
                                    inactiveTrackColor = NuxColors.CardBorder
                                )
                            )
                        }

                        // Physical Mouse Mode Card (Zalith Style: Auto-Hide Virtual Cursor)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Physical Mouse Mode (Auto-Hide)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Automatically hide virtual controls when external mouse is connected",
                                    fontSize = 9.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }
                            Switch(
                                checked = physicalMouseMode,
                                onCheckedChange = {
                                    physicalMouseMode = it
                                    commitSettings()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuxColors.MintGreen,
                                    checkedTrackColor = NuxColors.ForestGreen,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = NuxColors.CardBorder
                                )
                            )
                        }

                        // Device Info & Reset Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NuxButton(
                                onClick = {
                                    SettingsManager.resetToDefaults(context)
                                    val reset = SettingsManager.settings.value
                                    ramMb = reset.ramMb
                                    initialHeapMb = reset.initialHeapMb
                                    customJvmArgs = reset.customJvmArgs
                                    defaultRuntime = reset.defaultJavaRuntime
                                    mouseMode = reset.mouseControlMode
                                    cursorSensitivity = reset.cursorSensitivity
                                    captureSensitivity = reset.captureSensitivity
                                    mouseSizeDp = reset.mouseSizeDp
                                    physicalMouseMode = reset.physicalMouseMode
                                    resolutionRatio = reset.resolutionRatio
                                    autoOptimizeMC = reset.autoOptimizeMinecraft
                                    sustainedPerf = reset.sustainedPerformanceMode
                                    aiAutoAnalyze = reset.aiAutoAnalyze
                                    aiApiKey = reset.aiApiKey
                                    aiModel = reset.aiModel
                                    Toast.makeText(context, "Settings reset to defaults!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                backgroundColor = NuxColors.Coral.copy(alpha = 0.15f),
                                contentColor = NuxColors.Coral,
                                borderColor = NuxColors.Coral.copy(alpha = 0.35f),
                                cornerRadius = 6.dp
                            ) {
                                Text(
                                    text = "RESET DEFAULTS",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    color = NuxColors.Coral
                                )
                            }

                            NuxButton(
                                onClick = {
                                    commitSettings()
                                    Toast.makeText(context, "All settings saved!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                backgroundColor = NuxColors.ForestGreen,
                                contentColor = Color.White,
                                cornerRadius = 6.dp
                            ) {
                                Text(
                                    text = "SAVE",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // =====================================================================
            // AI ANALITIK CRASH TAB (Konfigurasi Gemini AI / OpenRouter + Diagnostic)
            // =====================================================================
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT CARD: Pengaturan AI Engine, Model & API Key
                NuxCard(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section 1: Header & Switch Otomatis
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.section_ai_crash"),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        // Toggle Otomatis Analisis
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("settings.ai_auto_analyze_label"),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (aiAutoAnalyze) "Active: AI analyzes crash logs automatically when game terminates unexpectedly."
                                           else "Disabled (Default): AI analysis is triggered manually in crash dialogs to conserve quota.",
                                    fontSize = 9.sp,
                                    color = if (aiAutoAnalyze) NuxColors.MintGreen else NuxColors.GrayNeutral,
                                    lineHeight = 12.sp
                                )
                            }
                            Switch(
                                checked = aiAutoAnalyze,
                                onCheckedChange = {
                                    aiAutoAnalyze = it
                                    commitSettings()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NuxColors.ForestGreen,
                                    uncheckedThumbColor = NuxColors.GrayNeutral,
                                    uncheckedTrackColor = NuxColors.CardBorder
                                )
                            )
                        }

                        // Section 2: API Key Configuration
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("settings.ai_api_key_label"),
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                color = Color.White
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "API Access Key (OpenRouter API Key):",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NuxColors.GrayNeutral
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            NuxTextField(
                                value = aiApiKey,
                                onValueChange = {
                                    aiApiKey = it
                                    commitSettings()
                                },
                                placeholder = "Leave blank = Default launcher free quota",
                                visualTransformation = if (showApiKeyPlaintext) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingContent = {
                                    Icon(
                                        imageVector = if (showApiKeyPlaintext) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null,
                                        tint = NuxColors.GrayNeutral,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { showApiKeyPlaintext = !showApiKeyPlaintext }
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Supports OpenRouter 'sk-or-v1-...' format. If empty, launcher uses built-in free quota.",
                                fontSize = 8.5.sp,
                                color = NuxColors.GrayNeutral
                            )
                        }

                        // Test Connection & Save Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NuxButton(
                                onClick = {
                                    isTestingAiConnection = true
                                    aiConnectionTestResult = null
                                    scope.launch {
                                        val res = AICrashAnalyzer.testConnection(aiApiKey, "")
                                        isTestingAiConnection = false
                                        res.onSuccess { msg ->
                                            isAiConnectionSuccess = true
                                            aiConnectionTestResult = msg
                                        }.onFailure { err ->
                                            isAiConnectionSuccess = false
                                            aiConnectionTestResult = err.message ?: "Connection failed"
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                backgroundColor = NuxColors.SurfaceInput,
                                borderColor = NuxColors.CardBorder,
                                contentColor = Color.White,
                                cornerRadius = 6.dp,
                                enabled = !isTestingAiConnection
                            ) {
                                if (isTestingAiConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(tr("settings.btn_testing_ai"), fontWeight = FontWeight.Bold, fontSize = 9.5.sp, color = Color.White)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.NetworkCheck,
                                        contentDescription = null,
                                        tint = NuxColors.MintGreen,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(tr("settings.btn_test_ai"), fontWeight = FontWeight.Bold, fontSize = 9.5.sp, color = Color.White)
                                }
                            }

                            NuxButton(
                                onClick = {
                                    commitSettings()
                                    Toast.makeText(context, "AI Analytics settings saved!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                backgroundColor = NuxColors.ForestGreen,
                                contentColor = Color.White,
                                cornerRadius = 6.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Save,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("settings.btn_save_settings"), fontWeight = FontWeight.Bold, fontSize = 9.5.sp, color = Color.White)
                            }
                        }

                        // Test Result Badge
                        if (aiConnectionTestResult != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isAiConnectionSuccess) NuxColors.MintGreen.copy(alpha = 0.15f)
                                        else NuxColors.Coral.copy(alpha = 0.15f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isAiConnectionSuccess) NuxColors.MintGreen.copy(alpha = 0.4f)
                                        else NuxColors.Coral.copy(alpha = 0.4f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isAiConnectionSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (isAiConnectionSuccess) NuxColors.MintGreen else NuxColors.Coral,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = aiConnectionTestResult!!,
                                    fontSize = 9.sp,
                                    color = if (isAiConnectionSuccess) Color.White else NuxColors.Coral,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }

                // RIGHT CARD: Status Sistem, Panduan & Keunggulan AI
                NuxCard(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = NuxColors.CardBorder,
                    borderWidth = 1.dp,
                    cornerRadius = 8.dp,
                    fillMaxHeight = true
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header Right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = NuxColors.ForestGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SYSTEM STATUS & CAPABILITIES",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .background(NuxColors.MintGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ONLINE & READY",
                                    color = NuxColors.MintGreen,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Penjelasan Kapabilitas AI
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.SurfaceInput, RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("settings.ai_capabilities_title"),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            val features = listOf(
                                "📦 Incompatible Mods & Conflicts" to "Accurately detects which mod caused the game to crash and identifies missing dependencies.",
                                "🧠 RAM Allocation & OutOfMemoryError" to "Analyzes whether system RAM is insufficient or Java heap size is too small for the modpack.",
                                "🎮 GPU Driver, Vulkan & Zink Shader" to "Diagnoses GL/GLES crashes, Turnip driver issues, or Minecraft shader compilation errors.",
                                "☕ Java Runtime & ClassNotFound" to "Verifies compatibility between Java version (Java 8, 17, 21) and the installed Fabric/Forge version.",
                                "⚡ Instant Fix Recommendations" to "Provides actionable 1-2-3 resolution steps that players can apply immediately without reading raw logs."
                            )

                            features.forEach { (title, desc) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(5.dp)
                                            .background(NuxColors.MintGreen, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = title,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = desc,
                                            fontSize = 8.5.sp,
                                            color = NuxColors.GrayNeutral,
                                            lineHeight = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Tips Dapatkan API Key Gratis
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NuxColors.ForestGreen.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .border(1.dp, NuxColors.ForestGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Lightbulb,
                                    contentDescription = null,
                                    tint = NuxColors.MintGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Want to Use a Personal API Key?",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NuxColors.MintGreen
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Open openrouter.ai in your browser and sign up / log in\n" +
                                       "2. Create an API Key in the 'Keys' menu (starts with 'sk-or-v1-...')\n" +
                                       "3. Copy the key and paste it into the API Key field on the left\n" +
                                       "4. Enjoy ultra-fast dedicated quota with the best AI models!",
                                fontSize = 8.5.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Logout Confirmation Dialog
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = {
                    Text(
                        text = "Sign Out of Account?",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        text = "You will be signed out of ${launcherUser?.username ?: ""}. You can log back in at any time.",
                        color = NuxColors.GrayNeutral,
                        fontSize = 11.sp
                    )
                },
                confirmButton = {
                    NuxButton(
                        onClick = {
                            showLogoutDialog = false
                            AccountManager.logout(context)
                            onNavigateBack()
                            Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.height(32.dp),
                        backgroundColor = NuxColors.Coral,
                        contentColor = Color.White,
                        cornerRadius = 6.dp
                    ) {
                        Text("YES, SIGN OUT", fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                    }
                },
                dismissButton = {
                    NuxButton(
                        onClick = { showLogoutDialog = false },
                        modifier = Modifier.height(32.dp),
                        backgroundColor = NuxColors.SurfaceInput,
                        contentColor = NuxColors.GrayNeutral,
                        cornerRadius = 6.dp
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                    }
                },
                containerColor = NuxColors.SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
        }

        // Renderer Selection Dialog (Landscape Optimized with NuxDialog)
        if (showRendererDialog) {
            LaunchedEffect(Unit) {
                NuxRendererPluginManager.scanPlugins(context)
            }
            NuxDialog(
                onDismissRequest = { showRendererDialog = false },
                modifier = Modifier.fillMaxWidth(0.72f),
                fillMaxHeight = true
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Layers,
                                contentDescription = null,
                                tint = NuxColors.ForestGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "SELECT GRAPHICS RENDERER",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Match graphics backend with Minecraft version & device GPU",
                                    fontSize = 9.5.sp,
                                    color = NuxColors.GrayNeutral
                                )
                            }
                        }
                        IconButton(
                            onClick = { showRendererDialog = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable Renderer List
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(NuxRendererRegistry.availableRenderers) { rendererItem ->
                            val isSelected = selectedRenderer.equals(rendererItem.id, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) NuxColors.ForestGreen.copy(alpha = 0.15f) else NuxColors.SurfaceInput
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) NuxColors.MintGreen else NuxColors.CardBorder,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        selectedRenderer = rendererItem.id
                                        commitSettings()
                                        showRendererDialog = false
                                        Toast.makeText(context, "Renderer changed to: ${rendererItem.displayName}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = {
                                                    selectedRenderer = rendererItem.id
                                                    commitSettings()
                                                    showRendererDialog = false
                                                    Toast.makeText(context, "Renderer changed to: ${rendererItem.displayName}", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = NuxColors.ForestGreen,
                                                    unselectedColor = NuxColors.CardBorder
                                                ),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = rendererItem.displayName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (isSelected) NuxColors.MintGreen else Color.White
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (rendererItem.isConfigurable) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(NuxColors.SurfaceElevated)
                                                        .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            selectedConfigRenderer = rendererItem
                                                            showRendererDialog = false
                                                            showRendererConfigDialog = true
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Settings,
                                                        contentDescription = "Configure ${rendererItem.displayName}",
                                                        tint = NuxColors.MintGreen,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (isSelected) NuxColors.ForestGreen else NuxColors.SurfaceElevated,
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .border(1.dp, NuxColors.CardBorder, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = rendererItem.badge,
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else NuxColors.GrayNeutral
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = rendererItem.summary,
                                        fontSize = 9.5.sp,
                                        color = NuxColors.GrayNeutral,
                                        lineHeight = 12.sp,
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Compatibility: ${rendererItem.compatibility}",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NuxColors.MintGreen,
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        NuxButton(
                            onClick = { showRendererDialog = false },
                            modifier = Modifier.height(30.dp),
                            backgroundColor = NuxColors.SurfaceInput,
                            contentColor = Color.White,
                            cornerRadius = 6.dp
                        ) {
                            Text("CLOSE", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    // Adreno GPU Warning Dialog (Persis seperti Zalith)
    if (showAdrenoWarningDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAdrenoWarningDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                NuxCard(
                    modifier = Modifier
                        .width(420.dp)
                        .wrapContentHeight(),
                    backgroundColor = NuxColors.SurfaceElevated,
                    borderColor = Color(0x33FFFFFF),
                    cornerRadius = NuxSizes.CornerRadiusLarge,
                    fillMaxHeight = false
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_warning_triangle),
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ADRENO DRIVER WARNING",
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            text = "Launcher detected an Adreno GPU. Enabling this option may cause Zink renderer to malfunction or crash. Are you sure you want to proceed?",
                            color = Color(0xFFD4D4D8),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                NuxButton(
                                    onClick = {
                                        zinkPreferSystemDriver = false
                                        commitSettings()
                                        showAdrenoWarningDialog = false
                                    },
                                    backgroundColor = NuxColors.SurfaceInput,
                                    contentColor = Color.White,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "CANCEL",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                NuxButton(
                                    onClick = {
                                        zinkPreferSystemDriver = true
                                        commitSettings()
                                        showAdrenoWarningDialog = false
                                    },
                                    backgroundColor = Color(0xFFF59E0B),
                                    contentColor = Color.Black,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "AKTIFKAN",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog Konfigurasi Renderer V2 (persis Zalith untuk MobileGlues, NGG dll)
    if (showRendererConfigDialog && selectedConfigRenderer != null) {
        NuxRendererV2ConfigDialog(
            rendererInfo = selectedConfigRenderer,
            onDismiss = { showRendererConfigDialog = false }
        )
    }

    // Dialog Tentang & Lisensi Open Source (GPL-3.0 & Zalith Compliance)
    if (showAboutDialog) {
        NuxAboutDialog(
            onDismissRequest = { showAboutDialog = false }
        )
    }

    // Dialog NUX Premium & Showcase (Fitur 1 - 7 Eksklusif)
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
