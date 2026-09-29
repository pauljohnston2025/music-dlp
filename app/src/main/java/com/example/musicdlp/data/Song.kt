package com.example.musicdlp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.musicdlp.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

@Serializable
enum class SwipingMode(val displayName: String) {
    ONLY_NEW("Only New"),
    NEW_AND_LIKED("New and Liked"),
    PLAY_ALL_RECATEGORISE("Play All")
}

@Serializable
data class AlternateVersion(
    val youtubeUrl: String,
    val rawTitle: String? = null,
    val thumbnailUrl: String? = null
)

@Serializable
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val youtubeUrl: String,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val isrc: String? = null,
    val rawTitle: String? = null,
    val isMetadataCleaned: Boolean? = false,
    val likedAt: Long? = null,
    val dislikedAt: Long? = null,
    val metadataSource: String? = null,
    val alternateYoutubeUrls: String? = null,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getAlternateVersionsList(json: Json = Json { ignoreUnknownKeys = true }): List<AlternateVersion> {
        if (alternateYoutubeUrls.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<AlternateVersion>>(alternateYoutubeUrls)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addAlternateVersion(version: AlternateVersion, json: Json = Json { ignoreUnknownKeys = true }): Song {
        val currentList = getAlternateVersionsList(json).toMutableList()
        if (version.youtubeUrl != youtubeUrl && currentList.none { it.youtubeUrl == version.youtubeUrl }) {
            currentList.add(version)
        }
        val encoded = json.encodeToString(currentList)
        return copy(alternateYoutubeUrls = encoded)
    }

    // this really needs to be its own table with relations, or use the same table but have a "parent" link
    // its really slow deserialising the string every time and makes lookups by id even harder because we can't just select where id or songname matches
    // ie would be really nice to do containsYoutubeUrlOrId straight against the db, no hydration
    fun containsYoutubeUrlOrId(targetUrlOrId: String, json: Json = Json { ignoreUnknownKeys = true }): Boolean {
        if (targetUrlOrId.isBlank()) return false
        val cleanTarget = targetUrlOrId.trim()
        if (cleanTarget.length < 5 || cleanTarget.contains("watch?v=null")) return false

        if (id.isNotBlank() && !id.startsWith("url_") && !id.startsWith("search_") && id == cleanTarget) return true
        if (youtubeUrl.isNotBlank() && !youtubeUrl.contains("watch?v=null") && (youtubeUrl.contains(
                cleanTarget
            ) || cleanTarget.contains(youtubeUrl))
        ) return true

        return containsAlternateId(targetUrlOrId, json)
    }

    fun containsAlternateId(targetUrlOrId: String, json: Json = Json { ignoreUnknownKeys = true }): Boolean {
        val cleanTarget = targetUrlOrId.trim()
        if (cleanTarget.length < 5 || cleanTarget.contains("watch?v=null")) return false

        val alternates = getAlternateVersionsList(json)
        return alternates.any {
            it.youtubeUrl.isNotBlank() && !it.youtubeUrl.contains("watch?v=null") &&
                    (it.youtubeUrl.contains(cleanTarget) || cleanTarget.contains(it.youtubeUrl))
        }
    }
}

@UnstableApi
fun Song.toMediaItem(
    playableUri: String? = null,
    parentId: String? = null,
    isCurrentSong: Boolean = false,
    completionPercentage: Double? = null,
    context: Context? = null
): MediaItem {
    val artworkUri = if (thumbnailUrl.isNotBlank()) {
        Uri.parse(thumbnailUrl)
    } else if (youtubeUrl.contains("watch?v=")) {
        val id = youtubeUrl.substringAfter("watch?v=").substringBefore("&")
        Uri.parse("https://i.ytimg.com/vi/$id/hqdefault.jpg")
    } else null

    val safeId = id.ifBlank { "unknown_${youtubeUrl.hashCode()}" }

    val uriToUse: Uri = when {
        !playableUri.isNullOrBlank() -> if (playableUri.startsWith("/")) Uri.fromFile(File(playableUri)) else Uri.parse(playableUri)
        youtubeUrl.startsWith("content://") || youtubeUrl.startsWith("file://") || youtubeUrl.startsWith("/") -> {
            if (youtubeUrl.startsWith("/")) Uri.fromFile(File(youtubeUrl)) else Uri.parse(youtubeUrl)
        }
        youtubeUrl.startsWith("http://") || youtubeUrl.startsWith("https://") -> Uri.parse(youtubeUrl)
        else -> Uri.parse("http://dummy/$safeId")
    }

    val itemExtras = Bundle().apply {
        putBoolean("isLiked", isLiked)
        putBoolean("isDisliked", isDisliked)
        if (!parentId.isNullOrBlank()) {
            putString("parentId", parentId)
        }
        // absolutely no idea why the below seems inverted, the below give s green dot next to the
        // artist name thats playing, making it look like its playing
        // if I invert it all the songs get a green dot and the one thats playing is empty (i guess to indicate you can click it?)
        if (isCurrentSong) {
            putInt("android.media.extra.PLAYBACK_STATUS", 0)
            putBoolean("android.media.extra.IS_PLAYING", false) // this seems to not be needed if PLAYBACK_STATUS is supplied (or takes over if it is supplied, but its still inverted?)
            // this seems to do nothing
            putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            )
        } else {
            putInt("android.media.extra.PLAYBACK_STATUS", 1)
            putBoolean("android.media.extra.IS_PLAYING", true) // this seems to not be needed if PLAYBACK_STATUS is supplied (or takes over if it is supplied, but its still inverted?)
            // this seems to do nothing
            putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            )
            putDouble(
                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                completionPercentage ?: 0.5
            )
        }
    }

    val artworkData: ByteArray? = if (isLiked || isDisliked || isCurrentSong) {
        processArtworkOverlays(
            artworkUri = artworkUri,
            thumbnailUrl = thumbnailUrl,
            isLiked = isLiked,
            isDisliked = isDisliked,
            isCurrentSong = isCurrentSong,
            context = context
        )
    } else null

    val mediaMetadataBuilder = MediaMetadata.Builder()
        .setTitle(title.ifBlank { "Unknown Title" })
        .setArtist(artist.ifBlank { "MusicDLP" })
        .setArtworkUri(artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
        .setExtras(itemExtras)

    if (artworkData != null) {
        mediaMetadataBuilder.setArtworkData(
            artworkData,
            MediaMetadata.PICTURE_TYPE_FRONT_COVER
        )
    }

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriToUse)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uriToUse)
                .setExtras(itemExtras)
                .build()
        )
        .setMediaMetadata(mediaMetadataBuilder.build())
        .build()
}

private fun processArtworkOverlays(
    artworkUri: Uri?,
    thumbnailUrl: String,
    isLiked: Boolean,
    isDisliked: Boolean,
    isCurrentSong: Boolean,
    context: Context?
): ByteArray? {
    return try {
        val ctx = context ?: try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            currentAppMethod.invoke(null) as? Context
        } catch (e: Throwable) {
            null
        }

        val baseBitmap: Bitmap? = try {
            when {
                artworkUri == null -> null
                artworkUri.scheme == "content" && ctx != null -> {
                    ctx.contentResolver.openInputStream(artworkUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                artworkUri.scheme == "file" || artworkUri.scheme == null -> {
                    val filePath = artworkUri.path ?: thumbnailUrl
                    if (filePath.isNotBlank() && File(filePath).exists()) {
                        BitmapFactory.decodeFile(filePath)
                    } else null
                }
                artworkUri.scheme == "http" || artworkUri.scheme == "https" -> {
                    try {
                        val url = URL(artworkUri.toString())
                        val connection = url.openConnection()
                        connection.connectTimeout = 3000
                        connection.readTimeout = 3000
                        connection.getInputStream().use {
                            BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Throwable) {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            null
        }

        if (baseBitmap == null) return null

        val width = baseBitmap.width
        val height = baseBitmap.height
        if (width <= 0 || height <= 0) return null

        val overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlayBitmap)
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        val minDimension = minOf(width, height).toFloat()
        val badgeDiameter = minDimension * 0.26f
        val margin = minDimension * 0.05f
        val badgeRadius = badgeDiameter / 2f

        // 1. Thumbs Up / Down Overlay (Top-Right corner)
        if (isLiked || isDisliked) {
            val cx = width - margin - badgeRadius
            val cy = margin + badgeRadius

            val badgeBgColor = if (isLiked) 0xFF1DB954.toInt() else 0xFFD32F2F.toInt()
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = badgeBgColor
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = badgeDiameter * 0.06f
            }

            canvas.drawCircle(cx, cy, badgeRadius, bgPaint)
            canvas.drawCircle(cx, cy, badgeRadius, borderPaint)

            val drawableResId = if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_down_filled
            val drawable: Drawable? = ctx?.let { ContextCompat.getDrawable(it, drawableResId) }

            if (drawable != null) {
                val iconPadding = badgeDiameter * 0.22f
                val iconLeft = (cx - badgeRadius + iconPadding).toInt()
                val iconTop = (cy - badgeRadius + iconPadding).toInt()
                val iconRight = (cx + badgeRadius - iconPadding).toInt()
                val iconBottom = (cy + badgeRadius - iconPadding).toInt()

                drawable.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                drawable.mutate().setTint(Color.WHITE)
                drawable.draw(canvas)
            } else {
                drawFallbackThumbShape(canvas, cx, cy, badgeRadius, isLiked)
            }
        }

        // 2. Playing Status Overlay / "Now Playing" (Bottom-Right corner)
        if (isCurrentSong) {
            val cx = width - margin - badgeRadius
            val cy = height - margin - badgeRadius

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E88E5.toInt()
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = badgeDiameter * 0.06f
            }

            canvas.drawCircle(cx, cy, badgeRadius, bgPaint)
            canvas.drawCircle(cx, cy, badgeRadius, borderPaint)

            val eqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = badgeDiameter * 0.08f
            }

            val barHeights = floatArrayOf(0.40f, 0.85f, 0.55f, 0.90f)
            val totalBarsWidth = badgeDiameter * 0.48f
            val barSpacing = totalBarsWidth / 3f
            val startX = cx - (totalBarsWidth / 2f)
            val maxBarHeight = badgeDiameter * 0.45f

            for (i in 0..3) {
                val barX = startX + (i * barSpacing)
                val h = maxBarHeight * barHeights[i]
                val startY = cy + (h / 2f)
                val endY = cy - (h / 2f)
                canvas.drawLine(barX, startY, barX, endY, eqPaint)
            }
        }

        val outputStream = ByteArrayOutputStream()
        overlayBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        overlayBitmap.recycle()
        byteArray
    } catch (e: Throwable) {
        null
    }
}

private fun drawFallbackThumbShape(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    isLiked: Boolean
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val path = Path()
    val size = radius * 0.5f
    if (isLiked) {
        path.moveTo(cx - size * 0.5f, cy + size * 0.5f)
        path.lineTo(cx + size * 0.5f, cy + size * 0.5f)
        path.lineTo(cx, cy - size * 0.6f)
        path.close()
    } else {
        path.moveTo(cx - size * 0.5f, cy - size * 0.5f)
        path.lineTo(cx + size * 0.5f, cy - size * 0.5f)
        path.lineTo(cx, cy + size * 0.6f)
        path.close()
    }
    canvas.drawPath(path, paint)
}

