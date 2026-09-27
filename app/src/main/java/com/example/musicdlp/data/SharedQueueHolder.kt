package com.example.musicdlp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SharedQueueHolder {
    private val _activeQueue = MutableStateFlow<List<Song>>(emptyList())
    val activeQueue: StateFlow<List<Song>> = _activeQueue

    fun setQueue(queue: List<Song>) {
        _activeQueue.value = queue
    }

    fun updateQueue(transform: (List<Song>) -> List<Song>) {
        _activeQueue.value = transform(_activeQueue.value)
    }

    fun getQueue(): List<Song> = _activeQueue.value
}
