package com.israadev.nuxlauncher.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.auth.AuthService
import com.israadev.nuxlauncher.core.auth.SubscriptionInfo
import com.israadev.nuxlauncher.core.network.RemoteLauncherConfig
import com.israadev.nuxlauncher.ui.components.NuxBadge
import com.israadev.nuxlauncher.ui.components.NuxButton
import com.israadev.nuxlauncher.ui.components.NuxCard
import com.israadev.nuxlauncher.ui.components.NuxDialog
import com.israadev.nuxlauncher.ui.theme.NuxColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class PremiumFeatureItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val desc: String
)

private val PREMIUM_FEATURES = listOf(
    PremiumFeatureItem(Icons.Outlined.Videocam, "Built-in Screen Recorder", "Record high-FPS Minecraft gameplay with internal audio directly to your gallery."),
    PremiumFeatureItem(Icons.Outlined.Mic, "Voice Rooms Real-Time", "Join and create low-latency multiplayer voice rooms (LiveKit WebRTC)."),
    PremiumFeatureItem(Icons.Outlined.AllInclusive, "Unlimited Game Instances", "Create unlimited Minecraft instances and modpacks (Free users limited to 3)."),
    PremiumFeatureItem(Icons.Outlined.VideoLibrary, "Custom Video Hero Banner", "Set custom MP4 videos as animated backgrounds on the launcher dashboard."),
    PremiumFeatureItem(Icons.Outlined.WorkspacePremium, "Exclusive Premium Profile Badge", "Cyber Golden / Emerald Premium badge in profile, header, and multiplayer chat."),
    PremiumFeatureItem(Icons.Outlined.Science, "Snapshot & Beta Versions Access", "Freely download and play latest Snapshot builds, Old Beta, and Old Alpha."),
    PremiumFeatureItem(Icons.Outlined.People, "Multi-Account Switcher", "Save and switch unlimited accounts (Free users limited to 2)."),
    PremiumFeatureItem(Icons.Outlined.Speed, "Turbo Multi-Thread Downloader", "Accelerate asset and library downloads with 48 parallel worker threads.")
)

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "Telah Teraktivasi"
    return try {
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm 'WIB'", Locale("id", "ID"))
        sdf.format(Date(timestamp))
    } catch (_: Exception) {
        "Telah Teraktivasi"
    }
}

private fun formatExpiry(expiresAt: String?): Pair<String, String> {
    if (expiresAt.isNullOrBlank() || expiresAt.equals("lifetime", ignoreCase = true)) {
        return Pair("Permanen (Selamanya)", "Tanpa Batas Waktu")
    }
    return try {
        val epoch = expiresAt.toLongOrNull()
        if (epoch != null) {
            val diffMs = epoch - System.currentTimeMillis()
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
            val dateStr = sdf.format(Date(epoch))
            if (diffMs > 0) {
                val days = TimeUnit.MILLISECONDS.toDays(diffMs)
                val hours = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
                val remainingStr = if (days > 0) "$days Hari lagi" else "$hours Jam lagi"
                Pair(dateStr, remainingStr)
            } else {
                Pair(dateStr, "Masa Aktif Berakhir")
            }
        } else {
            Pair(expiresAt, "Active")
        }
    } catch (_: Exception) {
        Pair(expiresAt ?: "Active", "Active")
    }
}

private fun maskKey(key: String?): String {
    return com.israadev.nuxlauncher.core.utils.PrivacyMasker.maskKey(key)
}

@Composable
fun NuxPremiumDialog(
    initialPrompt: String? = null,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val launcherUser by AccountManager.launcherUser.collectAsState()
    val remoteConfig by RemoteLauncherConfig.config.collectAsState()
    val premiumPurchaseUrl = RemoteLauncherConfig.text(remoteConfig, "general", "premiumPurchaseUrl")
        .ifBlank { RemoteLauncherConfig.text(remoteConfig, "premium", "purchaseUrl") }
    val monthlyPurchaseUrl = RemoteLauncherConfig.text(remoteConfig, "general", "premiumMonthlyPurchaseUrl").ifBlank { premiumPurchaseUrl }
    val yearlyPurchaseUrl = RemoteLauncherConfig.text(remoteConfig, "general", "premiumYearlyPurchaseUrl").ifBlank { premiumPurchaseUrl }
    val lifetimePurchaseUrl = RemoteLauncherConfig.text(remoteConfig, "general", "premiumLifetimePurchaseUrl").ifBlank { premiumPurchaseUrl }
    fun openPurchaseUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(context, "Link pembelian paket ini belum diatur oleh admin TG Launcher.", Toast.LENGTH_LONG).show()
        } else {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(context, "Tidak dapat membuka link pembelian.", Toast.LENGTH_SHORT).show() }
        }
    }

    var licenseKeyInput by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }
    var activationSuccess by remember { mutableStateOf<String?>(null) }
    var showRedeemBoxForPremium by remember { mutableStateOf(false) }

    // Live subscription info fetched from server
    var subscriptionInfo by remember { mutableStateOf<SubscriptionInfo?>(null) }
    var isRefreshingInfo by remember { mutableStateOf(false) }

    val isUserPremium = launcherUser?.isActivated == true || subscriptionInfo?.isActivated == true
    val activeTier = (subscriptionInfo?.tier ?: launcherUser?.tier ?: "unactivated").lowercase()

    // Tier-based theme styling
    val isLifetime = activeTier == "lifetime"
    val isYearly = activeTier == "yearly"
    val isMonthly = activeTier == "monthly"

    val themeAccentColor = when {
        isLifetime -> Color(0xFFF59E0B) // Cyber Gold / Amber
        isYearly -> Color(0xFF10B981)   // Emerald Green
        isMonthly -> Color(0xFF0EA5E9)  // Electric Cyan
        else -> Color(0xFFF59E0B)
    }

    val themeGradient = when {
        isLifetime -> listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFF78350F))
        isYearly -> listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF064E3B))
        isMonthly -> listOf(Color(0xFF0EA5E9), Color(0xFF0284C7), Color(0xFF082F49))
        else -> listOf(Color(0xFFF59E0B), Color(0xFFB45309), Color(0xFF451A03))
    }

    // Auto-fetch fresh subscription details on open
    LaunchedEffect(launcherUser?.uid) {
        val uid = launcherUser?.uid
        if (!uid.isNullOrBlank()) {
            isRefreshingInfo = true
            val info = AuthService.fetchSubscriptionInfo(uid, launcherUser?.idToken)
            if (info != null) {
                subscriptionInfo = info
                if (launcherUser != null && (launcherUser!!.activatedAt == null || launcherUser!!.expiresAt == null)) {
                    val updated = launcherUser!!.copy(
                        isActivated = info.isActivated,
                        tier = if (info.isActivated) info.tier else launcherUser!!.tier,
                        activatedAt = info.activatedAt ?: launcherUser!!.activatedAt,
                        expiresAt = info.expiresAt ?: launcherUser!!.expiresAt,
                        redeemedKey = info.redeemedKey ?: launcherUser!!.redeemedKey
                    )
                    AccountManager.saveAuthUser(context, updated)
                }
            }
            isRefreshingInfo = false
        }
    }

    NuxDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxWidth(0.92f),
        fillMaxHeight = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // ==========================================
            // 1. TOP HEADER BAR (Double-Bezel Aura)
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Outer bezel icon ring
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(themeAccentColor.copy(alpha = 0.28f), Color.Transparent)
                                ),
                                CircleShape
                            )
                            .border(1.5.dp, themeAccentColor.copy(alpha = 0.7f), CircleShape)
                            .padding(2.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF161922), CircleShape)
                                .border(1.dp, Color(0x33FFFFFF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isUserPremium) Icons.Default.Star else Icons.Outlined.WorkspacePremium,
                                contentDescription = null,
                                tint = themeAccentColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "TG LAUNCHER PREMIUM",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.5.sp,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            NuxBadge(
                                text = if (isUserPremium) "PREMIUM ACTIVE" else "UPGRADE",
                                backgroundColor = if (isUserPremium) themeAccentColor.copy(alpha = 0.25f) else Color(0xFFF59E0B).copy(alpha = 0.2f),
                                textColor = if (isUserPremium) themeAccentColor else Color(0xFFFBBF24)
                            )
                        }
                        Text(
                            text = if (isUserPremium)
                                "Premium Membership active • All 8 exclusive features, Voice Rooms, & unlimited slots unlocked"
                            else
                                "Unlock all 8 exclusive features, multiplayer Voice Rooms, and unlimited slots",
                            color = NuxColors.GrayNeutral,
                            fontSize = 8.5.sp
                        )
                    }
                }

                // Close Button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(NuxColors.SurfaceInput, CircleShape)
                        .border(1.dp, NuxColors.CardBorder, CircleShape)
                        .clickable { onDismissRequest() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = NuxColors.GrayNeutral,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ==========================================
            // 2. MAIN BODY (Left Features + Right Details/Upgrade)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ------------------------------------------
                // LEFT COLUMN: 7 Showcase Cards (Double-Bezel)
                // ------------------------------------------
                Column(
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!initialPrompt.isNullOrBlank()) {
                        NuxCard(
                            backgroundColor = Color(0xFF1E1710),
                            borderColor = Color(0xFFF59E0B).copy(alpha = 0.5f),
                            cornerRadius = 8.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = initialPrompt,
                                color = Color(0xFFFBBF24),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "8 EXCLUSIVE TG PREMIUM FEATURES:",
                            color = NuxColors.GrayNeutral,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        if (isUserPremium) {
                        Text(
                            text = "PREMIUM ACCESS MANAGED BY TG ADMIN",
                            color = themeAccentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Paket, masa aktif, dan delapan fitur Premium akun ini diatur langsung dari Admin Panel. Hak akses akan diperbarui otomatis saat akun menyinkronkan data.",
                            color = NuxColors.GrayNeutral,
                            fontSize = 8.sp,
                            lineHeight = 12.sp
                        )
                        val assignedFeatures = launcherUser?.features.orEmpty()
                        val premiumFeatureLabels = listOf(
                            "screen_recorder" to "Built-in Screen Recorder",
                            "voice_rooms" to "Voice Rooms Real-Time",
                            "unlimited_instances" to "Unlimited Game Instances",
                            "custom_video_hero" to "Custom Video Hero Banner",
                            "premium_badge" to "Exclusive Premium Profile Badge",
                            "snapshot_beta_versions" to "Snapshot & Beta Versions Access",
                            "unlimited_accounts" to "Multi-Account Switcher",
                            "turbo_downloader" to "Turbo Multi-Thread Downloader"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            premiumFeatureLabels.forEach { (key, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (assignedFeatures.contains(key)) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = if (assignedFeatures.contains(key)) NuxColors.MintGreen else NuxColors.GrayNeutral,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = label,
                                        color = if (assignedFeatures.contains(key)) Color.White else NuxColors.GrayNeutral,
                                        fontSize = 7.5.sp
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Masa aktif: ${launcherUser?.expiresAt ?: "Tidak dibatasi"}",
                            color = NuxColors.GrayNeutral,
                            fontSize = 7.sp
                        )
                    } else {
                        // ==========================================
                        // STATE B: PENGGUNA FREE (UPGRADE PLANS + KEY)
                        // ==========================================
                        Text(
                            text = "UPGRADE PACKAGE OPTIONS:",
                            color = NuxColors.GrayNeutral,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 1 Bulan
                            PlanCard(
                                title = "MONTHLY PREMIUM",
                                price = "Rp 5.000",
                                subtext = "Try features",
                                modifier = Modifier.weight(1f),
                                onSelect = {
                                    openPurchaseUrl(monthlyPurchaseUrl)
                                }
                            )

                            // 1 Tahun (Best Value)
                            PlanCard(
                                title = "YEARLY PREMIUM",
                                price = "Rp 50.000",
                                subtext = "Save 16%",
                                isBestValue = true,
                                modifier = Modifier.weight(1.15f),
                                onSelect = {
                                    openPurchaseUrl(yearlyPurchaseUrl)
                                }
                            )

                            // Lifetime
                            PlanCard(
                                title = "LIFETIME PREMIUM",
                                price = "Rp 125.000",
                                subtext = "One-time purchase",
                                modifier = Modifier.weight(1f),
                                onSelect = {
                                    openPurchaseUrl(lifetimePurchaseUrl)
                                }
                            )
                        }

                        // Premium access is assigned by administrators; no in-app key redemption.
                        Text(
                            text = "Premium diaktifkan langsung oleh Admin TG Launcher setelah akun kamu disetujui. Login dengan akun ini setelah admin memberikan akses.",
                            color = NuxColors.GrayNeutral,
                            fontSize = 8.sp,
                            lineHeight = 11.sp
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun BentoDetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primaryValue: String,
    secondaryValue: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF141822), RoundedCornerShape(7.dp))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(7.dp))
            .padding(6.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = label,
                    color = NuxColors.GrayNeutral,
                    fontSize = 6.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = primaryValue,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 8.sp,
                maxLines = 1
            )
            Text(
                text = secondaryValue,
                color = accentColor.copy(alpha = 0.9f),
                fontSize = 6.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RedeemKeyCard(
    licenseKeyInput: String,
    onKeyChange: (String) -> Unit,
    isActivating: Boolean,
    activationError: String?,
    activationSuccess: String?,
    onActivateClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1219), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .padding(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131722), RoundedCornerShape(8.dp))
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ACTIVATE / ENTER LICENSE KEY",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }

            // Key Input Field
            val shape = RoundedCornerShape(7.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color(0xFF1A1F2C), shape)
                    .border(1.dp, Color(0x33FFFFFF), shape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = licenseKeyInput,
                    onValueChange = { raw ->
                        var clean = raw.filter { it.isLetterOrDigit() }.uppercase()
                        if (clean.length > 16) clean = clean.substring(0, 16)
                        val formatted = buildString {
                            for (i in clean.indices) {
                                if (i > 0 && i % 4 == 0) append('-')
                                append(clean[i])
                            }
                        }
                        onKeyChange(formatted)
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(0xFFF59E0B)),
                    modifier = Modifier.fillMaxWidth()
                )
                if (licenseKeyInput.isEmpty()) {
                    Text(
                        text = "e.g. XXXX-XXXX-XXXX-XXXX",
                        color = Color(0xFF52525B),
                        fontSize = 9.5.sp
                    )
                }
            }

            if (!activationError.isNullOrBlank()) {
                Text(
                    text = activationError,
                    color = NuxColors.ErrorRed,
                    fontSize = 8.sp,
                    lineHeight = 11.sp
                )
            }

            if (!activationSuccess.isNullOrBlank()) {
                Text(
                    text = activationSuccess,
                    color = NuxColors.ForestGreen,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            NuxButton(
                onClick = onActivateClick,
                backgroundColor = Color(0xFFF59E0B),
                contentColor = Color.Black,
                enabled = !isActivating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            ) {
                Text(
                    text = if (isActivating) "VERIFYING..." else "ACTIVATE KEY NOW",
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    title: String,
    price: String,
    subtext: String,
    isBestValue: Boolean = false,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    // Outer Doppelrand
    Box(
        modifier = modifier
            .background(
                if (isBestValue) Color(0xFF241B12) else Color(0xFF10131B),
                RoundedCornerShape(9.dp)
            )
            .border(
                1.dp,
                if (isBestValue) Color(0xFFF59E0B).copy(alpha = 0.6f) else Color(0x22FFFFFF),
                RoundedCornerShape(9.dp)
            )
            .padding(1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isBestValue) Color(0xFF191410) else Color(0xFF141822),
                    RoundedCornerShape(7.5.dp)
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isBestValue) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFFF59E0B), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text("POPULAR", color = Color.Black, fontSize = 6.5.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(title, color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black)
            Text(price, color = if (isBestValue) Color(0xFFFBBF24) else Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(subtext, color = NuxColors.GrayNeutral, fontSize = 7.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(if (isBestValue) Color(0xFFF59E0B) else Color(0xFF27272A), RoundedCornerShape(5.dp))
                .clickable { onSelect() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Buy", color = if (isBestValue) Color.Black else Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = if (isBestValue) Color.Black else Color.White,
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        }
    }
}
