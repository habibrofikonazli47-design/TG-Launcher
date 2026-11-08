package com.israadev.nuxlauncher.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israadev.nuxlauncher.ui.theme.NuxColors
import kotlinx.coroutines.delay

private val GAME_TIPS = listOf(
    "Tap the FPS counter to open launcher settings in-game.",
    "Bugs are natural in software development; continuous improvement makes perfection.",
    "Disable Vertical Sync (VSync) in Renderer settings if you experience stuttering or a black screen after the Mojang logo.",
    "If Minecraft appears cropped or vertical, enable auto-rotate and hold your phone horizontally (Landscape) before tapping Play.",
    "Use MobileGlues Renderer for optimal battery efficiency and shaderpack compatibility.",
    "If Minecraft crashes on startup on Mali/Adreno GPUs, try enabling 'DISABLE_SUBGROUP' in renderer settings.",
    "Enable OpenGL NoError in MobileGlues settings to boost in-game FPS.",
    "Use the HIDE GUI button to conceal on-screen virtual buttons when using a physical keyboard & mouse.",
    "Set RAM allocation according to your device specs: 2GB–3GB is ideal for Minecraft 1.20+ without heavy modpacks.",
    "Adjust cursor sensitivity in Control Settings for smoother aiming and viewing.",
    "Select the Turnip Vulkan Driver on Snapdragon devices for peak graphics performance and stability.",
    "Customize virtual button positions and sizes anytime in the Button Customization menu.",
    "Use Import Addon to instantly install Modpacks (.mrpack), Mods (.jar), and Resource Packs!",
    "Press the ESC on-screen button to open pause menu or return to previous screen.",
    "Press the F3 button to view coordinates, biome info, and FPS performance overlay in-game.",
    "Enable Fullscreen mode in Settings to maximize display area and bypass device notch."
)

@Composable
fun GameLoadingOverlay(
    visible: Boolean,
    instanceName: String,
    mcVersion: String,
    latestLog: String,
    onClose: () -> Unit,
    onViewLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tipIndex by remember { mutableIntStateOf(0) }

    // Ganti tips setiap 5 detik
    LaunchedEffect(visible) {
        if (visible) {
            tipIndex = (GAME_TIPS.indices).random()
            while (true) {
                delay(5000)
                tipIndex = (tipIndex + 1) % GAME_TIPS.size
            }
        }
    }

    // Animasi pulsing glow halus untuk logo NUX
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(500)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0F1522),
                            Color(0xFF07090E),
                            Color.Black
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val cardShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .width(480.dp)
                    .clip(cardShape)
                    .background(Color(0xE610141E), cardShape)
                    .border(1.2.dp, Color(0x3810B981), cardShape)
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Bar (Logo & Tombol Tutup)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NuxColors.ForestGreen.copy(alpha = 0.2f))
                                    .border(1.dp, NuxColors.ForestGreen.copy(alpha = pulseAlpha), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "N",
                                    color = NuxColors.MintGreen,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "LOADING GAME",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "v$mcVersion · $instanceName",
                                    color = NuxColors.MintGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Tombol Close manual jika user ingin mengintip render di baliknya
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E222D))
                                .clickable { onClose() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Loading",
                                tint = Color(0xFFA1A1AA),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = NuxColors.ForestGreen,
                        trackColor = Color(0xFF1E2A27)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Log Status Terakhir
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = NuxColors.MintGreen,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (latestLog.isNotBlank()) latestLog else "Preparing Java & Graphics engine...",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // KOTAK TIPS LAUNCHER (CYBER TIPS CARD)
                    val tipBoxShape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tipBoxShape)
                            .background(Color(0xFF161B26), tipBoxShape)
                            .border(1.dp, Color(0x2838BDF8), tipBoxShape)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "NUX LAUNCHER TIPS",
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Animated Tip Text
                            AnimatedContent(
                                targetState = GAME_TIPS.getOrElse(tipIndex) { GAME_TIPS[0] },
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                                },
                                label = "tipAnimation"
                            ) { tipText ->
                                Text(
                                    text = "“$tipText”",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tombol Lihat Log Konsol & Keterangan Menunggu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Waiting for Mojang logo...",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            modifier = Modifier.alpha(pulseAlpha)
                        )

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E2430))
                                .clickable { onViewLog() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = NuxColors.MintGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "View Logs",
                                color = NuxColors.MintGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
