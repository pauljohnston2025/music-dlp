# Implementation Plan: Fix Search Queue Decrementing, Playback Mode Alternate Skipping, and Alternate Version UI

## Overview
After searching in the app, search result counts currently decrement continuously as background metadata cleaning runs. This happens because search items matching existing DB songs were being replaced in `activeQueue` by the parent DB song rather than retaining their position as search items.
Additionally, playback modes need precise filtering to ensure "New and Liked" plays only new songs and primary liked versions (skipping alternates), while "Play All" plays everything including alternates.
Finally, the UI for displaying alternate versions on the swipe page (in the category dialogs) will be upgraded from nested dialog overlays to inline expandable sections for a seamless user experience.

---

## User Review Required

> [!IMPORTANT]
> **Key Enhancements**:
> 1. **Stable Search Counts**: Search results will no longer shrink/decrement during metadata cleaning. Every item in the search queue remains in the queue.
> 2. **Mode Filtering Rules**:
>    - **Only New**: Plays new songs (skips liked, disliked, and alternate versions).
>    - **New and Liked**: Plays new songs and primary liked versions (skips disliked and alternate versions).
>    - **Play All**: Plays all songs in queue, including alternate versions.
> 3. **Inline Alternate View**: In the category dialogs on the Swipe page, clicking "Alternates" expands the list directly within the song card inline instead of popping up a nested dialog over the existing dialog.

---

## Proposed Changes

### 1. Music Library Service
#### [MODIFY] [MusicLibraryService.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/service/MusicLibraryService.kt)
- **Fix Queue Processing**: In `processQueue()`, when a search result (`songToProcess`) matches `matchingExistingSong` by clean title & artist:
  - Add `songToProcess.youtubeUrl` to `matchingExistingSong`'s `alternateYoutubeUrls` list and update DB.
  - Construct `cleanedSong` from `songToProcess` (preserving `songToProcess.id` and `youtubeUrl`) with cleaned title, artist, `isMetadataCleaned = true`, and inherited `isLiked`/`isDisliked` status.
  - Keep `songToProcess.id` in `activeQueue` so that search results do not get replaced by duplicates of `matchingExistingSong`.
- **Refine `isSongPlayableInMode`**: Update mode checking logic so that:
  - `SwipingMode.ONLY_NEW`: returns `true` only for non-liked, non-disliked, non-alternate songs.
  - `SwipingMode.NEW_AND_LIKED`: returns `true` for new songs and primary liked songs, returning `false` for disliked songs and alternate versions.
  - `SwipingMode.PLAY_ALL_RECATEGORISE`: returns `true` for all songs (including alternates).

### 2. ViewModel Logic
#### [MODIFY] [MusicViewModel.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/ui/MusicViewModel.kt)
- **Categorization StateFlows**: Refine `playlistLikedSongs`, `playlistDislikedSongs`, and `playlistNewSongs` flows so that:
  - `playlistLikedSongs` contains primary liked songs in queue (excluding alternates).
  - `playlistDislikedSongs` contains primary disliked songs in queue (excluding alternates).
  - `playlistNewSongs` contains new songs in queue (excluding liked, disliked, and alternate versions).
- **Playlist Total**: Keep `_playlistTotal` consistent with queue size.

### 3. UI Screens & Components
#### [MODIFY] [MusicScreens.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/ui/MusicScreens.kt)
- **Category Chips**: Display stable counts for `liked`, `disliked`, `new`, and `all` search queue items.
- **`FilteredSongsDialog` Inline Expansion**:
  - Replace the nested `AlternateVersionsDialog` pop-up in `FilteredSongsDialog` with an inline expandable card section (`AnimatedVisibility`).
  - Allow users to tap "Alternates (X)" to toggle inline expansion showing alternate versions with Preview / Replace Download actions directly within the list.
- **Card Badge**: Clear badges/chips identifying "Already Liked (Alternate)" or "Already Disliked (Alternate)" vs primary liked/disliked songs.

---

## Verification Plan

### Automated Tests
- Build project using Gradle tasks: `./gradlew app:assembleDebug` or `gradle_build("app:assembleDebug")`.
- Verify compilation and zero build errors.

### Manual Verification
- Test search functionality with multi-song search queries. Verify that "all" count remains stable (e.g. 15 songs) as metadata cleaning finishes.
- Verify playback modes:
  - In "New and Liked" mode, alternate versions of liked songs are skipped during swiping/playback.
  - In "Play All" mode, alternate versions are played.
  - In "Only New" mode, alternate versions and liked/disliked songs are skipped.
- Open "All", "Liked", "Disliked", and "New" category dialogs from the top chips on the Swipe Screen. Tap "Alternates" on a song and verify smooth inline expansion without nested dialog pop-ups.
