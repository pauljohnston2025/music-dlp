# Android Auto, Improved Notifications, and Fast Incremental Search

Implement full Android Auto support, lock screen controls, interactive notification icons, and a more efficient incremental search/metadata processing engine.

## User Review Required

> [!IMPORTANT]
> - **Android Auto Integration**: Requires implementing a `MediaLibraryService`. This will change how playback is managed, as `ExoPlayer` will be tied to a `MediaSession`.
> - **Notification Permissions**: Ensure you have granted notification permissions, as the new notification will be "ongoing" during playback.
> - **Metadata Cleaning**: I will be adding a MusicBrainz API lookup. This is rate-limited to 1 request per second.

## Proposed Changes

### Dependencies & Manifest

#### [MODIFY] [build.gradle.kts](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/build.gradle.kts)
- Add `androidx.media3:media3-session`.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/AndroidManifest.xml)
- Add `MediaLibraryService` declaration with `android.media.browse.MediaBrowserService` intent filter.

---

### Media Session & Android Auto

#### [NEW] [MusicLibraryService.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/service/MusicLibraryService.kt)
- Implement `MediaLibraryService`.
- Create and manage `MediaSession`.
- Implement `onGetLibraryRoot`, `onGetChildren`, and `onGetItem` for Android Auto browsing (Liked Songs, Disliked Songs).
- Implement `onPlayFromSearch` to handle Assistant voice commands ("Play X on MusicDLP").

---

### Metadata Processing Engine

#### [MODIFY] [YoutubeDLRepository.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/data/YoutubeDLRepository.kt)
- **Regex-First Logic**: Clean title/artist using regex first.
- **Scoring**: Score the cleaned metadata. If it contains "noise" or lacks a clear artist/title split, mark as "low confidence".
- **MusicBrainz Integration**: For high-confidence regex results, optionally verify against MusicBrainz (rate-limited).
- **Gemini Fallback**: Use Gemini only if regex confidence is low.
- **Final Fallback**: Use regex if Gemini fails.

---

### Fast Incremental Search

#### [MODIFY] [MusicViewModel.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/ui/MusicViewModel.kt)
- **Incremental Loading**: `searchPlaylists` and `loadPlaylist` will immediately populate the `songsToSwipe` list with "shallow" items (ID and Raw Title only).
- **Processing Buffer**: A background job will process metadata cleaning and stream URL fetching for only the top 5 songs in the queue.
- **Library Integration**: Link `ExoPlayer` to the `MediaSession` from `MusicLibraryService`.
- **Android Auto Playback Mode**: Implement "new and liked" mode for voice commands.

---

### UI & Notification

#### [MODIFY] [MainActivity.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/MainActivity.kt)
- Redesign bottom floating bar with full transport controls (Back, Play/Pause, Next).
- Hide floating bar on `SwipingScreen`.
- Support "Resume Swiping" action that closes the floating bar and resumes the swipe queue.

#### [MODIFY] [MusicScreens.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/ui/MusicScreens.kt)
- Ensure YouTube titles can span 2-3 lines without clipping.

## Verification Plan

### Automated Tests
- Build the project using `:app:assembleDebug`.

### Manual Verification
- **Notification**: Check that the notification has interactive icons (play/pause/next/prev) and is controllable from the lock screen.
- **Search**: Perform a search and verify that items appear almost instantly, with metadata filling in for the top 5.
- **Android Auto**: If possible, use the Android Auto Desktop Head Unit (DHU) to test browsing and voice commands.
- **Voice Commands**: Test "Play <song> on MusicDLP" via Google Assistant.
