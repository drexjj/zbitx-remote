# 📻 zBitx Remote Fork By W9JES

**A native Android app for operating a zBitx QRP transceiver from anywhere.**

Tune the bands, work SSB with your phone's microphone, run FT8 and CW, and listen to live receiver audio — over your LAN or over Tailscale.

Forked from [vis4573/sbitx-remote](https://github.com/vis4573/sbitx-remote) (VU3UBP) and adapted to the zBitx software ([drexjj/zbitx](https://github.com/drexjj/zbitx)).

---

## ✨ Features

- 🎙️ **Remote SSB voice** — hold-to-talk PTT streams the phone mic into the zBitx browser-mic path; the button turns red only when the radio confirms it is on the air
- 🔊 **Live RX audio** — 16 kHz receiver audio with a jitter buffer tuned for mobile networks; muted while you transmit so the speaker can't feed back
- 🌊 **Waterfall + spectrum** — correct orientation, passband overlay from the radio's LOW/HIGH filter, **tap to tune**, **drag to pan**, span selector
- 📶 **Frequency control** — tap a digit then turn the knob, direct kHz entry, VFO A/B, lock, split
- 🎛️ **zBitx controls** — 80 m–10 m incl. 60 m, USB/LSB/AM/CW/CWR/FT8/DIGI, volume, IF gain, drive, bandwidth, compressor, AGC, ANR / DSP / NOTCH, TUNE
- 📏 **Meters** — S-meter on receive, forward power and SWR on transmit
- 📟 **FT8** — colour-coded decodes; **tap a decode and the radio runs the QSO** (same as the zBitx web UI), auto mode, F1–F12 macros from the radio's FT8 macro file, Stop, free-text messages
- ⌨️ **CW / CWR** — decoded text, type-to-send through the radio's keyer, F1–F12 macros, QSO logger (Call / Sent / Rcvd / exchange → Log), WPM and pitch
- 🗂️ **Macro files** — pick any `.mc` file on the radio (`~/sbitx/web`); CW/CWR and FT8 each remember their own file (defaults CW1 and FT8). Tap a key to send, long-press to preview
- 🔁 **Auto-reconnect** — rides out Wi-Fi/cellular drops with backoff; if the link dies while keyed, the radio is unkeyed as soon as it comes back
- 📒 **Logbook** — browse the radio's log (newest first, search by callsign, load older); every mode has a logger row (Call / Sent / Rcvd / exchange or grid) with Log QSO and Wipe
- 🔄 **Rotation** — portrait or landscape (knob and PTT move to the right side in landscape); follows the phone's auto-rotate setting
- 🔢 **Version** shown under the title and on the connect screen
- 📱 **Background-safe** — a foreground service keeps the session alive with the screen off

---

## 📋 Requirements

| Component | Requirement |
|---|---|
| Radio | zBitx (Raspberry Pi Zero 2 W + RP2040 front panel) |
| Software | [drexjj/zbitx](https://github.com/drexjj/zbitx) with the web server on port 8443 |
| Phone | Android 8.0+ |
| Remote access | Same LAN, or [Tailscale](https://tailscale.com) for the internet |

---

## 🚀 Quick start

1. On the radio, press **SET** and note the **PASSKEY** (case-sensitive).
2. Check the web UI works from a laptop: `https://zbitx.local:8443` (accept the self-signed certificate).
3. Install the APK on your phone (Releases page, or the CI artifact from any branch).
4. In the app: **Local network**, address `zbitx.local` (or its IP), port `8443`, TLS on, your passkey → **Connect**.
5. For use away from home, install Tailscale on the Pi (`scripts/sbitx-remote-setup.sh` does it in one step) and on the phone, then pick **Tailscale (internet)** and enter the Pi's `100.x.y.z` address.

> zBitx allows **one remote session at a time**. Logging in from the web UI on another device ends the app's session (and vice versa); the app tells you when that happens.

---

## 🔧 How it talks to zBitx

The app speaks the same WebSocket protocol as the zBitx web UI (`src/webserver.c`, `web/index.html`):

- `wss://<radio>:8443/websocket` carries login, commands, telemetry and audio. Plain port 8080 is redirected to HTTPS for anything but localhost.
- Commands are `<cookie>\n<command>`, max 99 chars, run through `cmd_exec()` on the Pi. The command word must be at least 2 characters or the radio drops the connection, which is why PTT is sent as `t ` / `r `.
- RX audio: int16 PCM, 16 kHz mono, returned for each `audio` poll (every 50 ms).
- TX audio: int16 PCM, 8 kHz mono in 32 ms frames. The radio falls back to its own mic after 100 ms without a frame, and does **not** apply its MIC gain to remote audio — use the app's *Phone mic* slider (COMP and TX EQ still apply).
- Spectrum: `RX `/`TX ` frames, 46.875 Hz per bin, highest frequency first.
- Macros: `macros_list` lists the `.mc` files, `MACRO=<file>` loads one (the radio then sends `F1 <label>` … `F12 <label>`), `F<n>` runs a key on the radio. Macros read the radio's logger fields CALL/SENT/NR.
- Console: `CONSOLE <WSJTX-RX>…</WSJTX-RX><CW-RX>…` tags, entity-escaped, possibly split across frames.

```
app/src/main/java/com/sbitx/remote/
  net/SbitxClient.kt       WebSocket protocol client + reconnect
  audio/RxAudioPlayer.kt   16 kHz RX playback
  audio/MicStreamer.kt     8 kHz PTT mic capture, gain + soft clip
  service/RadioService.kt  Foreground service, PTT sequencing
  ui/MainActivity.kt       Connect screen and main radio panel
  ui/Widgets.kt            Waterfall, meters, knob, FT8 and CW consoles
  ui/Macros.kt             Macro file picker, F1–F12 keys, QSO logger
  ui/Logbook.kt            Logbook viewer
```

---

## ⬆️ Updating the app

Every CI build is signed with the same key (`app/zbitx-remote.keystore`) and gets a higher version code, so a newly downloaded APK installs **over** the old one — no uninstall needed. (Builds made before this change used a random key per build, so uninstall once when moving to the first build that has it.)

## 🛠️ Building

Open in Android Studio (Hedgehog+) and run, or push a branch — GitHub Actions builds an installable debug APK for every push.

---

## 🙏 Acknowledgements

- **Enhanced by W9JES and the Radio & Electronics Hub team** — the sBitx 64-bit fork and browser-mic SSB transmit that zBitx inherits.
- **VU3UBP** — original sBitx Remote app this is built on.
- **Ashhar Farhan, VU2ESE** — creator of the sBitx and its open-source software.

---

## ⚖️ Disclaimer & license

Use at your own risk. **You are the control operator when transmitting remotely** — make sure your licence and local rules allow remote operation, and verify frequency, mode and power before transmitting.

MIT License — see [LICENSE](LICENSE).
