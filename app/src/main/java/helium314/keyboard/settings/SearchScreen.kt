// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.settings.preferences.PreferenceCategory

val LocalHazeState = staticCompositionLocalOf<HazeState> { error("No HazeState provided") }
val LocalSearchInnerPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

data class SearchState(
    val showSearch: Boolean,
    val searchText: TextFieldValue,
    val onSearchChange: (TextFieldValue) -> Unit,
    val setShowSearch: (Boolean) -> Unit,
    val searchField: @Composable () -> Unit
)
val LocalSearchState = staticCompositionLocalOf<SearchState?> { null }

val materialSymbols = FontFamily(Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f))))

val googleSansFlex = FontFamily(
    Font(R.font.google_sans_flex, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("ROND", 100f))),
    Font(R.font.google_sans_flex, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("ROND", 100f))),
    Font(R.font.google_sans_flex, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("ROND", 100f))),
    Font(R.font.google_sans_flex, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("ROND", 100f)))
)

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    hideTopSearchBar: Boolean = false,
    showBackButton: Boolean = true,
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        hideTopSearchBar = hideTopSearchBar,
        showBackButton = showBackButton,
        title = { Text(title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        content = {
            if (content != null) content()
            else {
                val hazeState = LocalHazeState.current
                val topPadding = LocalSearchInnerPadding.current
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = topPadding.calculateTopPadding(),
                                    bottom = innerPadding.calculateBottomPadding()
                                )
                        ) {
                            val searchState = LocalSearchState.current
                            if (searchState != null) {
                                searchState.searchField()
                            }
                            settings.forEach {
                                if (it is Int) {
                                    PreferenceCategory(stringResource(it))
                                } else {
                                    AnimatedVisibility(visible = it != null) {
                                        if (it != null)
                                            SettingsActivity.settingsContainer[it]?.Preference()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    itemKey: ((T) -> Any)? = null,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    hideTopSearchBar: Boolean = false,
    showBackButton: Boolean = true,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }
    val isBlurSupported = remember { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
    val ctx = LocalContext.current

    fun setShowSearch(value: Boolean) {
        showSearch = value
        if (!value) searchText = TextFieldValue()
    }
    BackHandler {
        if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
        else onClickBack()
    }
    
    val isDark = isSystemInDarkTheme()
    val scaffoldBg = if (isDark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer
    val topBarBg = MaterialTheme.colorScheme.surface
    
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    val searchFieldContent = remember {
        movableContentOf {
            StaticSearchField(
                search = searchText,
                onSearchChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    }

    val searchState = remember(showSearch, searchText, searchFieldContent) {
        SearchState(
            showSearch = showSearch,
            searchText = searchText,
            onSearchChange = { searchText = it },
            setShowSearch = ::setShowSearch,
            searchField = searchFieldContent
        )
    }
    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalSearchState provides searchState
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = scaffoldBg,
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    val topBarHazeStyle = remember(topBarBg) {
                        HazeStyle(blurRadius = 16.dp, tint = topBarBg.copy(alpha = 0.3f))
                    }
                    AnimatedVisibility(
                        visible = !SettingsActivity.isTopBarHidden && SettingsActivity.activeOverlay == null,
                        enter = fadeIn(animationSpec = tween(350)) + slideInVertically(initialOffsetY = { -it }, animationSpec = tween(350)),
                        exit = fadeOut(animationSpec = tween(350)) + slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(350))
                    ) {
                    Box {
                        if (isBlurSupported) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                                    .hazeChild(state = hazeState, style = topBarHazeStyle)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(topBarBg)
                            )
                        }
                        Column(
                            modifier = Modifier.layout { measurable, constraints ->
                                val fraction = scrollBehavior.state.collapsedFraction
                                val bottomPadding = (32.dp.toPx() * (1f - fraction)).roundToInt()
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height + bottomPadding) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                        ) {
                            MaterialTheme(
                                colorScheme = MaterialTheme.colorScheme,
                                shapes = MaterialTheme.shapes,
                                typography = MaterialTheme.typography.copy(
                                    headlineMedium = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = googleSansFlex,
                                        fontSize = 36.sp,
                                        lineHeight = 44.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    titleLarge = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = googleSansFlex,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            ) {
                                LargeTopAppBar(
                                    title = { 
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 12.dp)
                                                .layout { measurable, constraints ->
                                                    val fraction = scrollBehavior.state.collapsedFraction
                                                    val topPadding = (40.dp.toPx() * (1f - fraction)).roundToInt()
                                                    val placeable = measurable.measure(constraints)
                                                    layout(placeable.width, placeable.height + topPadding) {
                                                        placeable.placeRelative(0, topPadding)
                                                    }
                                                }
                                        ) { title() } 
                                    },
                                scrollBehavior = scrollBehavior,
                                navigationIcon = {
                                    if (showBackButton) {
                                        IconButton(
                                            onClick = {
                                                if (showSearch) setShowSearch(false)
                                                else onClickBack()
                                            },
                                            modifier = Modifier.padding(start = 12.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                            ) {
                                                Text("arrow_back", fontFamily = materialSymbols, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                },
                                actions = {
                                    if (icon != null)
                                        icon()
                                    if (menu != null)
                                        Box {
                                            var showMenu by remember { mutableStateOf(false) }
                                            IconButton(
                                                onClick = { showMenu = true }
                                            ) { Text("more_vert", fontFamily = materialSymbols, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) }
                                            DropdownMenu(
                                                expanded = showMenu,
                                                onDismissRequest = { showMenu = false }
                                            ) {
                                                menu.forEach {
                                                    DropdownMenuItem(
                                                        text = { Text(it.first) },
                                                        onClick = { showMenu = false; it.second() }
                                                    )
                                                }
                                            }
                                        }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    scrolledContainerColor = Color.Transparent
                                )
                            )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                val contentModifier = Modifier.fillMaxSize()

                if (searchText.text.isBlank() && content != null) {
                    CompositionLocalProvider(LocalSearchInnerPadding provides innerPadding) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isBlurSupported) {
                                        Modifier
                                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                            .haze(state = hazeState)
                                    } else Modifier
                                )
                        ) {
                            Column(modifier = contentModifier) {
                                content()
                            }
                        }
                    }
                } else {
                    val items = filteredItems(searchText.text)
                    Scaffold(
                        modifier = contentModifier,
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    ) { innerPadding2 ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isBlurSupported) {
                                        Modifier
                                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                            .haze(state = hazeState)
                                    } else Modifier
                                )
                        ) {
                            LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = innerPadding.calculateTopPadding(),
                                    bottom = innerPadding2.calculateBottomPadding()
                                )
                            ) {
                                item {
                                    val searchState = LocalSearchState.current
                                    if (searchState != null) {
                                        searchState.searchField()
                                    }
                                }
                                if (itemKey == null) {
                                    items(items) {
                                        itemContent(it)
                                    }
                                } else {
                                    items(items, key = itemKey) {
                                        itemContent(it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SettingsActivity.activeOverlay?.invoke(hazeState)
    }
}
}

// from StreetComplete
/** Static text field for searching */
@Composable
fun StaticSearchField(
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    TextField(
        value = search,
        onValueChange = onSearchChange,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = modifier,
        leadingIcon = { Text("search", fontFamily = materialSymbols, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) },
        placeholder = { Text("Search Settings") },
        singleLine = true,
        colors = colors,
        textStyle = contentTextDirectionStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}
