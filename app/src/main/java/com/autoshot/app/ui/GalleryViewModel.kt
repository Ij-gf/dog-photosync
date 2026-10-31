package com.autoshot.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.autoshot.app.data.db.MediaEntity
import com.autoshot.app.di.ServiceLocator
import kotlinx.coroutines.flow.Flow

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    /** 只展示相册来源的照片 */
    val items: Flow<PagingData<MediaEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = false,
        )
    ) {
        ServiceLocator.mediaDao.albumPagingSource()
    }.flow.cachedIn(viewModelScope)
}
