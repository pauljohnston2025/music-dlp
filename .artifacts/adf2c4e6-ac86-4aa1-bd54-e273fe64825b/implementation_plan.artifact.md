# Implementation Plan: App Fixes & Enhancements

Fix playback stream errors, alternate version handling, metadata updates on downloaded files, Android Auto "no more songs" handling, search results grid styling, and UI preview issues.

## Proposed Changes

### 1. ID3 Tag Writing & Cleaned Metadata Update
- **`data/YoutubeDLRepository.kt` & `service/MusicLibraryService.kt`**:
  - Implement ID3v2.3 tag writer (`writeId3Tags`) to write UTF-8 `TIT2` (Title) and `TPE1` (Artist) frames to downloaded `.mp3` files.
  - When downloading a song (`saveLikedSong`), apply `writeId3Tags` to the downloaded MP3 and set MediaStore metadata.
  - When updating a song's name/artist (`updateSongNameAndArtist`), if the downloaded file exists:
    - Rename `$oldArtist - $oldTitle.mp3` to `$newArtist - $newTitle.mp3`.
    - Update ID3 tags and MediaStore entry via ContentResolver.

### 2. "No More Songs" Playable Source & Navigation
- **`service/MusicLibraryService.kt`**:
  - In `createNoMoreSongsMediaItem`, generate a 1-second silent MP3 file in `cacheDir` and use `Uri.fromFile(silentFile)` with `.setIsPlayable(true)` so Android Auto receives a valid stream without "source error".
  - In `seekToPreviousMediaItem` and `seekToNextMediaItem`, handle transitioning back from "No More Songs" state by restoring `activeQueue` media items to `exoPlayer`.
- **`ui/MusicViewModel.kt`**:
  - Update `canGoPreviousInContext` and `canGoNextInContext` so "Previous" is enabled when in "No More Songs" state.

### 3. Stream Extractor Error Recovery
- **`service/MusicLibraryService.kt`**:
  - In `onPlayerError`, detect extractor/parsing errors, clear expired stream URL cache, and automatically perform a retry from scratch by re-fetching stream URL via `repository.getStreamUrl`.

### 4. Alternate Versions Dialog & Queue Sync
- **`ui/MusicViewModel.kt`**:
  - Keep `SharedQueueHolder` in sync whenever `playSong` or `playPreviewByUrl` is invoked, preventing `_activePlayingList` from reverting and closing the player bar or displaying wrong metadata.
  - Update `replaceLikedSongWithAlternateVersion` / `setAlternateAsCurrentVersion` to handle both Liked (downloading) and Unliked/New/Disliked (no force download) scenarios cleanly.
- **`ui/MusicScreens.kt`**:
  - Update `AlternateVersionsDialog`:
    - Differentiate wording for Liked pages (`"[Current Downloaded Version]"`, `"Replace Download"`) vs New/Disliked pages (`"[Current Version]"`, `"Set as Current"`).
    - Add a "Preview" / "Pause" button on the Current Version Card to allow comparing current vs alternate versions.
    - On Swipe Screen context, selecting an alternate sets it as current and plays it directly without preview mode.
  - In `CommonSongListScreen`, render both `trailingContent` (Retry button & download progress) and the Alternates chip so Retry is always accessible.

### 5. Folder Grid with Artwork in `onGetSearchResult`
- **`service/MusicLibraryService.kt`**:
  - In `performSearchInternal`, assign artwork URIs to the "Songs" and "Playlists" folder items using the artwork of their first contained item.
  - Include `createGridExtras()` on folder MediaItems and in `onGetSearchResult` response.

## Verification Plan
- Build and run the Android app using Gradle.
- Test previewing alternate and current versions in `AlternateVersionsDialog`.
- Verify switching alternates on Liked, Disliked, and New screens.
- Verify download progress when replacing downloaded versions.
- Verify ID3 tag writing on edited/downloaded MP3 files.
- Verify Android Auto folder grid rendering and "No More Songs" behavior.
