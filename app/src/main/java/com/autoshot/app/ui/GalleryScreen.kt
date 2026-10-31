package com.autoshot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.autoshot.app.data.db.MediaEntity
import java.io.File

/**
 * 唯一的用户界面：本机相册照片三列墙。
 * 不显示来源标记、状态角标、筛选、计数或任何文字。
 */
@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    val pagedItems = vm.items.collectAsLazyPagingItems()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        items(count = pagedItems.itemCount) { index ->
            val entity = pagedItems[index]
            if (entity != null) {
                PhotoCell(entity)
            }
        }
    }
}

@Composable
private fun PhotoCell(entity: MediaEntity) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val model: Any? = entity.thumbPath?.let { File(it) }?.takeIf { it.exists() } ?: entity.uri
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
