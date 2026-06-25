# CarStream

A no-watermark Android **IRL / mobile live-streaming sender** built for drop-resistant cellular streaming. Camera → H.264/AAC → **SRT**, with auto-reconnect, adaptive bitrate, in-app settings and camera switching.

Built on the excellent [StreamPack](https://github.com/ThibaultBee/StreamPack) library (and its boilerplate), with a connection layer tuned for unreliable cellular links — the "sender" end of a personal **no-dropout in-car streaming** rig.

## Features
- **Camera → SRT** over cellular (IPv6 or IPv4), no watermark.
- **Auto-reconnect** on drop (exponential backoff) so a momentary signal loss doesn't end the stream.
- **Adaptive bitrate (ABR)** — encoder bitrate drops automatically when the link gets thin (SRT bitrate regulator).
- **In-app settings**: bitrate / resolution / fps, persisted.
- **Front/back camera switch**, keep-screen-on, editable SRT URL with on-screen connection status.

## Build
CLI (no Android Studio needed):
```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use
Open the app, set the SRT URL (the `streamid` is whatever your receiver expects, e.g. `publish:car`), press the live button. Pair it with an SRT receiver such as [MediaMTX](https://github.com/bluenviron/mediamtx). For automatic failover across multiple links, see [mediamtx-failover-controller](https://github.com/akagifreeez/mediamtx-failover-controller).

> Note from building on StreamPack 3.1.x: the SRT URL parser accepts `streamid` / `latency` / `mss` etc. but **not** `conntimeo` (it rejects the whole URL) — set connection timeouts via the API, not the URL.

## Credit & License
Based on [StreamPack-boilerplate](https://github.com/ThibaultBee/StreamPack-boilerplate) by Thibault Beyou. Licensed under **Apache-2.0** — see [LICENSE.md](LICENSE.md).
