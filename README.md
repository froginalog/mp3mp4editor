# MP3/MP4 Editor

An Android app for pulling YouTube videos down as MP4 or MP3, and for clipping and trimming
audio/video files already on the phone.

## What it does

**Get tab — YouTube → MP4 / MP3**

- Paste a link, or share one to the app straight from YouTube (it registers as a share target).
  `youtu.be/…`, `/shorts/…`, `/embed/…`, `m.youtube.com` and bare video IDs all work.
- Pick a video quality (each one lists its approximate size). Adaptive streams arrive as separate
  video and audio files, which the app downloads and muxes back together into a single MP4.
- Or take the audio only: **MP3** at 128/192/320 kbps, or **M4A** which saves YouTube's original
  AAC stream untouched — no re-encode, so no generation loss.
- Downloads run in a foreground service with a progress notification, so locking the phone or
  switching apps doesn't kill them.

**Trim tab — clip and trim**

- Open any MP4/MP3/M4A from the phone, from the Library tab, or by opening a file with this app.
- Scrub with the player, drag the two handles to set the range, or type exact timestamps
  (`1:04.250`). "Playhead" buttons snap the start/end to wherever playback is sitting.
- "Preview clip" plays just the selection.
- Export as:
  - **MP4** — copies the original samples, no re-encode. Fast and lossless; the start snaps to the
    nearest keyframe.
  - **MP3** — sample-accurate, encoded with LAME at your chosen bitrate.
  - **M4A** — lifts the AAC audio out of the container untouched (offered when the source audio is
    AAC).

**Library tab**

Everything the app produces, with play / trim / share / delete. Files land in
`Movies/MP3MP4Editor` and `Music/MP3MP4Editor`, so they show up in Gallery, the music player and
anything else that reads the media store.

## Getting it onto your phone

### Easiest: let CI build it

Every push builds a debug APK. Go to the repo's **Actions** tab → the latest **Build APK** run →
download the `mp3mp4editor-debug` artifact, unzip it, and open the `.apk` on your phone. You'll
need to allow "install unknown apps" for whatever app you open it from.

### Or build it yourself

Requires Android Studio (or a command-line SDK) with API 35 and JDK 17.

```bash
./gradlew assembleDebug
# then, with the phone plugged in and USB debugging on:
./gradlew installDebug
```

Minimum Android version is 10 (API 29).

## How it's put together

```
youtube/    NewPipeExtractor wrapper — resolves a watch URL to concrete stream URLs
media/      the actual work:
              FileDownloader   resumable HTTP download
              Mp4Tools         MediaExtractor + MediaMuxer: mux video+audio, trim by sample copy
              PcmDecoder       MediaCodec decode of any audio track to raw PCM
              Mp3Sink          LAME encoder fed by PcmDecoder
              MediaStoreWriter publishes finished files to Movies/ and Music/
              JobManager       the queue; one job at a time, progress as a StateFlow
service/    foreground service that keeps jobs alive and mirrors them into a notification
ui/         Compose screens (download / edit / library)
```

Two deliberate choices worth knowing about:

- **Container operations never re-encode.** Trimming an MP4 and muxing YouTube's separate video and
  audio streams both copy encoded samples with `MediaMuxer`. That's why they're near-instant, and
  why an MP4 trim starts at a keyframe rather than the exact millisecond. MP3 export goes through a
  full decode/encode, so it *is* exact.
- **MP3 needs LAME.** Android's `MediaCodec` can decode MP3 but not encode it, so the app decodes to
  PCM and hands the samples to LAME. Everything else uses platform APIs only.

## Dependency notes

Two dependencies come from JitPack rather than Maven Central, and they're the two most likely to
need attention over time:

- `com.github.TeamNewPipe:NewPipeExtractor` — this is what understands YouTube. YouTube changes
  things and old versions stop working; when downloads start failing with "couldn't read that
  video", bump `newpipe` in `gradle/libs.versions.toml` to the latest tag from
  [the releases page](https://github.com/TeamNewPipe/NewPipeExtractor/releases). This is expected
  maintenance, not a bug in the app.
- `com.github.naman14:TAndroidLame` — prebuilt LAME JNI bindings, used only by
  `media/Mp3Sink.kt`. If it ever fails to resolve, that one file and the `android-lame` dependency
  are the whole blast radius; M4A export keeps working without it.

The release build type has shrinking turned **off** on purpose — NewPipeExtractor drives Rhino
reflectively and shrinking it breaks stream resolution at runtime rather than at build time.
`app/proguard-rules.pro` has the keep rules if you want to turn it back on.

## A note on YouTube

Downloading from YouTube is against YouTube's Terms of Service, whatever the content is. This is a
personal-use tool; what you point it at, and whether you have the rights to keep a copy, is on you.
