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
import app.zenelo.ui.playlists.PlaylistScreen
import app.zenelo.ui.playlists.SonicPathScreen
import app.zenelo.ui.playlists.PlaylistPickerDialog
import app.zenelo.ui.settings.SettingsPage
import app.zenelo.ui.settings.SettingsPageScreen
import app.zenelo.ui.settings.SettingsScreen
import app.zenelo.ui.settings.SwipeSettingsScreen
import app.zenelo.ui.theme.ZeneloColors
import app.zenelo.data.settings.Navigation
import app.zenelo.data.settings.Section
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.library.AudioFile
import app.zenelo.ui.components.CoverPickerDialog
import app.zenelo.ui.components.TagEditorDialog
import app.zenelo.ui.components.ZeneloSnackbar
import app.zenelo.ui.components.icon
import app.zenelo.ui.home.CustomizeHomeScreen
import app.zenelo.ui.home.CustomizeTabsScreen
import app.zenelo.ui.home.HomeNav
import app.zenelo.ui.home.HomeScreen
import app.zenelo.ui.home.SearchScreen
import app.zenelo.ui.library.AlbumScreen
import app.zenelo.ui.library.AlbumsScreen
import app.zenelo.ui.library.ArtistScreen
import app.zenelo.ui.library.ArtistsScreen
import app.zenelo.ui.library.LibraryEvent
import app.zenelo.ui.library.LibraryNav
import app.zenelo.ui.library.LibraryViewModel
import app.zenelo.ui.library.RecentScreen
import app.zenelo.ui.library.TrackDialog
import app.zenelo.ui.library.TracksScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import java.io.File

private object Routes {
    /** The bottom bar tabs, as pages of a horizontal pager. */
    const val MAIN = "main"
    const val SWIPE_SETTINGS = "settings/swipes"
    const val SETTINGS_PAGE = "settings/page/{page}"
    const val CUSTOMIZE_HOME = "settings/home"
    const val CUSTOMIZE_TABS = "settings/tabs"
    const val SEARCH = "search"
    /** A section that isn't a tab, opened from Home. */
    const val SECTION = "section/{section}"
    const val ALBUM = "album/{key}"
    const val ARTIST = "artist/{key}"
    const val PLAYLIST = "playlist/{id}"
    const val SONIC_PATH = "sonicpath"

    fun section(section: Section) = "section/${section.name}"
    fun album(key: String) = "album/${Uri.encode(key)}"
    fun artist(key: String) = "artist/${Uri.encode(key)}"
    fun playlist(id: Long) = "playlist/$id"
    fun settingsPage(page: SettingsPage) = "settings/page/${page.name}"
}

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
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    // The tabs and the last one used come from settings: wait for them rather than flash defaults.
    val loaded = settings ?: return Box(Modifier.fillMaxSize().background(ZeneloColors.Background))
    MainContent(loaded)
}

@Composable
private fun MainContent(settings: ZeneloSettings) {
    val container = appContainer()
    val nav = rememberNavController()
    // Activity-scoped so Home, Favorites and Now Playing can open a folder in the browser.
    val browserViewModel: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(container))
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val pages = Navigation.pages(settings.tabs)
    val pager = rememberPagerState(initialPage = pages.indexOf(settings.lastTab).coerceAtLeast(0)) { pages.size }
    val scope = rememberCoroutineScope()
    val selectPage: (Int) -> Unit = { page -> scope.launch { pager.animateScrollToPage(page, animationSpec = tween(180)) } }

    // Remember the tab in use; keep showing it when the bar is rearranged.
    var activeSection by remember { mutableStateOf(pages.getOrNull(pager.currentPage)) }
    LaunchedEffect(pager.settledPage, pages) {
        pages.getOrNull(pager.settledPage)?.let {
            if (it != activeSection) {
                activeSection = it
                container.settings.setLastTab(it)
            }
        }
    }
    LaunchedEffect(pages) {
        val i = pages.indexOf(activeSection)
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }

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
    val bottomReserved = with(density) { (tabBarHeight + if (playing) miniHeight else 0).toDp() }

    /** A tab switches pages; anything else opens as a sub-page with a back arrow. */
    val openSection: (Section) -> Unit = { section ->
        val page = pages.indexOf(section)
        if (page >= 0) {
            nav.popBackStack(Routes.MAIN, inclusive = false)
            selectPage(page)
        } else {
            nav.navigate(Routes.section(section))
        }
    }
    val openFolderInBrowser: (File) -> Unit = { folder ->
        browserViewModel.open(folder)
        val page = pages.indexOf(Section.FOLDERS)
        if (page >= 0) {
            nav.popBackStack(Routes.MAIN, inclusive = false)
            scope.launch { pager.scrollToPage(page) }
        } else if (nav.currentBackStackEntry?.arguments?.getString("section") != Section.FOLDERS.name) {
            nav.navigate(Routes.section(Section.FOLDERS))
        }
        sheet.close()
    }
    val libraryNav = remember(nav) {
        LibraryNav(
            onOpenAlbum = { key -> nav.navigate(Routes.album(key)) },
            onOpenArtist = { key -> nav.navigate(Routes.artist(key)) },
        )
    }
    val openPlaylist: (Long) -> Unit = { id -> nav.navigate(Routes.playlist(id)) }
    val homeNav = HomeNav(
        library = libraryNav,
        onOpenPlaylist = openPlaylist,
        onOpenSection = openSection,
        onOpenFolder = openFolderInBrowser,
        onCustomize = { nav.navigate(Routes.CUSTOMIZE_HOME) },
        onSearch = { nav.navigate(Routes.SEARCH) },
    )

    @Composable
    fun SectionPage(section: Section, isActive: Boolean, onBack: (() -> Unit)?) {
        when (section) {
            Section.HOME -> HomeScreen(libraryViewModel, homeNav)
            Section.FOLDERS -> BrowserScreen(
                browserViewModel,
                currentMediaId = playerState.mediaId,
                isPlaying = playerState.isPlaying,
                isActive = isActive,
            )
            Section.FAVORITES -> FavoritesScreen(
                currentMediaId = playerState.mediaId,
                isPlaying = playerState.isPlaying,
                onOpenFolder = { openFolderInBrowser(File(it)) },
                onOpenAlbum = libraryNav.onOpenAlbum,
                onOpenArtist = libraryNav.onOpenArtist,
                onMessage = libraryViewModel::showMessage,
                onBack = onBack,
            )
            Section.PLAYLISTS -> PlaylistsScreen(openPlaylist, libraryViewModel::showMessage, onBack)
            Section.SETTINGS -> SettingsScreen(onOpenPage = { nav.navigate(Routes.settingsPage(it)) }, onBack = onBack)
            Section.ALBUMS -> AlbumsScreen(libraryViewModel, libraryNav, onBack)
            Section.ARTISTS -> ArtistsScreen(libraryViewModel, libraryNav, onBack)
            Section.TRACKS -> TracksScreen(libraryViewModel, onBack)
            Section.RECENT -> RecentScreen(libraryViewModel, onBack)
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height }) {
        Scaffold(
            containerColor = ZeneloColors.Background,
            // The mini player and the tab bar are drawn over the scaffold (below): this only keeps their room.
            bottomBar = { Spacer(Modifier.fillMaxWidth().height(bottomReserved)) },
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
                    // Back from another tab returns to the first one before leaving the app.
                    BackHandler(enabled = pager.currentPage != 0) { selectPage(0) }
                    // Horizontal swipes switch tabs. Track rows keep their own swipe actions: their
                    // gesture handler sees the drag first and consumes it.
                    HorizontalPager(
                        pager,
                        key = { pages.getOrNull(it)?.name ?: it },
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pager,
                            snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                        ),
                    ) { page ->
                        pages.getOrNull(page)?.let { SectionPage(it, isActive = pager.currentPage == page, onBack = null) }
                    }
                }
                composable(Routes.SECTION) { entry ->
                    val section = entry.arguments?.getString("section")?.let { name -> Section.entries.firstOrNull { it.name == name } }
                    if (section != null) SectionPage(section, isActive = true) { nav.popBackStack() }
                }
                composable(Routes.ALBUM) { entry ->
                    AlbumScreen(libraryViewModel, entry.arguments?.getString("key").orEmpty(), libraryNav) { nav.popBackStack() }
                }
                composable(Routes.ARTIST) { entry ->
                    ArtistScreen(libraryViewModel, entry.arguments?.getString("key").orEmpty(), libraryNav) { nav.popBackStack() }
                }
                composable(Routes.PLAYLIST) { entry ->
                    val id = entry.arguments?.getString("id")?.toLongOrNull()
                    if (id != null) PlaylistScreen(libraryViewModel, id) { nav.popBackStack() }
                }
                composable(Routes.SONIC_PATH) { SonicPathScreen(libraryViewModel::showMessage) { nav.popBackStack() } }
                composable(Routes.SEARCH) { SearchScreen(libraryViewModel, libraryNav) { nav.popBackStack() } }
                composable(Routes.SETTINGS_PAGE) { entry ->
                    val page = entry.arguments?.getString("page")?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
                    if (page != null) {
                        SettingsPageScreen(
                            page,
                            onBack = { nav.popBackStack() },
                            onOpenSwipeSettings = { nav.navigate(Routes.SWIPE_SETTINGS) },
                            onCustomizeHome = { nav.navigate(Routes.CUSTOMIZE_HOME) },
                            onCustomizeTabs = { nav.navigate(Routes.CUSTOMIZE_TABS) },
                            onMessage = libraryViewModel::showMessage,
                        )
                    }
                }
                composable(Routes.SWIPE_SETTINGS) { SwipeSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.CUSTOMIZE_HOME) { CustomizeHomeScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.CUSTOMIZE_TABS) { CustomizeTabsScreen(onBack = { nav.popBackStack() }) }
            }
        }

        LibraryMessages(libraryViewModel, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomReserved))

        // Sonic Path ends set from any menu open its screen (over Now Playing too); Auto DJ's failures show here.
        LaunchedEffect(Unit) {
            container.sonicPath.open.collect { problem ->
                if (problem != null) {
                    libraryViewModel.showMessage(problem)
                } else if (nav.currentDestination?.route != Routes.SONIC_PATH) {
                    nav.navigate(Routes.SONIC_PATH)
                    sheet.close()
                } else {
                    sheet.close()
                }
            }
        }
        LaunchedEffect(Unit) { container.autoDj.messages.collect(libraryViewModel::showMessage) }

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
                            NowPlayingScreen(
                                sheet,
                                onOpenFolder = openFolderInBrowser,
                                onOpenAlbum = { key ->
                                    sheet.close()
                                    libraryNav.onOpenAlbum(key)
                                },
                                onOpenArtist = { key ->
                                    sheet.close()
                                    libraryNav.onOpenArtist(key)
                                },
                                artists = libraryViewModel.artists,
                            )
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

        // Tabs on top of the collapsed sheet; they slide away as it opens. No tabs: no bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { tabBarHeight = it.height }
                .graphicsLayer { translationY = (1f - sheet.fraction) * size.height }
                .background(ZeneloColors.Bar)
                .navigationBarsPadding(),
        ) {
            if (settings.tabs.isNotEmpty()) {
                BottomBar(settings.tabs, selected = pager.currentPage) { page ->
                    nav.popBackStack(Routes.MAIN, inclusive = false)
                    selectPage(page)
                }
            }
        }
    }
}

/** Snackbars, the delete confirmation and the ⋮ menu's dialogs of the library pages. */
@Composable
private fun LibraryMessages(vm: LibraryViewModel, modifier: Modifier) {
    val snackbar = remember { SnackbarHostState() }
    var icon by remember { mutableStateOf<ImageVector?>(null) }
    var confirmDelete by remember { mutableStateOf<List<AudioFile>?>(null) }
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.ConfirmDelete -> confirmDelete = event.files
                is LibraryEvent.Message -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    icon = event.action?.icon
                    val result = snackbar.showSnackbar(event.text, actionLabel = if (event.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
                }
            }
        }
    }
    SnackbarHost(snackbar, modifier) { ZeneloSnackbar(it, icon) }

    val pickerFlow = appContainer().playlistPicker.request
    val picking by pickerFlow.collectAsStateWithLifecycle()
    picking?.let { paths ->
        PlaylistPickerDialog(paths, onDismiss = appContainer().playlistPicker::dismiss, onDone = vm::showMessage)
    }

    when (val d = dialog) {
        is TrackDialog.EditTags -> TagEditorDialog(d.path, d.fromFileName, onDismiss = { vm.openDialog(null) }, onDone = vm::showMessage)
        is TrackDialog.Cover -> CoverPickerDialog(d.path, onDismiss = { vm.openDialog(null) }, onDone = vm::showMessage)
        null -> Unit
    }
    confirmDelete?.let { files ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = ZeneloColors.Card,
            title = { Text(if (files.size == 1) "Delete file?" else "Delete ${files.size} files?") },
            text = { Text(if (files.size == 1) files.first().name else "They'll be removed from the device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(files)
                    confirmDelete = null
                }) { Text("Delete", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Icon + label tabs; the selected tab gets a celadon pill, as in the design. */
@Composable
private fun BottomBar(tabs: List<Section>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { index, tab ->
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
                    maxLines = 1,
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
