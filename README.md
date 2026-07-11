# 📻 sBitx Remote

**A native Android app for operating your sBitx transceiver from anywhere in the world.**

Full remote control over the internet — tune the bands, work SSB with your phone's microphone, and listen to live receiver audio, whether you're in the next room or another country.

Created by **VU3UBP** 🇮🇳

[![Download APK](https://img.shields.io/github/v/release/vis4573/sbitx-remote?label=%F0%9F%93%A5%20Download%20APK&style=for-the-badge&color=2ea44f)](https://github.com/vis4573/sbitx-remote/releases/latest/download/sbitx-remote.apk)

**[⬇️ Download the latest APK](https://github.com/vis4573/sbitx-remote/releases/latest/download/sbitx-remote.apk)** — or browse all versions on the [Releases page](https://github.com/vis4573/sbitx-remote/releases).

---

## ✨ Features

- 🎙️ **Remote SSB voice** — hold-to-talk PTT streams your phone's microphone straight into the radio's TX chain
- 🔊 **Live RX audio** — low-latency receiver audio with a jitter buffer tuned for mobile networks
- 📶 **Frequency control** — direct entry, ±100 Hz / ±1 kHz nudge buttons, big monospace readout
- 🎛️ **Full radio control** — mode (LSB/USB/CW/AM/FT8), band dropdown (80m–10m), tap-a-digit tuning with rotary knob, mic gain, volume, drive (TX power), bandwidth, S-meter
- 📟 **FT8 operating panel** — live color-coded decodes streamed from the radio (decoding runs on the sBitx), tap a station to grab their call, one-tap standard messages (CQ with custom modifier like DX/POTA, Call, Report, RR73, 73)
- 🌐 **Two connection profiles** — one tap to switch between your home LAN and Tailscale over the internet, with all settings remembered
- 🔒 **TLS support** — works with the firmware's HTTPS (port 8443) out of the box, self-signed certificate included
- 📱 **Background-safe** — a foreground service keeps the QSO alive when the screen turns off

---

## 📋 Requirements

| Component | Requirement |
|---|---|
| Radio | sBitx (v2/v3/DE) with Raspberry Pi |
| **Firmware** | **[drexjj/sbitx](https://github.com/drexjj/sbitx) 64-bit, v5.0 or newer** — required |
| Phone | Android 8.0+ |
| Remote access | [Tailscale](https://tailscale.com) (free tier is plenty) |

> ⚠️ **The official (afarhan) firmware will not work for voice.** Remote SSB transmit from a browser/phone microphone was introduced in the drexjj fork at v5.0. The stock firmware only supports FT8/CW remotely. Flash the drexjj 64-bit image first — and back up your `sbitx/data` and `sbitx/web` folders before upgrading.

---

## 🚀 Quick Start

### 1. Flash the firmware
Install the [drexjj/sbitx](https://github.com/drexjj/sbitx) 64-bit image (v5.0+) on your radio's Raspberry Pi. See their wiki for the full upgrade guide.

### 2. Set your PIN
On the radio, press **SET** and set/confirm the web access PIN. Verify the web UI works locally first: browse to `https://sbitx.local:8443` from a laptop on the same network, accept the certificate warning, and log in.

### 3. Set up Tailscale on the sBitx

**Easiest: the one-time setup script** (in `scripts/` of this repo). It installs Tailscale, joins your tailnet without any browser login on the radio, enables Tailscale SSH for remote recovery, survives reboots, and fixes WiFi power-save (a common cause of remote Pis dropping offline).

1. Generate an auth key at `https://login.tailscale.com/admin/settings/keys` (single-use, tick "Pre-approved" if shown)
2. Copy the script to the radio and run it:

```bash
# from your laptop, in this repo's folder:
scp scripts/sbitx-remote-setup.sh pi@sbitx.local:/home/pi/

ssh pi@sbitx.local
sudo bash /home/pi/sbitx-remote-setup.sh tskey-auth-XXXXXXXXXXXX
```

3. Note the Tailscale IP it prints (`100.x.y.z`) — that goes in the app
4. **Final step, once, from any browser:** `https://login.tailscale.com/admin/machines` → **sbitx** → ⋯ → **Disable key expiry**. This makes the setup permanent; without it the radio drops off the tailnet after ~180 days.

<details>
<summary>Manual setup (if you prefer doing it by hand)</summary>

SSH into the radio's Raspberry Pi (or open a terminal on it directly):

```bash
ssh pi@sbitx.local        # default password is hf12345 — change it!
```

Install and start Tailscale:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

The `tailscale up` command prints a login URL — open it in any browser and sign in (Google/Microsoft/GitHub accounts all work). The radio is now part of your private "tailnet."

Get the radio's Tailscale address:

```bash
tailscale ip -4        # e.g. 100.101.102.103
```

Optional but recommended — make Tailscale start on every boot:

```bash
sudo systemctl enable --now tailscaled
```
</details>

### 4. Set up Tailscale on your phone
Install the **Tailscale** app from the Play Store, sign in with the **same account**, and flip the VPN toggle on. You should see your sBitx listed as a device.

### 5. Install sBitx Remote
Download the latest APK directly on your phone: **[sbitx-remote.apk](https://github.com/vis4573/sbitx-remote/releases/latest/download/sbitx-remote.apk)** (or via the [Releases](../../releases) page) and install it (allow "install unknown apps" if prompted).

### 6. Connect and operate
- **At home:** choose **Local network**, enter the radio's LAN IP, port `8443`, TLS on, your PIN → Connect
- **Anywhere else:** turn on the Tailscale app, choose **Tailscale (internet)**, enter the radio's `100.x.y.z` address → Connect

Hold the big green button to talk. Release to listen. That's it. 🎉

---

## 🔧 How it works

The app speaks the drexjj firmware's native WebSocket protocol directly — the same one its web interface uses:

- A single WebSocket (`wss://<radio>:8443/websocket`) carries everything: login, control commands, telemetry, and audio in both directions
- Control uses the sBitx text command set (`freq=`, `mode=`, `mic=`, `tx`/`rx`, …)
- **RX audio** arrives as binary PCM frames (16-bit, 16 kHz mono) and plays through `AudioTrack`
- **TX voice** is captured at 8 kHz from the phone mic and streamed in 50 ms chunks; the firmware upsamples it 12× into the 96 kHz SSB transmit chain
- Tailscale provides an encrypted WireGuard tunnel, so the radio is never exposed to the open internet

```
┌─────────────┐   WireGuard    ┌──────────────┐   WebSocket   ┌────────────┐
│   Android   │◄──(Tailscale)──►│ Raspberry Pi │◄────(TLS)────►│  sBitx SDR │
│  this app   │    internet    │ drexjj v5.x  │   localhost   │  hardware  │
└─────────────┘                └──────────────┘               └────────────┘
```

---

## 🛠️ Building from source

Open the project in Android Studio (Hedgehog+) and run, or let GitHub Actions build it — every push produces an installable debug APK on the [Releases](../../releases) page.

```
app/src/main/java/com/sbitx/remote/
  net/SbitxClient.kt       WebSocket protocol client
  audio/RxAudioPlayer.kt   16 kHz RX playback
  audio/MicStreamer.kt     8 kHz PTT mic capture
  service/RadioService.kt  Foreground service
  ui/MainActivity.kt       Jetpack Compose UI
```

---

## 🗺️ Roadmap

- [ ] Spectrum / waterfall display
- [x] FT8 console with standard messages
- [ ] CW keyboard console
- [ ] Logbook viewer
- [ ] Auto-reconnect with backoff
- [ ] VFO A/B, RIT, split controls in UI

---

## 🙏 Acknowledgements

This app stands on the shoulders of some remarkable open-source work:

- **[Ashhar Farhan, VU2ESE](https://github.com/afarhan/sbitx)** — creator of the sBitx and the original open-source radio software that started it all. The sBitx's "hackable HF SDR" philosophy is what makes projects like this possible.
- **[W9JES and the drexjj/sbitx team](https://github.com/drexjj/sbitx)** (KJ5DTK, KB2ML, and contributors) — for the outstanding 64-bit fork, and especially for adding browser-microphone SSB transmit in v5.0, the feature this entire app is built around.
- Inspired by the **Radioberry remote project by VU3ZOF**.

---

## ⚖️ Disclaimer, license & operating note

**⚠️ Use this app at your own risk.** This is experimental software under active testing. The author takes no responsibility for any damage to your radio, amplifier, or other equipment, unintended transmissions, or any other consequences of using this app. Always verify your radio's behaviour (frequency, mode, power) before and during remote operation.

Open source under the [MIT License](LICENSE) — use, modify, and share freely.

**You are the control operator when transmitting remotely.** Ensure your amateur radio license privileges and local regulations permit remote operation of your station.

---

*73 de VU3UBP* 📡
