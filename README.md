# StreamVault

StreamVault is a TV-first IPTV player for Android TV built with Kotlin, Jetpack Compose, Room, Hilt, and Media3.

It is designed for large playlists, remote-friendly browsing, fast provider switching, and a polished living-room playback experience. StreamVault supports both `M3U` playlists and `Xtream Codes`, with dedicated flows for `Live TV`, `Movies`, and `Series`.

## Highlights

- Android TV-first interface with D-pad-friendly focus, navigation, and playback flows
- Live TV with favorites, recent channels, custom groups, pinned categories, and fast channel browsing
- Movie and series libraries with detailed metadata screens, resume support, and category navigation
- Multi-view split-screen playback for watching multiple channels at once
- Full EPG support with now-playing data and provider archive or catch-up support when available
- Strong parental controls with PIN-protected categories and automatic adult-category detection
- Global search across live channels, movies, and series
- Launcher and TV integrations including Android TV entry points and TV input support

## Feature Set

- Provider onboarding for `M3U` playlists and `Xtream Codes`
- Resilient sync pipeline for live, movie, series, and EPG data
- TV-optimized search inputs and provider setup forms
- Favorites, recent history, continue watching, and playback history
- Custom favorite groups and personalized live-TV organization
- Multi-view audio focus management and split-screen planning
- Subtitle, audio-track, aspect-ratio, playback-speed, and video-track controls
- Cast integration
- Provider-scoped settings and category visibility or protection preferences

## Screenshots

Add your own screenshots to `docs/images/` and update this section with the final image links when they are ready.

## Download

- [Download StreamVault.apk](releases/StreamVault.apk)
- The repository currently tracks a built release APK so GitHub can serve it directly from the project page.

## Project Structure

- `app/` Android app UI, navigation, dependency injection, and Android TV integrations
- `data/` Room database, sync, parsing, provider implementations, and repositories
- `domain/` models, repository contracts, managers, and use cases
- `player/` playback abstraction and Media3 player implementation
- `docs/` architecture notes and image assets

## Build

Requirements:

- Android Studio
- Android SDK
- JDK 17 or another Gradle-supported JDK 17 runtime

Useful commands:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

## Notes

- StreamVault is an IPTV client, not a content provider.
- Use only playlists, streams, and guide sources you are authorized to access.
- Local configuration and signing files are intentionally excluded from git.
