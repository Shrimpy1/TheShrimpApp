package com.example.theshrimpapp.features.imageoptimizer.ui

import android.content.ContentResolver
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ImageOptimizerViewModel @Inject constructor(
    private val contentResolver: ContentResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageOptimizerUiState())
    val uiState: StateFlow<ImageOptimizerUiState> = _uiState.asStateFlow()

    fun updateMaxDimension(dimension: Int) {
        _uiState.update { it.copy(maxDimension = dimension) }
    }

    fun updateOutputFormat(format: String) {
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun updateQuality(quality: Int) {
        _uiState.update { it.copy(quality = quality) }
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
