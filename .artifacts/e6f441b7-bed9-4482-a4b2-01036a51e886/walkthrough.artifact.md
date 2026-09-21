# Walkthrough - MusicBrainz Integration

Integrated MusicBrainz recording lookup into `YoutubeDLRepository` with strict 1 request/second rate limiting using coroutine `Mutex` and delays.

## Changes

### [YoutubeDLRepository.kt](file:///C:/Users/RandomGuy2.1/garmin_apps/android/MusicDLP/app/src/main/java/com/example/musicdlp/data/YoutubeDLRepository.kt)
- Added `lookupMusicBrainz(query: String)` querying `https://musicbrainz.org/ws/2/recording` with `User-Agent: MusicDLP/1.0.0 ( contact@example.com )`.
- Enforced $\ge$ 1-second interval between requests using `Mutex` and `delay`.
- Filtered out video recordings (`video != true`) and extracted primary artist credit and recording title.
- Updated `cleanTitleAndArtist` to prioritize MusicBrainz lookups (with query retries on combined, title-only, and uploader-only queries) and falling back cleanly without heavy regex parsing.

## Verification Results

### Automated Tests
- Gradle build `app:assembleDebug` completed successfully.
