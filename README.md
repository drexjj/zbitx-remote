# sBitx Remote (Android)

A native Android client for remotely operating an **sBitx v3** running the
**drexjj/sbitx** custom firmware (v5.x), over the internet via **Tailscale**.

Supports: PTT with live phone-mic SSB transmit, frequency tuning and direct
entry, band switching, mode (LSB/USB/CW/AM), mic gain, volume, drive (TX power),
bandwidth, S-meter readout, and live RX audio.

## Requirements

- sBitx v3 flashed with the drexjj 64-bit image (v5.0+ — this added the
  browser/remote microphone TX capability the app relies on).
- Tailscale installed on the radio's Raspberry Pi and on your phone
  (or any other VPN/LAN path between them).
- The radio's web PIN set via the SET menu.
- Android 8.0+ (minSdk 26).

## Quick start

1. Open the project in Android Studio (Hedgehog or newer), let Gradle sync,
   then Run on your phone.
2. Grant the microphone permission when prompted (needed for PTT voice).
3. Enter the radio's address:
   - On LAN: its local IP or `sbitx.local`'s resolved IP, port `8080`.
   - Over the internet: its **Tailscale IP or MagicDNS hostname**, port `8080`
     (or `8443` with the TLS checkbox if you've set up the firmware's SSL certs).
4. Enter the radio PIN and Connect.
5. Hold the big PTT button to talk; release to receive.

## How it talks to the radio (protocol summary)

Derived from `src/webserver.c` and `src/sbitx.c` in github.com/drexjj/sbitx:

- Single WebSocket: `ws://<host>:8080/websocket` (or `wss://<host>:8443/websocket`)
- Text messages client→radio: `"<cookie>\n<field>=<value>"`, max 99 chars.
  - Login: `"nullsession\nlogin=<PIN>"` → radio replies `login <cookie>`
    (or `login error`).
  - Anything else is executed as a console command: `freq=7100000`,
    `mode=LSB`, `mic=25`, `drive=40`, `bw=2400`, `tx=`, `rx=`, band buttons
    (`80M`…`10M`), `agc=SLOW`, `rit=ON`, etc. Full command list:
    `src/help_commands.txt` and `sBitx v5.3 commands.pdf` in the firmware repo.
  - Keywords: `refresh` (radio re-sends every field as `LABEL value` text
    frames), `audio` (radio replies with a spectrum text frame + a binary
    RX-audio frame), `spectrum`, `logbook`, `macros_list`.
- Radio→app binary frames: **int16 LE PCM, 16 kHz mono** RX audio.
- App→radio binary frames: **int16 LE PCM, 8 kHz mono** phone-mic audio;
  the firmware jitter-buffers and upsamples 12× into the 96 kHz TX chain.
  The app sends 400-sample (50 ms) chunks continuously while PTT is held.
- The server pings every 2 s and drops clients after 5 s of silence
  (OkHttp answers pings automatically; the app also polls `audio` every 80 ms).
- Max 10 simultaneous WebSocket clients.

## Project layout

```
app/src/main/java/com/sbitx/remote/
  net/SbitxClient.kt       WebSocket protocol client (login, commands, parsing)
  audio/RxAudioPlayer.kt   AudioTrack playback of 16 kHz RX stream
  audio/MicStreamer.kt     AudioRecord 8 kHz mic capture for PTT
  service/RadioService.kt  Foreground service (survives screen-off)
  ui/MainActivity.kt       Jetpack Compose UI
```

## Known limitations / TODO

- No spectrum/waterfall rendering yet (frames arrive as ASCII-encoded bins on
  `RX `/`TX ` text messages — hook `client.spectrum` to a Canvas).
- No FT8/CW keyboard console yet (`text=` command + `console` frames).
- No logbook view (`logbook` keyword returns rows).
- Reconnect-on-drop is manual; add auto-retry with backoff for mobile use.
- Latency depends on your network path; expect a noticeable but usable delay
  on mobile data. Test on LAN first.

## Legal note

You are the control operator when transmitting remotely — ensure your
license privileges and local regulations permit remote operation.
