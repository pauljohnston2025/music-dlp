package com.example.musicdlp.data

object PlaybackStateHolder {
    @Volatile var canGoPrevious: Boolean = false
    @Volatile var canGoNext: Boolean = false
}
