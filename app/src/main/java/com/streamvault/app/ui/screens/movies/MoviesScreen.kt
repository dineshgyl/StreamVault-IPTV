package com.streamvault.app.ui.screens.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.streamvault.app.ui.components.SearchInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.ContinueWatchingRow
import com.streamvault.app.ui.components.MovieCard
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.SavedCategoryContextCard
import com.streamvault.app.ui.components.SavedCategoryShortcut
import com.streamvault.app.ui.components.SavedCategoryShortcutsRow
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.Movie
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.border
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.dialogs.DeleteGroupDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.streamvault.app.ui.components.shell.BrowseSearchLaunchCard
import com.streamvault.app.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import androidx.tv.material3.Border
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.shell.VodActionChip
import com.streamvault.app.ui.components.shell.VodActionChipRow
import com.streamvault.app.ui.components.shell.VodCategoryOption
import com.streamvault.app.ui.components.shell.VodCategoryPickerDialog
import com.streamvault.app.ui.components.shell.VodHeroStrip
import com.streamvault.app.ui.components.shell.VodSectionHeader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviesScreen(
    onMovieClick: (Movie) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: MoviesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedLibraryLens by rememberSaveable { mutableStateOf(MovieLibraryLens.FAVORITES.name) }
    var selectedFacet by rememberSaveable { mutableStateOf(MovieLibraryFacet.ALL.name) }
    var selectedSort by rememberSaveable { mutableStateOf(MovieLibrarySort.LIBRARY.name) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    BackHandler(enabled = uiState.selectedCategory != null && !uiState.isReorderMode) {
        viewModel.selectCategory(null)
    }

    if (showPinDialog) {
        com.streamvault.app.ui.components.dialogs.PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingMovie = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        showPinDialog = false
                        pinError = null
                        pendingMovie?.let { onMovieClick(it) }
                        pendingMovie = null
                    } else {
                        pinError = context.getString(R.string.movies_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_movies),
            subtitle = null,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
        if (uiState.isReorderMode && uiState.reorderCategory != null) {
            ReorderTopBar(
                categoryName = uiState.reorderCategory!!.name,
                onSave = { viewModel.saveReorder() },
                onCancel = { viewModel.exitCategoryReorderMode() },
                subtitle = stringResource(R.string.movies_reorder_subtitle)
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.movies_loading),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (uiState.errorMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.home_error_load_failed),
                    subtitle = uiState.errorMessage ?: ""
                )
            }
        } else if (uiState.moviesByCategory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.movies_no_found),
                    subtitle = stringResource(R.string.movies_no_found_subtitle)
                )
            }
        } else {
            MoviesVodContent(
                uiState = uiState,
                selectedFacet = selectedFacet,
                onSelectedFacetChange = { selectedFacet = it },
                selectedSort = selectedSort,
                onSelectedSortChange = { selectedSort = it },
                onNavigate = onNavigate,
                onMovieClick = onMovieClick,
                onProtectedMovieClick = { movie ->
                    pendingMovie = movie
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onLoadMore = viewModel::loadMoreSelectedCategory,
                onDismissReorder = viewModel::exitCategoryReorderMode
            )
            return@AppScreenScaffold
            Row(modifier = Modifier.fillMaxSize()) {
                val focusManager = LocalFocusManager.current
                val categorySearchFocusRequester = remember { FocusRequester() }
                val categoryFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
                var lastFocusedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
                var shouldRestoreCategoryFocus by remember { mutableStateOf(false) }
                var preferCategoryRestore by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(
                    uiState.selectedCategoryForOptions,
                    uiState.showRenameGroupDialog,
                    uiState.showDeleteGroupDialog
                ) {
                    val modalClosed =
                        uiState.selectedCategoryForOptions == null &&
                            !uiState.showRenameGroupDialog &&
                            !uiState.showDeleteGroupDialog
                    if (modalClosed && preferCategoryRestore && lastFocusedCategoryName != null) {
                        shouldRestoreCategoryFocus = true
                        preferCategoryRestore = false
                    }
                }

                LaunchedEffect(shouldRestoreCategoryFocus, uiState.categoryNames) {
                    if (!shouldRestoreCategoryFocus) return@LaunchedEffect
                    kotlinx.coroutines.delay(80)
                    val categoryName = lastFocusedCategoryName
                    if (categoryName != null) {
                        runCatching {
                            categoryFocusRequesters[categoryName]?.requestFocus()
                        }
                    }
                    shouldRestoreCategoryFocus = false
                }

                Column(
                    modifier = Modifier
                        .width(188.dp)
                        .fillMaxHeight()
                        .background(SurfaceElevated.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
                        .padding(top = 8.dp)
                ) {
                    // Sticky Header
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = stringResource(R.string.movies_categories_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = Primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        SearchInput(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = stringResource(R.string.movies_search_placeholder),
                            focusRequester = categorySearchFocusRequester,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                    item {
                        val isAllSelected = uiState.selectedCategory == null || uiState.selectedCategory == uiState.fullLibraryCategoryName
                        Surface(
                            onClick = { viewModel.selectCategory(null) },
                            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isAllSelected) Primary.copy(alpha = 0.15f) else Color.Transparent,
                                focusedContainerColor = Primary.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                            categorySearchFocusRequester.requestFocus()
                                            true
                                        } else false
                                    } else false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.movies_all_categories),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isAllSelected) Primary else OnSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${uiState.libraryCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                    items(uiState.categoryNames.size) { index ->
                        val categoryName = uiState.categoryNames[index]
                        val isSelected = uiState.selectedCategory == categoryName
                        val count = uiState.categoryCounts[categoryName] ?: 0
                        Surface(
                            onClick = { viewModel.selectCategory(categoryName) },
                            onLongClick = {
                                preferCategoryRestore = true
                                viewModel.showCategoryOptions(categoryName)
                            },
                            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent,
                                focusedContainerColor = Primary.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .focusRequester(categoryFocusRequesters.getOrPut(categoryName) { FocusRequester() })
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        lastFocusedCategoryName = categoryName
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                            categorySearchFocusRequester.requestFocus()
                                            true
                                        } else false
                                    } else false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = categoryName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) Primary else OnSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$count",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }
                }

                val savedShortcuts = remember(
                    uiState.favoriteCategoryName,
                    uiState.categories,
                    uiState.moviesByCategory
                ) {
                    buildList {
                        val favoriteCount = uiState.categoryCounts[uiState.favoriteCategoryName] ?: 0
                        if (favoriteCount > 0) {
                            add(
                                SavedCategoryShortcut(
                                    name = uiState.favoriteCategoryName,
                                    count = favoriteCount
                                )
                            )
                        }
                        uiState.categories
                            .asSequence()
                            .filterNot { it.name == uiState.favoriteCategoryName }
                            .map { category ->
                                SavedCategoryShortcut(
                                    name = category.name,
                                    count = uiState.categoryCounts[category.name] ?: 0
                                )
                            }
                            .filter { it.count > 0 }
                            .sortedByDescending { it.count }
                            .forEach(::add)
                    }
                }
                val selectedSavedCategory = remember(
                    uiState.selectedCategory,
                    uiState.favoriteCategoryName,
                    uiState.categories
                ) {
                    when (uiState.selectedCategory) {
                        null -> null
                        uiState.favoriteCategoryName -> com.streamvault.domain.model.Category(
                            id = -999L,
                            name = uiState.favoriteCategoryName,
                            type = com.streamvault.domain.model.ContentType.MOVIE,
                            isVirtual = true
                        )
                        else -> uiState.categories.firstOrNull { it.name == uiState.selectedCategory }
                    }
                }

                // Main content
                if (uiState.selectedCategory == null) {
                    val activeLibraryLens = remember(selectedLibraryLens, uiState.libraryLensRows) {
                        MovieLibraryLens.entries.firstOrNull { it.name == selectedLibraryLens && uiState.libraryLensRows.containsKey(it) }
                            ?: uiState.libraryLensRows.keys.firstOrNull()
                    }
                    // Netflix-style rows (All categories view)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(
                            start = LocalSpacing.current.safeHoriz, 
                            end = LocalSpacing.current.safeHoriz, 
                            bottom = LocalSpacing.current.safeBottom
                        )
                    ) {
                        item {
                            BrowseSearchLaunchCard(
                                title = stringResource(R.string.movies_search_launch_title),
                                subtitle = stringResource(R.string.movies_search_launch_subtitle),
                                onClick = { onNavigate(Routes.SEARCH) },
                                modifier = Modifier.padding(vertical = LocalSpacing.current.md)
                            )
                        }

                        item(key = "saved_shortcuts") {
                            SavedCategoryShortcutsRow(
                                title = stringResource(R.string.library_saved_title),
                                subtitle = stringResource(R.string.library_saved_subtitle),
                                emptyHint = stringResource(R.string.movies_saved_empty_hint),
                                shortcuts = savedShortcuts,
                                managementHint = stringResource(R.string.library_saved_manage_hint),
                                primaryShortcutLabel = stringResource(R.string.library_browse_all),
                                isPrimaryShortcutSelected = uiState.selectedCategory == null,
                                onPrimaryShortcutClick = { viewModel.selectCategory(null) },
                                selectedShortcutName = uiState.selectedCategory,
                                onShortcutLongClick = viewModel::showCategoryOptions,
                                onShortcutClick = viewModel::selectCategory
                            )
                        }

                        if (activeLibraryLens != null) {
                            item(key = "library_lens_chips") {
                                SelectionChipRow(
                                    title = stringResource(R.string.library_lens_title),
                                    subtitle = stringResource(R.string.movies_library_lens_subtitle),
                                    chips = uiState.libraryLensRows.keys.map { lens ->
                                        SelectionChip(
                                            key = lens.name,
                                            label = movieLibraryLensLabel(lens),
                                            supportingText = stringResource(
                                                R.string.library_saved_items_count,
                                                uiState.libraryLensRows[lens]?.size ?: 0
                                            )
                                        )
                                    },
                                    selectedKey = activeLibraryLens.name,
                                    onChipSelected = { selectedLibraryLens = it },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                )
                            }

                            item(key = "library_lens_row") {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(
                                        onClick = viewModel::selectFullLibraryBrowse,
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color.White.copy(alpha = 0.06f),
                                            focusedContainerColor = Primary
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                            Text(
                                                text = stringResource(R.string.library_full_browse_title_movies),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = Color.White
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    CategoryRow(
                                        title = movieLibraryLensLabel(activeLibraryLens),
                                        items = uiState.libraryLensRows[activeLibraryLens].orEmpty(),
                                        onSeeAll = if (activeLibraryLens == MovieLibraryLens.FAVORITES) {
                                            { viewModel.selectCategory(uiState.favoriteCategoryName) }
                                        } else {
                                            null
                                        },
                                        keySelector = { it.id }
                                    ) { movie ->
                                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                                        MovieCard(
                                            movie = movie,
                                            isLocked = isLocked,
                                            onClick = {
                                                if (isLocked) {
                                                    pendingMovie = movie
                                                    showPinDialog = true
                                                } else {
                                                    onMovieClick(movie)
                                                }
                                            },
                                            onLongClick = { viewModel.onShowDialog(movie) }
                                        )
                                    }
                                }
                            }
                        }

                        // Continue Watching row (shown first, only if non-empty)
                        item(key = "continue_watching") {
                            ContinueWatchingRow(
                                items = uiState.continueWatching,
                                onItemClick = { history -> onMovieClick(
                                    com.streamvault.domain.model.Movie(
                                        id = history.contentId,
                                        name = history.title,
                                        posterUrl = history.posterUrl,
                                        streamUrl = history.streamUrl,
                                        providerId = history.providerId
                                    )
                                )}
                            )
                        }
                        items(
                            items = uiState.moviesByCategory.entries.toList(),
                            key = { it.key }
                        ) { (categoryName, movies) ->
                            CategoryRow(
                                title = categoryName,
                                items = movies,
                                onSeeAll = { viewModel.selectCategory(categoryName) },
                                keySelector = { it.id }
                            ) { movie ->
                                val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                                MovieCard(
                                    movie = movie,
                                    isLocked = isLocked,
                                    onClick = {
                                        if (isLocked) {
                                            pendingMovie = movie
                                            showPinDialog = true
                                        } else {
                                            onMovieClick(movie)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.onShowDialog(movie)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Filtered grid for selected category
                    val baseMovies = if (uiState.searchQuery.isBlank()) {
                        uiState.selectedCategoryItems
                    } else {
                        uiState.moviesByCategory[uiState.selectedCategory] ?: emptyList()
                    }
                    val activeMovies = if (uiState.isReorderMode) uiState.filteredMovies else baseMovies
                    val resumeMovieIds = remember(uiState.continueWatching) {
                        uiState.continueWatching.map { it.contentId }.toSet()
                    }
                    val activeFacet = remember(selectedFacet) {
                        MovieLibraryFacet.entries.firstOrNull { it.name == selectedFacet } ?: MovieLibraryFacet.ALL
                    }
                    val activeSort = remember(selectedSort) {
                        MovieLibrarySort.entries.firstOrNull { it.name == selectedSort } ?: MovieLibrarySort.LIBRARY
                    }
                    val filteredGridMovies = remember(activeMovies, activeFacet, activeSort, resumeMovieIds, uiState.isReorderMode) {
                        if (uiState.isReorderMode) {
                            activeMovies
                        } else {
                            applyMovieFacetAndSort(
                                items = activeMovies,
                                facet = activeFacet,
                                sort = activeSort,
                                resumeMovieIds = resumeMovieIds
                            )
                        }
                    }
                    val showSelectedCategoryControls = !uiState.isReorderMode &&
                        (selectedSavedCategory != null || activeMovies.size > 8)
                    
                    var draggingMovie by remember { mutableStateOf<Movie?>(null) }
                    
                    LaunchedEffect(uiState.isReorderMode) {
                        if (!uiState.isReorderMode) {
                            draggingMovie = null
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        if (selectedSavedCategory != null) {
                            SavedCategoryShortcutsRow(
                                title = stringResource(R.string.library_saved_title),
                                subtitle = stringResource(R.string.library_saved_subtitle),
                                emptyHint = stringResource(R.string.movies_saved_empty_hint),
                                shortcuts = savedShortcuts,
                                managementHint = stringResource(R.string.library_saved_manage_hint),
                                primaryShortcutLabel = stringResource(R.string.library_browse_all),
                                isPrimaryShortcutSelected = uiState.selectedCategory == null,
                                onPrimaryShortcutClick = { viewModel.selectCategory(null) },
                                selectedShortcutName = uiState.selectedCategory,
                                onShortcutLongClick = viewModel::showCategoryOptions,
                                onShortcutClick = viewModel::selectCategory
                            )

                            SavedCategoryContextCard(
                                categoryName = selectedSavedCategory.name,
                                itemCount = uiState.categoryCounts[selectedSavedCategory.name] ?: filteredGridMovies.size,
                                onManageClick = { viewModel.showCategoryOptions(selectedSavedCategory.name) },
                                onBrowseAllClick = { viewModel.selectCategory(null) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }

                        if (showSelectedCategoryControls) {
                            SelectionChipRow(
                                title = stringResource(R.string.library_filter_title),
                                chips = buildMovieFacetChips(
                                    items = activeMovies,
                                    resumeMovieIds = resumeMovieIds
                                ),
                                selectedKey = activeFacet.name,
                                onChipSelected = { selectedFacet = it },
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )

                            SelectionChipRow(
                                title = stringResource(R.string.library_sort_title),
                                chips = MovieLibrarySort.entries.map { sort ->
                                    SelectionChip(
                                        key = sort.name,
                                        label = when (sort) {
                                            MovieLibrarySort.LIBRARY -> stringResource(R.string.library_sort_library)
                                            MovieLibrarySort.TITLE -> stringResource(R.string.library_sort_az)
                                            MovieLibrarySort.RELEASE -> stringResource(R.string.library_sort_release)
                                            MovieLibrarySort.RATING -> stringResource(R.string.library_sort_rating)
                                        }
                                    )
                                },
                                selectedKey = activeSort.name,
                                onChipSelected = { selectedSort = it },
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            )
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 138.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .onPreviewKeyEvent { event ->
                                    if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                            if (draggingMovie != null) {
                                                draggingMovie = null
                                                true
                                            } else {
                                                viewModel.exitCategoryReorderMode()
                                                true
                                            }
                                        } else false
                                    } else false
                                },
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                    Text(
                                        text = when (uiState.selectedCategory) {
                                            uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_movies)
                                            else -> uiState.selectedCategory ?: ""
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    if (!uiState.isReorderMode && uiState.selectedCategoryTotalCount > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.library_loaded_results,
                                                uiState.selectedCategoryLoadedCount,
                                                uiState.selectedCategoryTotalCount
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                            }

                            if (filteredGridMovies.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = stringResource(R.string.library_filter_empty),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = OnSurfaceDim,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }
                            }
                            
                            gridItems(
                                items = filteredGridMovies,
                                key = { it.id }
                            ) { movie ->
                                val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                                val isDraggingThis = draggingMovie == movie

                                MovieCard(
                                    movie = movie,
                                    isLocked = isLocked,
                                    isReorderMode = uiState.isReorderMode,
                                    isDragging = isDraggingThis,
                                    onClick = {
                                        if (uiState.isReorderMode) {
                                            draggingMovie = if (isDraggingThis) null else movie
                                        } else if (isLocked) {
                                            pendingMovie = movie
                                            showPinDialog = true
                                        } else {
                                            onMovieClick(movie)
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isReorderMode) {
                                            viewModel.onShowDialog(movie)
                                        }
                                    },
                                    modifier = Modifier.onPreviewKeyEvent { event ->
                                        if (uiState.isReorderMode && isDraggingThis && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                            when (event.nativeKeyEvent.keyCode) {
                                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                    viewModel.moveItemUp(movie)
                                                    true
                                                }
                                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    viewModel.moveItemDown(movie)
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                )
                            }

                            if (!uiState.isReorderMode && uiState.canLoadMoreSelectedCategory) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    LoadMoreCard(
                                        label = stringResource(
                                            R.string.library_load_more,
                                            uiState.selectedCategoryLoadedCount,
                                            uiState.selectedCategoryTotalCount
                                        ),
                                        onClick = viewModel::loadMoreSelectedCategory,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (uiState.showDialog && uiState.selectedMovieForDialog != null) {
        val movie = uiState.selectedMovieForDialog!!
        com.streamvault.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = movie.name,
            groups = uiState.categories.filter { it.isVirtual && it.id != -999L },
            isFavorite = movie.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (movie.isFavorite) viewModel.removeFavorite(movie) else viewModel.addFavorite(movie)
            },
            onAddToGroup = { group -> viewModel.addToGroup(movie, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(movie, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) }
        )
    }

    if (uiState.selectedCategoryForOptions != null) {
        val category = uiState.selectedCategoryForOptions!!
        com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onRename = if (category.isVirtual && category.id != -999L) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onDelete = if (category.isVirtual && category.id != -999L) {
                {
                    viewModel.requestDeleteGroup(category)
                }
            } else null,
            onReorderChannels = if (category.isVirtual) {
                { viewModel.enterCategoryReorderMode(category) }
            } else null
        )
    }

    if (uiState.showRenameGroupDialog && uiState.groupToRename != null) {
        RenameGroupDialog(
            initialName = uiState.groupToRename!!.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        DeleteGroupDialog(
            groupName = uiState.groupToDelete!!.name,
            onDismissRequest = { viewModel.cancelDeleteGroup() },
            onConfirmDelete = { viewModel.confirmDeleteGroup() }
        )
    }
}

private enum class MovieLibraryFacet {
    ALL,
    FAVORITES,
    RESUME,
    UNWATCHED,
    TOP_RATED
}

@Composable
private fun MoviesVodContent(
    uiState: MoviesUiState,
    selectedFacet: String,
    onSelectedFacetChange: (String) -> Unit,
    selectedSort: String,
    onSelectedSortChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onProtectedMovieClick: (Movie) -> Unit,
    onShowDialog: (Movie) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteMovies = uiState.moviesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshMovies = uiState.libraryLensRows[MovieLibraryLens.FRESH].orEmpty()
    val topRatedMovies = uiState.libraryLensRows[MovieLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroMovie = freshMovies.firstOrNull() ?: topRatedMovies.firstOrNull() ?: favoriteMovies.firstOrNull()
    val categoryOptions = remember(uiState.categoryNames, uiState.categoryCounts) {
        uiState.categoryNames.map { name ->
            VodCategoryOption(
                name = name,
                count = uiState.categoryCounts[name] ?: 0,
                onClick = { onSelectCategory(name) }
            )
        }
    }

    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = categoryOptions,
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (uiState.selectedCategory == null) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            if (heroMovie != null) {
                item("hero") {
                    VodHeroStrip(
                        title = heroMovie.name,
                        subtitle = heroMovie.plot?.takeIf { it.isNotBlank() }
                            ?: heroMovie.year
                            ?: stringResource(R.string.movies_library_lens_subtitle),
                        actionLabel = stringResource(R.string.player_resume).substringBefore(" "),
                        onClick = {
                            val isLocked = (heroMovie.isAdult || heroMovie.isUserProtected) && uiState.parentalControlLevel == 1
                            if (isLocked) onProtectedMovieClick(heroMovie) else onMovieClick(heroMovie)
                        },
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    )
                }
            }

            item("actions") {
                VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "browse_all",
                                label = stringResource(R.string.library_full_browse_title_movies),
                                detail = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                onClick = onSelectFullLibraryBrowse
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.movies_categories_title),
                                detail = "${uiState.categoryNames.size} groups",
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        if (favoriteMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "favorites",
                                    label = stringResource(R.string.favorites_title),
                                    detail = stringResource(R.string.library_saved_items_count, favoriteMovies.size),
                                    onClick = { onSelectCategory(uiState.favoriteCategoryName) }
                                )
                            )
                        }
                        if (continueWatching.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "resume",
                                    label = stringResource(R.string.library_lens_continue),
                                    detail = "${continueWatching.size} items",
                                    onClick = { }
                                )
                            )
                        }
                        if (topRatedMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedMovies.size} picks",
                                    onClick = { }
                                )
                            )
                        }
                        if (freshMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_movies),
                                    detail = "${freshMovies.size} picks",
                                    onClick = { }
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            if (continueWatching.isNotEmpty()) {
                item("continue_watching") {
                    ContinueWatchingRow(
                        items = continueWatching,
                        onItemClick = { history ->
                            onMovieClick(
                                Movie(
                                    id = history.contentId,
                                    name = history.title,
                                    posterUrl = history.posterUrl,
                                    streamUrl = history.streamUrl,
                                    providerId = history.providerId
                                )
                            )
                        }
                    )
                }
            }

            if (favoriteMovies.isNotEmpty()) {
                item("favorites_header") {
                    VodSectionHeader(
                        title = stringResource(R.string.favorites_title),
                        onSeeAll = { onSelectCategory(uiState.favoriteCategoryName) }
                    )
                }
                item("favorites_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(favoriteMovies, key = { it.id }) { movie ->
                            val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                            MovieCard(
                                movie = movie,
                                isLocked = isLocked,
                                onClick = {
                                    if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie)
                                },
                                onLongClick = { onShowDialog(movie) },
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }

            if (freshMovies.isNotEmpty()) {
                item("fresh_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_fresh_movies))
                }
                item("fresh_row") {
                    CategoryRow(
                        title = "",
                        items = freshMovies,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { movie ->
                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                        MovieCard(
                            movie = movie,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                            onLongClick = { onShowDialog(movie) }
                        )
                    }
                }
            }

            if (topRatedMovies.isNotEmpty()) {
                item("top_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_top_rated))
                }
                item("top_row") {
                    CategoryRow(
                        title = "",
                        items = topRatedMovies,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { movie ->
                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                        MovieCard(
                            movie = movie,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                            onLongClick = { onShowDialog(movie) }
                        )
                    }
                }
            }

            items(
                items = uiState.moviesByCategory.entries.filter { (name, items) ->
                    name != uiState.favoriteCategoryName && items.isNotEmpty()
                }.take(8),
                key = { it.key }
            ) { (categoryName, movies) ->
                VodSectionHeader(
                    title = categoryName,
                    onSeeAll = { onSelectCategory(categoryName) }
                )
                CategoryRow(
                    title = "",
                    items = movies,
                    onSeeAll = null,
                    keySelector = { it.id }
                ) { movie ->
                    val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                    MovieCard(
                        movie = movie,
                        isLocked = isLocked,
                        onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                        onLongClick = { onShowDialog(movie) }
                    )
                }
            }
        }
        return
    }

    val baseMovies = uiState.selectedCategoryItems
    val resumeMovieIds = remember(uiState.continueWatching) { uiState.continueWatching.map { it.contentId }.toSet() }
    val activeFacet = remember(selectedFacet) {
        MovieLibraryFacet.entries.firstOrNull { it.name == selectedFacet } ?: MovieLibraryFacet.ALL
    }
    val activeSort = remember(selectedSort) {
        MovieLibrarySort.entries.firstOrNull { it.name == selectedSort } ?: MovieLibrarySort.LIBRARY
    }
    val filteredGridMovies = remember(baseMovies, activeFacet, activeSort, resumeMovieIds, uiState.isReorderMode, uiState.filteredMovies) {
        val source = if (uiState.isReorderMode) uiState.filteredMovies else baseMovies
        if (uiState.isReorderMode) source else applyMovieFacetAndSort(
            items = source,
            facet = activeFacet,
            sort = activeSort,
            resumeMovieIds = resumeMovieIds
        )
    }
    var draggingMovie by remember { mutableStateOf<Movie?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VodActionChipRow(
            actions = buildList {
                add(
                    VodActionChip(
                        key = "back_home",
                        label = stringResource(R.string.nav_movies),
                        detail = stringResource(R.string.category_see_all),
                        onClick = { onSelectCategory(null) }
                    )
                )
                add(
                    VodActionChip(
                        key = uiState.fullLibraryCategoryName,
                        label = stringResource(R.string.library_full_browse_title_movies),
                        detail = "${uiState.libraryCount} titles",
                        onClick = onSelectFullLibraryBrowse
                    )
                )
                add(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.movies_categories_title),
                        detail = "${uiState.categoryNames.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
                if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                    add(
                        VodActionChip(
                            key = uiState.selectedCategory ?: "",
                            label = uiState.selectedCategory ?: stringResource(R.string.nav_movies),
                            detail = "${uiState.selectedCategoryTotalCount} titles",
                            onClick = { }
                        )
                    )
                }
            },
            selectedKey = uiState.selectedCategory,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (!uiState.isReorderMode) {
            SelectionChipRow(
                title = stringResource(R.string.library_filter_title),
                chips = buildMovieFacetChips(baseMovies, resumeMovieIds),
                selectedKey = activeFacet.name,
                onChipSelected = onSelectedFacetChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
            SelectionChipRow(
                title = stringResource(R.string.library_sort_title),
                chips = MovieLibrarySort.entries.map { sort ->
                    SelectionChip(
                        key = sort.name,
                        label = when (sort) {
                            MovieLibrarySort.LIBRARY -> stringResource(R.string.library_sort_library)
                            MovieLibrarySort.TITLE -> stringResource(R.string.library_sort_az)
                            MovieLibrarySort.RELEASE -> stringResource(R.string.library_sort_release)
                            MovieLibrarySort.RATING -> stringResource(R.string.library_sort_rating)
                        }
                    )
                },
                selectedKey = activeSort.name,
                onChipSelected = onSelectedSortChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                            draggingMovie = null
                            onDismissReorder()
                            true
                        } else false
                    } else false
                },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VodSectionHeader(
                    title = when (uiState.selectedCategory) {
                        uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_movies)
                        else -> uiState.selectedCategory ?: stringResource(R.string.nav_movies)
                    }
                )
            }

            if (uiState.isLoadingSelectedCategory) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = stringResource(R.string.movies_loading),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                gridItems(filteredGridMovies, key = { it.id }) { movie ->
                    val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                    val isDraggingThis = draggingMovie == movie
                    MovieCard(
                        movie = movie,
                        isLocked = isLocked,
                        isReorderMode = uiState.isReorderMode,
                        isDragging = isDraggingThis,
                        onClick = {
                            if (uiState.isReorderMode) {
                                draggingMovie = if (isDraggingThis) null else movie
                            } else if (isLocked) {
                                onProtectedMovieClick(movie)
                            } else {
                                onMovieClick(movie)
                            }
                        },
                        onLongClick = {
                            if (!uiState.isReorderMode) onShowDialog(movie)
                        }
                    )
                }

                if (!uiState.isReorderMode && uiState.canLoadMoreSelectedCategory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreCard(
                            label = stringResource(
                                R.string.library_load_more,
                                uiState.selectedCategoryLoadedCount,
                                uiState.selectedCategoryTotalCount
                            ),
                            onClick = onLoadMore,
                            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class MovieLibrarySort {
    LIBRARY,
    TITLE,
    RELEASE,
    RATING
}

@Composable
private fun movieLibraryLensLabel(lens: MovieLibraryLens): String =
    when (lens) {
        MovieLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        MovieLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        MovieLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        MovieLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_movies)
    }

private fun buildMovieFacetChips(
    items: List<Movie>,
    resumeMovieIds: Set<Long>
): List<SelectionChip> {
    val favoriteCount = items.count { it.isFavorite }
    val resumeCount = items.count { it.id in resumeMovieIds || it.watchProgress > 0L }
    val unwatchedCount = items.count { it.watchProgress <= 0L && it.id !in resumeMovieIds }
    val topRatedCount = items.count { it.rating > 0f }
    return listOf(
        SelectionChip(MovieLibraryFacet.ALL.name, "All", "${items.size} visible"),
        SelectionChip(MovieLibraryFacet.FAVORITES.name, "Favorites", "$favoriteCount saved"),
        SelectionChip(MovieLibraryFacet.RESUME.name, "Resume", "$resumeCount in progress"),
        SelectionChip(MovieLibraryFacet.UNWATCHED.name, "Unwatched", "$unwatchedCount not started"),
        SelectionChip(MovieLibraryFacet.TOP_RATED.name, "Top Rated", "$topRatedCount rated")
    )
}

private fun applyMovieFacetAndSort(
    items: List<Movie>,
    facet: MovieLibraryFacet,
    sort: MovieLibrarySort,
    resumeMovieIds: Set<Long>
): List<Movie> {
    val filtered = when (facet) {
        MovieLibraryFacet.ALL -> items
        MovieLibraryFacet.FAVORITES -> items.filter { it.isFavorite }
        MovieLibraryFacet.RESUME -> items.filter { it.id in resumeMovieIds || it.watchProgress > 0L }
        MovieLibraryFacet.UNWATCHED -> items.filter { it.watchProgress <= 0L && it.id !in resumeMovieIds }
        MovieLibraryFacet.TOP_RATED -> items.filter { it.rating > 0f }
    }

    return when (sort) {
        MovieLibrarySort.LIBRARY -> filtered
        MovieLibrarySort.TITLE -> filtered.sortedBy { it.name.lowercase() }
        MovieLibrarySort.RELEASE -> filtered.sortedByDescending(::movieReleaseScore)
        MovieLibrarySort.RATING -> filtered.sortedByDescending { it.rating }
    }
}

private fun movieReleaseScore(movie: Movie): Long {
    return movie.releaseDate
        ?.filter { it.isDigit() }
        ?.take(8)
        ?.toLongOrNull()
        ?: movie.year?.toLongOrNull()
        ?: 0L
}


