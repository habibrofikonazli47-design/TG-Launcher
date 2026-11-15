package com.israadev.nuxlauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israadev.nuxlauncher.R
import com.israadev.nuxlauncher.core.auth.AuthUser
import com.israadev.nuxlauncher.core.models.UserAccount
import com.israadev.nuxlauncher.core.social.NuxSocialManager
import com.israadev.nuxlauncher.ui.theme.resp
import com.israadev.nuxlauncher.core.lang.tr

private val TGSidebarBackground = Color(0xFF080E1A)
private val TGSidebarSurface = Color(0xFF101B2D)
private val TGSidebarBlue = Color(0xFF278BFF)
private val TGSidebarCyan = Color(0xFF50D7FF)
private val TGSidebarMuted = Color(0xFF8998AD)

data class NuxNavItem(
    val id: String,
    val icon: ImageVector,
    val labelKey: String
)

val NUX_NAV_ITEMS = listOf(
    NuxNavItem("home", Icons.Outlined.Home, "sidebar.home"),
    NuxNavItem("friends", Icons.Outlined.Group, "sidebar.friends"),
    NuxNavItem("accounts", Icons.Outlined.Person, "sidebar.accounts"),
    NuxNavItem("mods", Icons.Outlined.Extension, "sidebar.mods"),
    NuxNavItem("ai", Icons.Outlined.Psychology, "sidebar.ai"),
    NuxNavItem("settings", Icons.Outlined.Settings, "sidebar.settings")
)

/**
 * TG Launcher navigation rail. This is a presentation-only replacement:
 * navigation IDs, feature gates, unread indicators and callbacks stay unchanged.
 */
@Composable
fun NuxSidebar(
    activeTab: String,
    onTabSelected: (String) -> Unit,
    onOpenAbout: () -> Unit = {},
    currentAccount: UserAccount? = null,
    launcherUser: AuthUser? = null,
    enabledTabs: Set<String> = NUX_NAV_ITEMS.map { it.id }.toSet(),
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val sidebarShape = RoundedCornerShape(
        topStart = 0.dp,
        bottomStart = 0.dp,
        topEnd = (20.dp).resp(),
        bottomEnd = (20.dp).resp()
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(sidebarShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1728), TGSidebarBackground, Color(0xFF070B13))
                ),
                sidebarShape
            )
            .border(
                width = 1.dp,
                color = Color(0x22278BFF),
                shape = sidebarShape
            )
            .padding(top = (12.dp).resp(), bottom = (12.dp).resp(), start = (6.dp).resp(), end = (6.dp).resp()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TG brand mark
        val logoShape = RoundedCornerShape((11.dp).resp())
        Box(
            modifier = Modifier
                .size((38.dp).resp())
                .shadow((8.dp).resp(), logoShape, ambientColor = TGSidebarBlue.copy(alpha = 0.24f), spotColor = TGSidebarBlue.copy(alpha = 0.32f))
                .clip(logoShape)
                .background(
                    Brush diagonalGradient(
                        listOf(Color(0xFF173B67), Color(0xFF10233E), Color(0xFF0B1423))
                    )
                )
                .border(1.dp, Color(0x6650D7FF), logoShape)
                .clickable { onTabSelected("home") },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.tg_launcher_icon),
                contentDescription = "TG Launcher Logo",
                modifier = Modifier.size((26.dp).resp())
            )
        }

        Spacer(modifier = Modifier.height((8.dp).resp()))

        val friendsList by NuxSocialManager.friends.collectAsState()
        val totalSocialUnread = remember(friendsList) {
            friendsList.sumOf { it.unreadCount } + friendsList.count { it.isPendingReceived }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy((9.dp).resp()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NUX_NAV_ITEMS.filter { it.id in enabledTabs }.forEach { item ->
                val isSelected = activeTab == item.id
                val itemShape = RoundedCornerShape((12.dp).resp())

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .clickable { onTabSelected(item.id) }
                        .padding(vertical = (3.dp).resp()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width((46.dp).resp())
                            .height((34.dp).resp())
                            .clip(itemShape)
                            .background(
                                if (isSelected) Brush.horizontalGradient(
                                    listOf(Color(0xFF1C5FA5), Color(0xFF12365E), Color(0xFF10233D))
                                ) else Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.035f), Color.Transparent)
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = if (isSelected) Brush.horizontalGradient(
                                    listOf(Color(0xAA50D7FF), Color(0x55278BFF))
                                ) else Brush.verticalGradient(
                                    listOf(Color(0x1FFFFFFF), Color.Transparent)
                                ),
                                shape = itemShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width((3.dp).resp())
                                    .fillMaxHeight(0.58f)
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                    .background(TGSidebarCyan)
                            )
                        }
                        Icon(
                            imageVector = item.icon,
                            contentDescription = tr(item.labelKey),
                            tint = if (isSelected) TGSidebarCyan else TGSidebarMuted,
                            modifier = Modifier.size((18.dp).resp())
                        )

                        if (item.id == "friends" && totalSocialUnread > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = (5.dp).resp(), top = (4.dp).resp())
                                    .size((7.dp).resp())
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5577))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height((4.dp).resp()))

                    Text(
                        text = tr(item.labelKey),
                        color = if (isSelected) Color(0xFFEAF6FF) else TGSidebarMuted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = (8.5.sp).resp(),
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height((8.dp).resp()))

        Box(
            modifier = Modifier
                .size((30.dp).resp())
                .clip(CircleShape)
                .background(TGSidebarSurface)
                .border(1.dp, Color(0x334FCBFF), CircleShape)
                .clickable { onOpenAbout() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "About & License",
                tint = TGSidebarMuted,
                modifier = Modifier.size((16.dp).resp())
            )
        }
    }
}

private fun Brush.Companion.diagonalGradient(colors: List<Color>): Brush =
    Brush.linearGradient(colors)
