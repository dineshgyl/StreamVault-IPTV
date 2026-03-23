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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.streamvault.app.device.rememberIsTelevisionDevice
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
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.border
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
import com.streamvault.app.ui.components.shell.VodBrowseOptionsDialog
import com.streamvault.app.ui.components.shell.VodHeroStrip
import com.streamvault.app.ui.components.shell.VodSectionHeader
import com.streamvault.app.ui.screens.vod.HandleVodUserMessage
import com.streamvault.app.ui.screens.vod.ProtectedVodPinDialog
import com.streamvault.app.ui.screens.vod.VodBrowseDefaults

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviesScreen(
    onMovieClick: (Movie) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: MoviesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    HandleVodUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::userMessageShown
    )

    BackHandler(enabled = uiState.selectedCategory != null && !uiState.isReorderMode) {
        viewModel.selectCategory(null)
    }

    ProtectedVodPinDialog(
        visible = showPinDialog,
        error = pinError,
        incorrectPinMessage = context.getString(R.string.movies_incorrect_pin),
        onDismissRequest = {
            showPinDialog = false
            pinError = null
            pendingMovie = null
        },
        onVerified = {
            showPinDialog = false
            pinError = null
            pendingMovie?.let(onMovieClick)
            pendingMovie = null
        },
        onErrorChange = { pinError = it },
        verifyPin = viewModel::verifyPin
    )

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
                selectedFilterType = uiState.selectedLibraryFilterType,
                onSelectedFilterTypeChange = viewModel::setSelectedLibraryFilterType,
                selectedSortBy = uiState.selectedLibrarySortBy,
                onSelectedSortByChange = viewModel::setSelectedLibrarySortBy,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onMovieClick = onMovieClick,
                onProtectedMovieClick = { movie ->
                    pendingMovie = movie
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onShowCategoryOptions = viewModel::showCategoryOptions,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onOpenContinueWatching = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.IN_PROGRESS)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.LIBRARY)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenTopRated = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.TOP_RATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.RATING)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenFresh = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.RECENTLY_UPDATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.RELEASE)
                    viewModel.selectFullLibraryBrowse()
                },
                onLoadMore = viewModel::loadMoreSelectedCategory,
                onDismissReorder = viewModel::exitCategoryReorderMode
            )
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
            groups = uiState.categories.filter { it.isVirtual && it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID },
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
            onHide = if (!category.isVirtual) {
                { viewModel.hideCategory(category) }
            } else null,
            onRename = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onDelete = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
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

@Composable
private fun MoviesVodContent(
    uiState: MoviesUiState,
    selectedFilterType: LibraryFilterType,
    onSelectedFilterTypeChange: (LibraryFilterType) -> Unit,
    selectedSortBy: LibrarySortBy,
    onSelectedSortByChange: (LibrarySortBy) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onProtectedMovieClick: (Movie) -> Unit,
    onShowDialog: (Movie) -> Unit,
    onShowCategoryOptions: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenTopRated: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val favoriteCardWidth = when {
        screenWidth < 700.dp -> 136.dp
        !isTelevisionDevice && screenWidth < 900.dp -> 148.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 152.dp
        else -> 160.dp
    }
    val loadingSectionHeight = when {
        screenWidth < 700.dp -> 220.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 260.dp
        else -> 300.dp
    }
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteMovies = uiState.moviesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshMovies = uiState.libraryLensRows[MovieLibraryLens.FRESH].orEmpty()
    val topRatedMovies = uiState.libraryLensRows[MovieLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroMovie = freshMovies.firstOrNull() ?: topRatedMovies.firstOrNull() ?: favoriteMovies.firstOrNull()
    val categoryOptions = remember(uiState.categoryNames, uiState.categoryCounts) {
        uiState.categoryNames.map { name ->
            val matchedCategory = uiState.providerCategories.firstOrNull { it.name == name }
                ?: uiState.categories.firstOrNull { it.name == name }
                ?: if (name == uiState.favoriteCategoryName) {
                    com.streamvault.domain.model.Category(
                        id = VodBrowseDefaults.FAVORITES_SENTINEL_ID,
                        name = uiState.favoriteCategoryName,
                        type = com.streamvault.domain.model.ContentType.MOVIE,
                        isVirtual = true
                    )
                } else {
                    null
                }
            VodCategoryOption(
                name = name,
                count = uiState.categoryCounts[name] ?: 0,
                onClick = { onSelectCategory(name) },
                onLongClick = matchedCategory?.let { category ->
                    { onShowCategoryOptions(category.name) }
                }
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
                                    onClick = onOpenContinueWatching
                                )
                            )
                        }
                        if (topRatedMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedMovies.size} picks",
                                    onClick = onOpenTopRated
                                )
                            )
                        }
                        if (freshMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_movies),
                                    detail = "${freshMovies.size} picks",
                                    onClick = onOpenFresh
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
                item("favorites_row") {
                    CategoryRow(
                        title = stringResource(R.string.favorites_title),
                        items = favoriteMovies,
                        onSeeAll = { onSelectCategory(uiState.favoriteCategoryName) },
                        keySelector = { it.id }
                    ) { movie ->
                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                        MovieCard(
                            movie = movie,
                            isLocked = isLocked,
                            onClick = {
                                if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie)
                            },
                            onLongClick = { onShowDialog(movie) },
                            modifier = Modifier.width(favoriteCardWidth)
                        )
                    }
                }
            }

            if (freshMovies.isNotEmpty()) {
                item("fresh_row") {
                    CategoryRow(
                        title = stringResource(R.string.library_lens_fresh_movies),
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
                item("top_row") {
                    CategoryRow(
                        title = stringResource(R.string.library_lens_top_rated),
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
                CategoryRow(
                    title = categoryName,
                    items = movies,
                    onSeeAll = { onSelectCategory(categoryName) },
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
    val filteredGridMovies = remember(baseMovies, uiState.isReorderMode, uiState.filteredMovies) {
        if (uiState.isReorderMode) uiState.filteredMovies else baseMovies
    }
    var draggingMovie by remember { mutableStateOf<Movie?>(null) }
    var showBrowseOptions by rememberSaveable(uiState.selectedCategory) { mutableStateOf(false) }
    var showSearchBar by rememberSaveable(uiState.selectedCategory) { mutableStateOf(searchQuery.isNotBlank()) }

    if (showBrowseOptions) {
        VodBrowseOptionsDialog(
            title = stringResource(R.string.nav_movies),
            filterTitle = stringResource(R.string.library_filter_title),
            filterChips = movieFilterChips(),
            selectedFilterKey = selectedFilterType.name,
            onFilterSelected = { key ->
                LibraryFilterType.entries.firstOrNull { it.name == key }?.let(onSelectedFilterTypeChange)
            },
            sortTitle = stringResource(R.string.library_sort_title),
            sortChips = movieSortChips(),
            selectedSortKey = selectedSortBy.name,
            onSortSelected = { key ->
                LibrarySortBy.entries.firstOrNull { it.name == key }?.let(onSelectedSortByChange)
            },
            onDismiss = { showBrowseOptions = false }
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

        if (!uiState.isReorderMode) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "back_home",
                                label = stringResource(R.string.nav_movies),
                                onClick = { onSelectCategory(null) }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.movies_categories_title),
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "search_toggle",
                                label = if (showSearchBar) "Hide Search" else "Search",
                                onClick = { showSearchBar = !showSearchBar }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "browse_options",
                                label = "Filters & Sort",
                                onClick = { showBrowseOptions = true }
                            )
                        )
                        if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                            add(
                                VodActionChip(
                                    key = uiState.fullLibraryCategoryName,
                                    label = stringResource(R.string.library_full_browse_title_movies),
                                    onClick = onSelectFullLibraryBrowse
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }

            if (showSearchBar) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchInput(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.movies_search_placeholder),
                        onSearch = {},
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (uiState.isLoadingSelectedCategory) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(loadingSectionHeight),
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

@Composable
private fun movieLibraryLensLabel(lens: MovieLibraryLens): String =
    when (lens) {
        MovieLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        MovieLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        MovieLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        MovieLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_movies)
    }

private fun movieFilterChips(): List<SelectionChip> {
    return listOf(
        SelectionChip(LibraryFilterType.ALL.name, "All"),
        SelectionChip(LibraryFilterType.FAVORITES.name, "Favorites"),
        SelectionChip(LibraryFilterType.IN_PROGRESS.name, "Resume"),
        SelectionChip(LibraryFilterType.UNWATCHED.name, "Unwatched"),
        SelectionChip(LibraryFilterType.RECENTLY_UPDATED.name, "Recent"),
        SelectionChip(LibraryFilterType.TOP_RATED.name, "Top Rated")
    )
}

private fun movieSortChips(): List<SelectionChip> {
    return LibrarySortBy.entries.map { sort ->
        SelectionChip(
            key = sort.name,
            label = when (sort) {
                LibrarySortBy.LIBRARY -> "Library Order"
                LibrarySortBy.TITLE -> "A-Z"
                LibrarySortBy.RELEASE -> "Newest"
                LibrarySortBy.UPDATED -> "Recently Updated"
                LibrarySortBy.RATING -> "Rating"
                LibrarySortBy.WATCH_COUNT -> "Recent Activity"
            }
        )
    }
}


