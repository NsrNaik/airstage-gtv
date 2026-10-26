# AirStage

AirStage (repository `airplay-tv`).

**Version 3.0** (versionCode 3). See [Changes](#changes) for what is new.

An open-source AirPlay receiver for Android TV / Google TV sticks. It makes the stick show up in an
iPhone's AirPlay menu for:

- **Music**: audio from the iOS Music app (other apps' AirPlay audio takes the same path but was not
  tested), lossless ALAC, with a
  full-screen **Now Playing** screen: the cover with a "phone · · · TV" header above it (the dots
  pulse while playing), over a slowly drifting blurred copy of the cover; an editorial serif title,
  widely spaced artist and album, and a hairline progress bar, all coloured from the cover's main
  colour (picked by vividness × area, lightened for readability); and, when available,
  **time-synced lyrics** (press ▼ / ▲) that glide to the current line with a colour fade, without
  moving the title or the bar.
- **YouTube app video**: the YouTube app hands the stream over to the TV, which plays it itself
  (full screen, ExoPlayer). Pause, play and seek from the iPhone work, and the iPhone's scrubber
  follows the TV.

- **Photos and Screen Mirroring**: iOS sends Photos pictures and videos (and Control Center's
  Screen Mirroring) as a live mirrored H.264 stream, decoded with the TV's hardware decoder.
- **PIN pairing**: a new iPhone/iPad/Mac must enter a 4-digit code shown on the TV, once; its
  pairing key is then remembered. The PIN can be turned off, and paired devices forgotten, on the
  app's start screen.
- **Plays nicely with the TV**: Back on the Now Playing screen offers *Continue in background* or
  *Stop & disconnect*. In the background, when another app starts playing on the TV, AirPlay goes
  quiet and the iPhone is paused (over DACP); pressing play on the iPhone takes the TV back.
- **TV remote controls the iPhone**: on the Now Playing screen, OK / play-pause pauses and resumes
  the iPhone, ◀ ▶ go to the previous / next track (over DACP). On YouTube video, OK pauses and ◀ ▶
  skip 10 s; the iPhone follows. The remote only does this while the AirPlay screen is open, never
  in the background. Reopening the app during playback goes straight back to Now Playing.
- **Lyrics**: AirPlay does not carry lyrics, so they are looked up on [LRCLIB](https://lrclib.net)
  (open, no account) by title, artist, album and length, and shown line by line with the music.
- **Refresh on network**: a button on the start screen re-announces the receiver, for when the TV
  has dropped out of the iPhone's AirPlay list.
- **In sync with the iPhone**: audio is played at the time the iPhone stamps on each packet, so the
  TV's sound and progress bar match the Music app (and its lyrics), and stop together on pause.

It starts by itself at boot and runs as a foreground service. Developed and tested on a
**Thomson 152** (Android TV 14, 32-bit `armeabi-v7a`) with an iPhone on iOS 26 (build 23G90).

## How it works

```
iPhone ──RTSP/HTTP :7001──▶ UxPlay protocol library (C, NDK) ──JNI──▶ Kotlin app
                             · FairPlay/pairing handshake          · NsdManager: _airplay._tcp, _raop._tcp
                             · RTP audio, decryption, jitter buffer · AudioTrack (ALAC decoded natively,
                             · HLS: fetches YouTube playlists          AAC via MediaCodec)
                               through the iPhone, serves them at   · Now Playing screen
                               http://localhost:7001/master.m3u8    · ExoPlayer for video, reports
                                                                      position back for /playback-info
```

The protocol work is all [UxPlay](https://github.com/FDH2/UxPlay)'s `lib/` (reverse-engineered
AirPlay, GPL-3), vendored unchanged in `app/src/main/cpp/uxplay` (commit recorded in `UPSTREAM`).
This project replaces UxPlay's GStreamer renderers and DNS-SD backend with Android ones:

| File | Role |
|---|---|
| `app/src/main/cpp/airplay_jni.cpp` | UxPlay callbacks → Kotlin; ALAC decoding; playback state for the iPhone |
| `app/src/main/cpp/dnssd_android.c` | UxPlay's DNS-SD interface, announcing through `NsdManager` |
| `AirPlayService.kt` | Foreground service: starts the server, announces it, plays audio, opens screens |
| `AudioOutput.kt` | AudioTrack output, AAC decoding, AirPlay volume curve |
| `NowPlayingActivity.kt`, `NowPlaying.kt`, `ScrollingTextView.kt` | Now Playing screen and its state |
| `VideoActivity.kt`, `VideoController.kt` | Full-screen video and iPhone remote control |
| `MirrorActivity.kt`, `MirrorController.kt` | Screen mirroring (Photos): H.264 → MediaCodec → surface |
| `PairedDevices.kt` | Devices that entered the PIN once |
| `Lyrics.kt` | Synced lyrics lookup on LRCLIB and LRC parsing |
| `ConnectionHeader.kt` | The "phone · · · TV" header with pulsing dots |
| `LyricsView.kt` | Scrolling, colour-fading lyrics display |
| `Net.kt` | Shared HTTP client (IPv4 first) for video and lyrics |
| `DacpRemote.kt` | Sends play/pause, next and previous to the iPhone (remote keys, other TV media) |

## Build

Needs JDK 17, Android SDK platform 34, NDK `27.2.12479018`, CMake `3.22.1` (SDK packages) and perl.

```bash
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/27.2.12479018
scripts/build-openssl-android.sh      # once: static libcrypto for both ABIs into third_party/openssl
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

### Wallpaper (optional, not in git)

The start screen and the "Connected — waiting…" / PIN screens can show a background image. No image
is kept in this repository: put your own JPEG at

```
app/src/main/assets/wallpaper.jpg
```

before building and it is used; without it those screens are plain black. The path is in
`.gitignore`, so the image is never committed. The TV draws its interface at 1920×1080; a
3840×2160 (16:9) image is fine and is decoded once, in the background, at reduced colour depth to
save memory. Images of another shape are centre-cropped to fill the screen.

## Install

Turn on developer options and USB debugging on the TV, then:

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
# lets the background service bring the Now Playing / video screens to the front:
adb shell appops set dev.airplaytv SYSTEM_ALERT_WINDOW allow
adb shell am start -n dev.airplaytv/.MainActivity
```

The receiver is announced as **"<TV name> AirStage"** (e.g. "Living Room TV AirStage"): Android 14's mDNS
advertiser refuses a second service with the bare TV name, which the TV's remote-control service
already uses.

**VPN apps:** if the TV runs a VPN (e.g. Surfshark), exclude "AirStage" from it (Surfshark:
*Bypasser*). YouTube's stream URLs are tied to the iPhone's public IP, so the TV must fetch them from
the same home connection; through a VPN, video never starts. Audio is unaffected (it stays on the
local network).

Protocol logs: `adb shell setprop debug.airplaytv.verbose 1`, restart the app, `adb logcat -s UxPlay`.

## Known limits

- **No DRM.** FairPlay-protected video (Apple TV app, Netflix, most paid services) cannot be decrypted:
  that needs Apple's keys. Such apps won't offer this receiver for video, or send audio only.
- **Photos are mirrored, not sent as files.** iOS re-encodes the screen live: standard dynamic
  range (HDR videos are shown in SDR), at most 1920×1080, and some compression. Only Apple's own
  AirPlay 2 receivers get the original file.
- **Safari / web video sends audio only.** On iOS 26 Safari does not hand video URLs to this receiver,
  even for a plain HLS `<video>` (tested with Apple's own example page); the picture stays on the
  iPhone. UxPlay has the same limit. The **YouTube app** works; YouTube *in Safari* does not.
- **Other video apps** that use AirPlay URL hand-off were not tested; UxPlay's HLS support targets the
  YouTube app's protocol specifically.
- **iOS updates can break it.** The protocol is reverse-engineered (UxPlay); Apple can change it at any
  time. Update the vendored UxPlay `lib/` when UxPlay releases fixes.
- **Not AirPlay 2 multi-room.** It is a single "realtime" AirPlay receiver: no buffered audio, no
  grouping with HomePods or other speakers.
- Audio timing uses the iPhone's own timestamps and assumes the iPhone and TV clocks agree (both keep
  internet time): UxPlay's NTP clock exchange with the iPhone does not complete on this setup. If a
  clock is badly off, audio falls back to playing as it arrives.
- Mirroring sound and picture are each played as they arrive (no lip-sync correction).
- **No "explicit" (E) badge.** AirPlay does not send the content rating, and Apple's public
  catalogue disagrees with the Music app for some songs, so a badge would sometimes be wrong.
- **Lyrics are not Apple's.** Apple Music's lyrics are licensed and only reachable with an Apple
  developer token plus the user's Apple Music login, so they are not used. LRCLIB is community
  maintained: some songs, especially regional ones, have no lyrics there, and the TV needs internet
  (bypass any VPN for the app).
- The TV's elapsed/remaining time can differ from the iPhone's by about a second, varying from one
  session to the next: the iPhone never sends its exact on-screen time, only positions that are
  1–2 s off from what is being heard, so the TV shows the audio it actually plays.

## Licence

GPL-3.0 (see `LICENSE`), as required by UxPlay. Bundled third-party code: UxPlay `lib/` (GPL-3.0,
including playfair GPL-3.0 and llhttp MIT), libplist 2.7 (LGPL-2.1), Apple ALAC decoder (Apache-2.0).
OpenSSL 3.0 (Apache-2.0) is built locally by `scripts/build-openssl-android.sh` and linked statically.

## Changes

**3.0**
- Renamed to **AirStage** (display name and the AirPlay menu name, "<TV name> AirStage"; the package
  id `dev.airplaytv` is unchanged, so pairings and settings carry over).
- TV remote → iPhone player on the Now Playing screen: play/pause, previous/next track (DACP; the
  iPhone's address is found once per session, so key presses are instant). "Paused" and
  "Next/Previous track" shown on screen.
- YouTube video: OK to pause/play, ◀ ▶ to skip 10 s.
- The remote never controls the iPhone in the background.
- Opening the app during AirPlay goes to Now Playing (or mirroring), not the start screen.
- Progress bar resumes from the audio itself after a pause; remaining time rounded like the iPhone.
- Time-synced lyrics from LRCLIB on the Now Playing screen (▼ show, ▲ hide; remembered).
- New "phone · · · TV" header with pulsing dots, above the cover (also on the video loading screen).
- Now Playing redesign: fixed layout (title and progress never move), smooth lyric transitions,
  editorial serif title, colours taken from each song's album art (title, artist, bar, sung lyric
  line), no shadow under the cover, drifting blurred background.
- "Refresh on network" button on the start screen.

**2.0**
- PIN pairing: new devices enter a 4-digit code shown on the TV once; settings to turn it off and
  forget paired devices.
- Back on Now Playing: *Continue in background* or *Stop & disconnect*. Other media on the TV pauses
  AirPlay and the iPhone (DACP).
- Audio is played at the sender's timestamps: sound and progress match the iPhone, pause is exact.
- Screen mirroring, used by iOS for Photos pictures and videos and by Control Center.
- "Connected — waiting for audio or video" and "Starting the stream" screens; new black/sky-blue icon.
- Optional wallpaper from `assets/wallpaper.jpg` (not in git), else black.

**0.1**
- Music audio with Now Playing screen; YouTube-app video hand-off with pause/seek from the iPhone.
