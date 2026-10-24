# airplay-tv

An open-source AirPlay receiver for Android TV / Google TV sticks. It makes the stick show up in an
iPhone's AirPlay menu for:

- **Music**: audio from the iOS Music app (other apps' AirPlay audio takes the same path but was not
  tested), lossless ALAC, with a
  full-screen **Now Playing** screen: cover art over a blurred copy of it, "iPhone → TV" header,
  title / artist / album (long names scroll) and track progress.
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
| `DacpRemote.kt` | Sends "pause" back to the iPhone when other TV media starts |

## Build

Needs JDK 17, Android SDK platform 34, NDK `27.2.12479018`, CMake `3.22.1` (SDK packages) and perl.

```bash
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/27.2.12479018
scripts/build-openssl-android.sh      # once: static libcrypto for both ABIs into third_party/openssl
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

## Install

Turn on developer options and USB debugging on the TV, then:

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
# lets the background service bring the Now Playing / video screens to the front:
adb shell appops set dev.airplaytv SYSTEM_ALERT_WINDOW allow
adb shell am start -n dev.airplaytv/.MainActivity
```

The receiver is announced as **"<TV name> AirPlay"** (e.g. "Living Room TV AirPlay"): Android 14's mDNS
advertiser refuses a second service with the bare TV name, which the TV's remote-control service
already uses.

**VPN apps:** if the TV runs a VPN (e.g. Surfshark), exclude "AirPlay Receiver" from it (Surfshark:
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

## Licence

GPL-3.0 (see `LICENSE`), as required by UxPlay. Bundled third-party code: UxPlay `lib/` (GPL-3.0,
including playfair GPL-3.0 and llhttp MIT), libplist 2.7 (LGPL-2.1), Apple ALAC decoder (Apache-2.0).
OpenSSL 3.0 (Apache-2.0) is built locally by `scripts/build-openssl-android.sh` and linked statically.
