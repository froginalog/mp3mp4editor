package com.froginalog.mp3mp4editor.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.froginalog.mp3mp4editor.media.MediaStoreWriter
import com.froginalog.mp3mp4editor.media.OutputEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val loading: Boolean = true,
    val entries: List<OutputEntry> = emptyList(),
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val entries = withContext(Dispatchers.IO) {
                MediaStoreWriter.listOutputs(getApplication())
            }
            _state.value = LibraryUiState(loading = false, entries = entries)
        }
    }

    fun delete(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { MediaStoreWriter.delete(getApplication(), uri) }
            refresh()
        }
    }
}
