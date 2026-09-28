package app.zenelo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.zenelo.player.PlayerController
import app.zenelo.player.PlayerUiState
import app.zenelo.ui.browser.BrowserScreen
import app.zenelo.ui.browser.BrowserViewModel
import app.zenelo.ui.components.Artwork
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.marquee
import app.zenelo.ui.favorites.FavoritesScreen
import app.zenelo.ui.nowplaying.NowPlayingScreen
import app.zenelo.ui.nowplaying.PlayerSheet
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import app.zenelo.ui.playlists.PlaylistsScreen
import app.zenelo.ui.settings.SettingsScreen
import app.zenelo.ui.settings.SwipeSettingsScreen
import app.zenelo.ui.theme.ZeneloColors
import java.io.File

private object Routes {
    /** The four tabs, as pages of a horizontal pager. */
    const val MAIN = "main"
    const val SWIPE_SETTINGS = "settings/swipes"
}

private const val TAB_FOLDERS = 0
private const val TAB_FAVORITES = 1
private const val TAB_PLAYLISTS = 2
private const val TAB_SETTINGS = 3

@Composable
fun ZeneloRoot() {
    val context = LocalContext.current
    var hasFileAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        hasFileAccess = Environment.isExternalStorageManager()
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (hasFileAccess) {
        MainScaffold()
    } else {
        FileAccessGate {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        }
    }
}

@Composable
private fun FileAccessGate(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ZeneloColors.Background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zenelo", style = MaterialTheme.typography.titleLarge, color = ZeneloColors.Mustard)
        Spacer(Modifier.height(12.dp))
        Text(
            "To browse your music folders, allow access to all files.",
            style = MaterialTheme.typography.bodyMedium,
            color = ZeneloColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun MainScaffold() {
    val container = appContainer()
    val nav = rememberNavController()
    // Activity-scoped so Favorites and Now Playing can open a folder in the browser.
    val browserViewModel: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(container))
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()
    val selectTab: (Int) -> Unit = { page -> scope.launch { tabs.animateScrollToPage(page, animationSpec = tween(180)) } }
    val sheet = remember { PlayerSheet(scope) }
    val sheetVisible by remember { derivedStateOf { sheet.fraction < 1f } }
    // The mini player only shows while the sheet is (nearly) collapsed: it fades into Now Playing.
    // Kept (invisible) while a finger is down: removing it would cut off the drag it started.
    val miniVisible by remember { derivedStateOf { sheet.fraction > 0.8f || sheet.held } }
    val playing = playerState.mediaId != null
    // Measured: the sheet rests with its top (the mini player) right above the tab bar.
    var rootHeight by remember { mutableIntStateOf(0) }
    var tabBarHeight by remember { mutableIntStateOf(0) }
    var miniHeight by remember { mutableIntStateOf(0) }
    val collapsedTop = (rootHeight - tabBarHeight - miniHeight).toFloat()
    sheet.heightPx = collapsedTop.coerceAtLeast(1f)
    val density = LocalDensity.current
    val openFolderInBrowser: (File) -> Unit = { folder ->
        browserViewModel.open(folder)
        nav.popBackStack(Routes.MAIN, inclusive = false)
        scope.launch { tabs.scrollToPage(TAB_FOLDERS) }
        sheet.close()
    }

    Box(Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height }) {
        Scaffold(
            containerColor = ZeneloColors.Background,
            // The mini player and the tab bar are drawn over the scaffold (below): this only keeps their room.
            bottomBar = {
                val reserved = tabBarHeight + if (playing) miniHeight else 0
                Spacer(Modifier.fillMaxWidth().height(with(density) { reserved.toDp() }))
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = Routes.MAIN,
                modifier = Modifier.padding(padding),
                // Short fades: navigation's default 700ms crossfade feels sluggish.
                enterTransition = { fadeIn(tween(120)) },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(120)) },
                popExitTransition = { fadeOut(tween(90)) },
            ) {
                composable(Routes.MAIN) {
                    // Back from another tab returns to Folders before leaving the app.
                    BackHandler(enabled = tabs.currentPage != TAB_FOLDERS) { selectTab(TAB_FOLDERS) }
                    // Horizontal swipes switch tabs. Track rows keep their own swipe actions: their
                    // gesture handler sees the drag first and consumes it.
                    HorizontalPager(
                        tabs,
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = tabs,
                            snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                        ),
                    ) { page ->
                        when (page) {
                            TAB_FOLDERS -> BrowserScreen(
                                browserViewModel,
                                currentMediaId = playerState.mediaId,
                                isPlaying = playerState.isPlaying,
                                isActive = tabs.currentPage == TAB_FOLDERS,
                            )
                            TAB_FAVORITES -> FavoritesScreen(
                                currentMediaId = playerState.mediaId,
                                isPlaying = playerState.isPlaying,
                                onOpenFolder = { openFolderInBrowser(File(it)) },
                            )
                            TAB_PLAYLISTS -> PlaylistsScreen()
                            TAB_SETTINGS -> SettingsScreen(onOpenSwipeSettings = { nav.navigate(Routes.SWIPE_SETTINGS) })
                        }
                    }
                }
                composable(Routes.SWIPE_SETTINGS) { SwipeSettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }

        // The player sheet: collapsed it is just the mini player above the tab bar; dragged up it
        // grows into Now Playing (the mini player fading out), and back. Now Playing is composed
        // only while any of it shows.
        if (playing || sheetVisible) {
            if (sheetVisible) BackHandler { sheet.close() }
            CompositionLocalProvider(LocalContentColor provides ZeneloColors.TextPrimary) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = sheet.fraction * collapsedTop }
                        .drawBehind { drawRect(lerp(ZeneloColors.Background, ZeneloColors.Bar, sheet.fraction)) }
                        // Keeps taps from reaching the tabs underneath, like a Surface.
                        .pointerInput(Unit) {},
                ) {
                    if (sheetVisible) {
                        Box(
                            Modifier
                                .systemBarsPadding()
                                .graphicsLayer { alpha = ((0.95f - sheet.fraction) / 0.45f).coerceIn(0f, 1f) },
                        ) {
                            NowPlayingScreen(sheet, onOpenFolder = openFolderInBrowser)
                        }
                    }
                    if (playing && miniVisible) {
                        MiniPlayer(
                            playerState,
                            container.player,
                            sheet,
                            Modifier
                                .onSizeChanged { miniHeight = it.height }
                                .graphicsLayer { alpha = ((sheet.fraction - 0.8f) / 0.2f).coerceIn(0f, 1f) },
                        )
                    }
                }
            }
        }

        // Tabs on top of the collapsed sheet; they slide away as it opens.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { tabBarHeight = it.height }
                .graphicsLayer { translationY = (1f - sheet.fraction) * size.height }
                .background(ZeneloColors.Bar)
                .navigationBarsPadding(),
        ) {
            BottomBar(selected = if (route == Routes.SWIPE_SETTINGS) TAB_SETTINGS else tabs.currentPage) { page ->
                nav.popBackStack(Routes.MAIN, inclusive = false)
                selectTab(page)
            }
        }
    }
}

private data class Tab(val icon: ImageVector, val label: String)

private val TABS = listOf(
    Tab(Icons.Outlined.Folder, "Folders"),
    Tab(Icons.Outlined.FavoriteBorder, "Favorites"),
    Tab(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists"),
    Tab(Icons.Outlined.Settings, "Settings"),
)

/** Icon + label tabs; the selected tab gets a celadon pill, as in the design. */
@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        TABS.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 48.dp, height = 26.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) ZeneloColors.CeladonTint else ZeneloColors.Bar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (isSelected) ZeneloColors.Celadon else ZeneloColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) ZeneloColors.TextPrimary else ZeneloColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, player: PlayerController, sheet: PlayerSheet, modifier: Modifier = Modifier) {
    val position by player.position.collectAsStateWithLifecycle()
    // Swipe up pulls Now Playing in under the finger; a tap opens it.
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .background(ZeneloColors.Bar)
            .clickable(onClick = sheet::open)
            .draggable(
                state = rememberDraggableState { delta -> sheet.dragBy(delta) },
                orientation = Orientation.Vertical,
                onDragStarted = { sheet.dragStart() },
                onDragStopped = { velocity -> sheet.dragEnd(velocity, threshold) },
            ),
    ) {
        LinearProgressIndicator(
            // Read inside the lambda so position ticks only redraw the bar.
            progress = { if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = ZeneloColors.TextSecondary,
            trackColor = ZeneloColors.Bar,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(state.artwork, Modifier.size(38.dp), cornerRadius = 4.dp, decodeSizePx = 128, file = state.artworkFile)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.marquee())
                state.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { player.togglePlay() }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause")
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
        HorizontalDivider(color = ZeneloColors.Background, thickness = 1.dp)
    }
}
