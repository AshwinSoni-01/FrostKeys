// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
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
    val setShowSearch: (Boolean) -> Unit
)
val LocalSearchState = staticCompositionLocalOf<SearchState?> { null }

val materialSymbols = FontFamily(Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f))))

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    hideTopSearchBar: Boolean = false,
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        hideTopSearchBar = hideTopSearchBar,
        title = { Text(title) },
        content = {
            if (content != null) content()
            else {
                val hazeState = LocalHazeState.current
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().haze(state = hazeState)) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                        ) {
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
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }
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
    
    val searchState = remember(showSearch, searchText) {
        SearchState(
            showSearch = showSearch,
            searchText = searchText,
            onSearchChange = { searchText = it },
            setShowSearch = ::setShowSearch
        )
    }
    
    val customScrollConnection = remember(scrollBehavior, showSearch) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 10f && scrollBehavior.state.collapsedFraction == 0f && !showSearch) {
                    setShowSearch(true)
                }
                return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
            }
        }
    }
    
    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalSearchState provides searchState
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(customScrollConnection),
            containerColor = scaffoldBg,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                val collapsedFraction = scrollBehavior.state.collapsedFraction
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.hazeChild(state = hazeState, style = HazeStyle(blurRadius = 24.dp * collapsedFraction, tint = topBarBg.copy(alpha = 0.3f * collapsedFraction)))
                ) {
                    Column {
                        LargeTopAppBar(
                            title = { Box(modifier = Modifier.padding(start = 12.dp)) { title() } },
                            scrollBehavior = scrollBehavior,
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        if (showSearch) setShowSearch(false)
                                        else onClickBack()
                                    },
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                ) {
                                    Text("arrow_back", fontFamily = materialSymbols, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            },
                            actions = {
                                if (icon == null)
                                    IconButton(onClick = { setShowSearch(!showSearch) }) { Text("search", fontFamily = materialSymbols, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) }
                                else
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
                        if (!hideTopSearchBar) {
                            ExpandableSearchField(
                                expanded = showSearch,
                                onDismiss = { setShowSearch(false) },
                            search = searchText,
                            onSearchChange = { searchText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        }
                    }
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                val contentModifier = Modifier.fillMaxSize()

                if (searchText.text.isBlank() && content != null) {
                    CompositionLocalProvider(LocalSearchInnerPadding provides innerPadding) {
                        Column(modifier = contentModifier) {
                            content()
                        }
                    }
                } else {
                    val items = filteredItems(searchText.text)
                    Scaffold(
                        modifier = contentModifier,
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    ) { innerPadding2 ->
                        Box(modifier = Modifier.fillMaxSize().haze(state = hazeState)) {
                            LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = innerPadding.calculateTopPadding(),
                                    bottom = innerPadding2.calculateBottomPadding()
                                )
                            ) {
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
    }
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = modifier.focusRequester(focusRequester),
            leadingIcon = { Text("search", fontFamily = materialSymbols, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) },
            trailingIcon = { IconButton(onClick = {
                if (search.text.isBlank()) onDismiss()
                else onSearchChange(TextFieldValue())
            }) { Text("close", fontFamily = materialSymbols, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) } },
            singleLine = true,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}
