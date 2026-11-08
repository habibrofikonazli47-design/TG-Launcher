package com.israadev.nuxlauncher.ui.dialogs

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.israadev.nuxlauncher.FpsMode
import com.israadev.nuxlauncher.ui.control.MouseControlMode
import com.israadev.nuxlauncher.ui.theme.NuxColors
import com.movtery.inputmap.keycodes.LwjglGlfwKeycode
import com.israadev.nuxlauncher.core.recorder.ScreenRecordConfig
import com.israadev.nuxlauncher.core.recorder.ScreenResolution
import com.israadev.nuxlauncher.core.recorder.ScreenRecordManager
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.lang.tr
import kotlin.math.roundToInt

@Composable
fun InGameSettingsDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    currentFps: Int,
    instanceName: String,
    mcVersion: String,
    currentResolutionRatio: Int,
    onResolutionChange: (Int) -> Unit,
    fpsMode: FpsMode,
    onFpsModeChange: (FpsMode) -> Unit,
    onOpenConsoleLog: () -> Unit,
    cursorSensitivity: Int,
    onCursorSensitivityChange: (Int) -> Unit,
    captureSensitivity: Int,
    onCaptureSensitivityChange: (Int) -> Unit,
    mouseControlMode: MouseControlMode,
    onMouseControlModeChange: (MouseControlMode) -> Unit,
    isControlVisible: Boolean,
    onToggleControlVisibility: () -> Unit,
    onRequestKeyboard: () -> Unit,
    onSendKeycode: (Int) -> Unit,
    onForceExitRequest: () -> Unit,
    onOpenCustomGui: () -> Unit = {},
    isRecording: Boolean = false,
    recordingDurationSeconds: Long = 0,
    onRequestStartRecord: ((ScreenRecordConfig) -> Unit)? = null,
    onStopRecord: (() -> Unit)? = null,
    onOpenPremium: (() -> Unit)? = null,
    initialTab: Int = 0
) {
    if (!visible) return

    var selectedTab by remember(visible) { mutableIntStateOf(initialTab) }
    var tempResolution by remember(currentResolutionRatio) { mutableFloatStateOf(currentResolutionRatio.toFloat()) }
    var tempCursorSens by remember(cursorSensitivity) { mutableFloatStateOf(cursorSensitivity.toFloat()) }
    var tempCaptureSens by remember(captureSensitivity) { mutableFloatStateOf(captureSensitivity.toFloat()) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        SideEffect {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            dialogWindow?.let { win ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    win.attributes = win.attributes.apply {
                        blurBehindRadius = 45
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    win.attributes = win.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val cardShape = RoundedCornerShape(20.dp)
            Card(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .clip(cardShape)
                    .border(1.2.dp, Color(0x3810B981), cardShape),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xF20F1522)
                ),
                shape = cardShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 1. Header Bar: Logo + Title + FPS Indicator + Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NuxColors.ForestGreen.copy(alpha = 0.25f))
                                    .border(1.dp, NuxColors.MintGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "N",
                                    color = NuxColors.MintGreen,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = tr("ingame.settings_title"),
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "$instanceName · v$mcVersion",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Realtime FPS Badge
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0x66000000),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3310B981))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                when {
                                                    currentFps >= 50 -> Color(0xFF10B981)
                                                    currentFps >= 25 -> Color(0xFFF59E0B)
                                                    currentFps > 0 -> Color(0xFFEF4444)
                                                    else -> Color(0xFF6B7280)
                                                },
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (currentFps > 0) "$currentFps FPS" else "FPS: --",
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Close Button
                            IconButton(
                                onClick = onDismissRequest,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x33FFFFFF))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = tr("common.close"),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Tab Navigation
                    val tabs = listOf(
                        tr("settings.tab_game"),
                        tr("controls.title"),
                        tr("ingame.tab_shortcuts"),
                        tr("recorder.title")
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x40000000))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = selectedTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) NuxColors.ForestGreen else Color.Transparent)
                                    .clickable { selectedTab = index }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Tab Contents (Scrollable)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (selectedTab) {
                            0 -> {
                                // === TAB 0: GRAFIS & LAYAR ===
                                // A. Slider Skala Resolusi
                                InGameSectionCard(title = tr("ingame.resolution_scale")) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${tr("ingame.resolution_scale")}:",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "${tempResolution.roundToInt()}%",
                                                color = NuxColors.MintGreen,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 13.sp
                                            )
                                        }

                                        Slider(
                                            value = tempResolution,
                                            onValueChange = { tempResolution = it },
                                            onValueChangeFinished = {
                                                onResolutionChange(tempResolution.roundToInt())
                                            },
                                            valueRange = 40f..125f,
                                            steps = 16,
                                            colors = SliderDefaults.colors(
                                                thumbColor = NuxColors.MintGreen,
                                                activeTrackColor = NuxColors.ForestGreen,
                                                inactiveTrackColor = Color(0x33FFFFFF)
                                            )
                                        )

                                        // Presets
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                         ) {
                                             listOf(50, 75, 100).forEach { preset ->
                                                 val isCur = tempResolution.roundToInt() == preset
                                                 Box(
                                                     modifier = Modifier
                                                         .weight(1f)
                                                         .clip(RoundedCornerShape(6.dp))
                                                         .background(if (isCur) NuxColors.ForestGreen.copy(alpha = 0.5f) else Color(0x22FFFFFF))
                                                         .border(1.dp, if (isCur) NuxColors.MintGreen else Color.Transparent, RoundedCornerShape(6.dp))
                                                         .clickable {
                                                             tempResolution = preset.toFloat()
                                                             onResolutionChange(preset)
                                                         }
                                                         .padding(vertical = 5.dp),
                                                     contentAlignment = Alignment.Center
                                                 ) {
                                                     Text(
                                                         text = "$preset%",
                                                         color = Color.White,
                                                         fontSize = 10.sp,
                                                         fontWeight = FontWeight.SemiBold
                                                     )
                                                 }
                                             }
                                         }
                                     }
                                 }

                                 Spacer(modifier = Modifier.height(10.dp))

                                 // B. Pin FPS Toggle
                                 InGameSectionCard(title = "FPS") {
                                     Row(
                                         modifier = Modifier.fillMaxWidth(),
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween
                                     ) {
                                         Column(modifier = Modifier.weight(1f)) {
                                             Text(
                                                 text = tr("ingame.fps_pinned"),
                                                 color = Color.White,
                                                 fontSize = 12.sp,
                                                 fontWeight = FontWeight.Bold
                                             )
                                         }
                                         Switch(
                                             checked = fpsMode == FpsMode.PINNED,
                                             onCheckedChange = { checked ->
                                                 onFpsModeChange(if (checked) FpsMode.PINNED else FpsMode.NORMAL)
                                             },
                                             colors = SwitchDefaults.colors(
                                                 checkedThumbColor = Color.White,
                                                 checkedTrackColor = NuxColors.ForestGreen
                                             )
                                         )
                                     }
                                 }

                                 Spacer(modifier = Modifier.height(10.dp))

                                 // C. Live Console Log Button
                                 InGameSectionCard(title = tr("common.diagnostics", "Diagnostics")) {
                                     Row(
                                         modifier = Modifier
                                             .fillMaxWidth()
                                             .clip(RoundedCornerShape(8.dp))
                                             .background(Color(0x3310B981))
                                             .border(1.dp, Color(0x4D10B981), RoundedCornerShape(8.dp))
                                             .clickable {
                                                 onDismissRequest()
                                                 onOpenConsoleLog()
                                             }
                                             .padding(horizontal = 12.dp, vertical = 9.dp),
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween
                                     ) {
                                         Row(verticalAlignment = Alignment.CenterVertically) {
                                             Icon(
                                                 imageVector = Icons.Outlined.Terminal,
                                                 contentDescription = null,
                                                 tint = NuxColors.MintGreen,
                                                 modifier = Modifier.size(16.dp)
                                             )
                                             Spacer(modifier = Modifier.width(8.dp))
                                             Text(
                                                 text = tr("ingame.open_console"),
                                                 color = Color.White,
                                                 fontSize = 11.sp,
                                                 fontWeight = FontWeight.Bold
                                             )
                                         }
                                         Icon(
                                             imageVector = Icons.Outlined.ChevronRight,
                                             contentDescription = null,
                                             tint = NuxColors.MintGreen,
                                             modifier = Modifier.size(16.dp)
                                         )
                                     }
                                 }
                            }

                            1 -> {
                                // === TAB 1: KONTROL & MOUSE ===
                                // A. Sensitivitas Kursor
                                InGameSectionCard(title = tr("ingame.cursor_sens")) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(tr("ingame.cursor_sens") + ":", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                            Text("${tempCursorSens.roundToInt()}%", color = NuxColors.MintGreen, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                        }
                                        Slider(
                                            value = tempCursorSens,
                                            onValueChange = { tempCursorSens = it },
                                            onValueChangeFinished = {
                                                onCursorSensitivityChange(tempCursorSens.roundToInt())
                                            },
                                            valueRange = 25f..250f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = NuxColors.MintGreen,
                                                activeTrackColor = NuxColors.ForestGreen,
                                                inactiveTrackColor = Color(0x33FFFFFF)
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // B. Sensitivitas Kamera (Capture)
                                InGameSectionCard(title = tr("ingame.camera_sens")) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(tr("ingame.camera_sens") + ":", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                            Text("${tempCaptureSens.roundToInt()}%", color = NuxColors.MintGreen, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                        }
                                        Slider(
                                            value = tempCaptureSens,
                                            onValueChange = { tempCaptureSens = it },
                                            onValueChangeFinished = {
                                                onCaptureSensitivityChange(tempCaptureSens.roundToInt())
                                            },
                                            valueRange = 25f..250f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = NuxColors.MintGreen,
                                                activeTrackColor = NuxColors.ForestGreen,
                                                inactiveTrackColor = Color(0x33FFFFFF)
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // C. Mode Navigasi Mouse & Visibilitas GUI
                                InGameSectionCard(title = "Touch Control Method") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Mouse Mode:",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf(
                                                    MouseControlMode.SLIDE to "Geser (Slide)",
                                                    MouseControlMode.CLICK to "Sentuh (Click)"
                                                ).forEach { (mode, label) ->
                                                    val isSelected = mouseControlMode == mode
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(if (isSelected) NuxColors.ForestGreen else Color(0x22FFFFFF))
                                                            .clickable { onMouseControlModeChange(mode) }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = Color(0x1AFFFFFF))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = tr("ingame.show_gui"),
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "Virtual buttons overlay",
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 9.5.sp
                                                )
                                            }
                                            Switch(
                                                checked = isControlVisible,
                                                onCheckedChange = { onToggleControlVisibility() },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = NuxColors.ForestGreen
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // === TAB 2: PINTAS & SISTEM ===
                                // A. Edit/Custom GUI Button
                                InGameSectionCard(title = tr("ingame.custom_gui")) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x3310B981))
                                            .border(1.dp, Color(0x6610B981), RoundedCornerShape(8.dp))
                                            .clickable {
                                                onDismissRequest()
                                                onOpenCustomGui()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                tint = NuxColors.MintGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = tr("ingame.custom_gui"),
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                            tint = NuxColors.MintGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                InGameSectionCard(title = "Quick Keycodes") {
                                    Column {
                                        Text(
                                            text = "Send direct keyboard input to Minecraft:",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        val quickKeys = listOf(
                                            Triple("F3", "Debug", LwjglGlfwKeycode.GLFW_KEY_F3),
                                            Triple("F5", "Kamera", LwjglGlfwKeycode.GLFW_KEY_F5),
                                            Triple("ESC", "Pause", LwjglGlfwKeycode.GLFW_KEY_ESCAPE),
                                            Triple("T", "Chat", LwjglGlfwKeycode.GLFW_KEY_T),
                                            Triple("TAB", "Players", LwjglGlfwKeycode.GLFW_KEY_TAB),
                                            Triple("F1", "Hide HUD", LwjglGlfwKeycode.GLFW_KEY_F1)
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            quickKeys.take(3).forEach { (label, desc, code) ->
                                                QuickKeyButton(
                                                    modifier = Modifier.weight(1f),
                                                    label = label,
                                                    desc = desc,
                                                    onClick = {
                                                        onSendKeycode(code)
                                                    }
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            quickKeys.drop(3).forEach { (label, desc, code) ->
                                                QuickKeyButton(
                                                    modifier = Modifier.weight(1f),
                                                    label = label,
                                                    desc = desc,
                                                    onClick = {
                                                        onSendKeycode(code)
                                                    }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Keyboard Toggle
                                        Button(
                                            onClick = {
                                                onDismissRequest()
                                                onRequestKeyboard()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0x3338BDF8)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Keyboard,
                                                contentDescription = null,
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = tr("ingame.virtual_keyboard"),
                                                color = Color(0xFFBAE6FD),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // B. Force Exit Section
                                InGameSectionCard(title = tr("ingame.force_exit")) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x26EF4444))
                                            .border(1.dp, Color(0x4DEF4444), RoundedCornerShape(8.dp))
                                            .clickable {
                                                onDismissRequest()
                                                onForceExitRequest()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.PowerSettingsNew,
                                                contentDescription = null,
                                                tint = Color(0xFFFCA5A5),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = tr("ingame.force_exit"),
                                                    color = Color(0xFFFCA5A5),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = tr("ingame.force_exit_desc"),
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                            tint = Color(0xFFFCA5A5),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            3 -> {
                                // === TAB 3: SCREEN RECORDER [PREMIUM] ===
                                InGameRecorderSection(
                                    isRecording = isRecording,
                                    recordingDurationSeconds = recordingDurationSeconds,
                                    onRequestStartRecord = { cfg ->
                                        onDismissRequest()
                                        onRequestStartRecord?.invoke(cfg)
                                    },
                                    onStopRecord = {
                                        onStopRecord?.invoke()
                                    },
                                    onOpenPremium = {
                                        onDismissRequest()
                                        onOpenPremium?.invoke()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InGameSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0x59000000),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                color = NuxColors.MintGreen,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun QuickKeyButton(
    modifier: Modifier = Modifier,
    label: String,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = Color(0x33FFFFFF),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp
            )
            Text(
                text = desc,
                color = Color(0xFF94A3B8),
                fontSize = 8.5.sp
            )
        }
    }
}


@Composable
private fun InGameRecorderSection(
    isRecording: Boolean,
    recordingDurationSeconds: Long,
    onRequestStartRecord: (ScreenRecordConfig) -> Unit,
    onStopRecord: () -> Unit,
    onOpenPremium: () -> Unit
) {
    val isUserPremium = ScreenRecordManager.isUserPremium()
    var selectedRes by remember { mutableStateOf(ScreenResolution.RES_720P) }
    var selectedFps by remember { mutableIntStateOf(60) }
    var recordAudio by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isUserPremium) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x33F59E0B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.israadev.nuxlauncher.R.drawable.ic_vip_crown),
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "NUX VIP SCREEN RECORDER ACTIVE",
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Active Screen Recorder Controls
        InGameSectionCard(title = "Recorder Status & Actions") {
                if (isRecording) {
                    val m = recordingDurationSeconds / 60
                    val s = recordingDurationSeconds % 60
                    val timeStr = "%02d:%02d".format(m, s)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x33EF4444), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFFEF4444), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RECORDING IN PROGRESS",
                                color = Color(0xFFFCA5A5),
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = timeStr,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onStopRecord,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(com.israadev.nuxlauncher.R.drawable.ic_stop_square),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tr("recorder.stop"),
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val config = ScreenRecordConfig(
                                resolution = selectedRes,
                                frameRate = selectedFps,
                                bitRate = selectedRes.defaultBitrate,
                                recordAudio = recordAudio
                            )
                            onRequestStartRecord(config)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NuxColors.ForestGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(com.israadev.nuxlauncher.R.drawable.ic_record_dot),
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("recorder.start"),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (!isRecording) {
                InGameSectionCard(title = tr("recorder.quality_title")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Resolution selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("recorder.resolution") + ":", color = Color(0xFF94A3B8), fontSize = 10.5.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(ScreenResolution.RES_720P, ScreenResolution.RES_1080P, ScreenResolution.RES_NATIVE).forEach { res ->
                                    val isSel = selectedRes == res
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSel) NuxColors.ForestGreen else Color(0x33FFFFFF),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) NuxColors.MintGreen else Color.Transparent),
                                        modifier = Modifier.clickable { selectedRes = res }
                                    ) {
                                        Text(
                                            text = res.label,
                                            color = if (isSel) Color.White else Color(0xFF94A3B8),
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 9.5.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // FPS selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("recorder.framerate") + ":", color = Color(0xFF94A3B8), fontSize = 10.5.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(30, 60).forEach { fpsVal ->
                                    val isSel = selectedFps == fpsVal
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSel) NuxColors.ForestGreen else Color(0x33FFFFFF),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) NuxColors.MintGreen else Color.Transparent),
                                        modifier = Modifier.clickable { selectedFps = fpsVal }
                                    ) {
                                        Text(
                                            text = "$fpsVal FPS",
                                            color = if (isSel) Color.White else Color(0xFF94A3B8),
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 9.5.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Audio Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(tr("recorder.mic_audio"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(tr("recorder.mic_audio_desc"), color = Color(0xFF94A3B8), fontSize = 9.sp)
                            }
                            Switch(
                                checked = recordAudio,
                                onCheckedChange = { recordAudio = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NuxColors.ForestGreen
                                )
                            )
                        }
                    }
                }
            }
    }
}
