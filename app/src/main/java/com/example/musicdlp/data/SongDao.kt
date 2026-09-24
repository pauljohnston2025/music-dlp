package com.example.musicdlp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun getLikedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    suspend fun getLikedSongsList(): List<Song>

    @Query("SELECT * FROM songs WHERE isDisliked = 1 ORDER BY dislikedAt DESC")
    fun getDislikedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isDisliked = 1 ORDER BY dislikedAt DESC")
    suspend fun getDislikedSongsList(): List<Song>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: String): Song?

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<Song>)

    @Query("DELETE FROM songs")
    suspend fun clearAllSongs()
}
