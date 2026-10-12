package app.zenelo.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Tapping the bottom bar's tab that's already selected: the page on it scrolls to the top. The
 * root provides each tab page its own events; pages opened as sub-pages get none.
 */
val LocalScrollToTop = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

/** A list state that scrolls to the top on [LocalScrollToTop]. */
@Composable
fun rememberTopListState(): LazyListState = rememberLazyListState().also { ScrollToTopOn(it) }

@Composable
fun rememberTopGridState(): LazyGridState = rememberLazyGridState().also { ScrollToTopOn(it) }

@Composable
fun rememberTopScrollState(): ScrollState = rememberScrollState().also { ScrollToTopOn(it) }

@Composable
fun ScrollToTopOn(state: LazyListState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(state, events) { events.collect { state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopOn(state: LazyGridState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(state, events) { events.collect { state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopOn(state: ScrollState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(state, events) { events.collect { state.animateScrollTo(0) } }
}
