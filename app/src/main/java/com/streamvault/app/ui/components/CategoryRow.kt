package com.streamvault.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.shell.AppSectionHeader
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight

// ── Netflix-style horizontal category row ─────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> CategoryRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    keySelector: ((T) -> Any)? = null,
    contentTypeSelector: ((T) -> Any?)? = null,
    itemContent: @Composable (T) -> Unit
) {
    val resolvedContentTypeSelector: (T) -> Any? = contentTypeSelector ?: { null }

    Column(modifier = modifier.fillMaxWidth()) {
        if (onSeeAll != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSectionHeader(title = title)
                Surface(
                    onClick = onSeeAll,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = Primary,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Text(
                        text = stringResource(R.string.category_see_all),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        } else {
            AppSectionHeader(
                title = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
            )
        }

        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = keySelector,  // null = index-based keys (safe default)
                contentType = resolvedContentTypeSelector
            ) { item ->
                itemContent(item)
            }
        }
    }
}
