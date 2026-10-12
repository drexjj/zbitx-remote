# zBitx Remote — Installation & Setup Guide

This guide walks you through installing the **zBitx Remote** Android app and connecting it to your zBitx transceiver: first on your home network, then (optionally) from anywhere over the internet with Tailscale.

It takes about 15 minutes for home use, plus another 15 minutes if you want internet access.

---

## Contents

1. [What you need](#1-what-you-need)
2. [Prepare the radio](#2-prepare-the-radio)
3. [Check the radio's web page](#3-check-the-radios-web-page)
4. [Install the app](#4-install-the-app)
5. [Connect on your home network](#5-connect-on-your-home-network)
6. [Set your callsign and grid](#6-set-your-callsign-and-grid)
7. [A tour of the screen](#7-a-tour-of-the-screen)
8. [Operating each mode](#8-operating-each-mode)
9. [Remote access over the internet (Tailscale)](#9-remote-access-over-the-internet-tailscale)
10. [Updating the app](#10-updating-the-app)
11. [Troubleshooting](#11-troubleshooting)
12. [Operating responsibly](#12-operating-responsibly)

---

## 1. What you need

| Item | Notes |
|---|---|
| A zBitx transceiver | Running the [drexjj/zbitx](https://github.com/drexjj/zbitx) software. **v5.14s or newer** is recommended (the FT8 "CQ only" filter is shared with the radio from that version on). |
| An Android phone or tablet | Android 8.0 or newer. |
| Wi-Fi | The radio and phone on the same network for home use. |
| A Tailscale account *(optional)* | Free, only needed to operate away from home. |

You'll also want the radio's **passkey**. This is the password that protects remote access. Section 2 shows where to find or set it.

---

## 2. Prepare the radio

### 2.1 Connect the radio to Wi-Fi

If your zBitx isn't on your Wi-Fi yet, use the front panel:

1. Tap **MENU → WIFI**.
2. Tap **SCAN**, pick your network, enter the Wi-Fi password and connect.

### 2.2 Set your callsign, grid and passkey

On the front panel, tap **MENU → SETUP** and fill in:

| Field | What to enter |
|---|---|
| **MY CALL** | Your callsign, e.g. `W1AW` |
| **MY GRID** | Your 4- or 6-character Maidenhead grid, e.g. `EM10` or `EM10DG` |
| **PASS KEY** | The password the app will use to log in (up to 10 characters) |

> **The passkey is case-sensitive.** `Radio1` and `RADIO1` are different. If you've never set one, the radio's default is `123`. Change it to something of your own, especially if you'll use the app over the internet.

You can also set the callsign and grid later from inside the app with the **Set** button (Section 6).

### 2.3 Find the radio's address

The app needs to know where the radio is on your network. Use either:

- **`zbitx.local`** — the radio's network name. This works on most home networks.
- **The radio's IP address**, e.g. `192.168.1.42`. You'll find it in your router's list of connected devices (look for `zbitx`). Use this if `zbitx.local` doesn't work.

---

## 3. Check the radio's web page

Before installing the app, confirm the radio's own web interface works. The app uses the same connection.

1. On a phone or computer on the same Wi-Fi, open a browser and go to:
   ```
   https://zbitx.local:8443
   ```
   (or `https://<radio-IP>:8443`)
2. You'll see a **security warning**. That's expected: the radio uses its own self-signed certificate. Choose **Advanced → Proceed / Accept the risk**.
3. Enter your passkey. You should see the radio's web interface with the waterfall running.

If this works, the app will work too. If it doesn't, see [Troubleshooting](#11-troubleshooting) before going further.

> **Close the web page afterwards.** zBitx allows **one remote session at a time**. If the web page stays logged in, it and the app will keep logging each other out.

---

## 4. Install the app

The app isn't on the Play Store. You install it directly from GitHub.

### 4.1 Download

On your phone, open this link:

**➡️ https://github.com/drexjj/zbitx-remote/releases/latest/download/zBitx-Remote.apk**

That link always gets the newest released version. Every version is also listed on the [Releases page](https://github.com/drexjj/zbitx-remote/releases), and the newest test build is under **Latest test build**.

### 4.2 Install

1. Open the downloaded `zBitx-Remote.apk` (from the download notification or your **Downloads** folder).
2. If Android says your browser **isn't allowed to install unknown apps**, tap **Settings**, turn on **Allow from this source**, then go back.
3. Tap **Install**.
4. If **Google Play Protect** warns that it doesn't recognise the app, tap **More details → Install anyway**. This happens with any app installed from outside the Play Store.

> **Had an older test version installed?** Builds made before v1.0.3 were signed differently, so Android shows **"App not installed"**. Uninstall the old app once, then install again. From v1.0.3 on, new versions install over the old one.

### 4.3 Allow permissions

When the app first opens it asks for:

- **Microphone**: needed to talk on SSB from your phone. Choose **While using the app** or **Allow**.
- **Notifications** (Android 13+): shows the "connected" notification that keeps the radio session alive when the screen is off.

---

## 5. Connect on your home network

On the app's connect screen:

1. Tap **Local network**.
2. **Radio address**: `zbitx.local` (or the radio's IP address from Section 2.3).
3. **Port**: `8443`.
4. **Passkey**: the passkey from Section 2.2 (case-sensitive).
5. Leave **Use TLS** ticked. zBitx requires it for remote clients.
6. Tap **Connect**.

After a moment the main radio screen appears and you'll hear receiver audio. The app remembers these settings for next time.

---

## 6. Set your callsign and grid

Tap **Set** at the top of the screen, enter **My callsign** and **My grid square**, and tap **Save**.

They're saved **on the radio** (the same settings as the front panel's MENU → SETUP), so FT8, the CW/FT8 macros and the logbook all use them. If you already set them on the front panel, they'll already be filled in.

---

## 7. A tour of the screen

**Header (top row)**

| Item | What it does |
|---|---|
| Version | Shown under *zBitx Remote*, e.g. `v1.0.4 (build 112)`. Quote this when reporting problems. |
| Clock | The radio's UTC time. |
| **Set** | Your callsign and grid (Section 6). |
| **Log** | Opens the radio's logbook. |
| **Disconnect** | Ends the session (unkeys the radio first if it's transmitting). |

**Frequency and meters**

- **Frequency digits**: tap a digit to choose which one the knob changes (the step size is shown under the meter).
- **VFO A/B**: switches VFO.
- **Meter**: S-meter on receive; forward power (W) and SWR while transmitting. SWR turns red at 3:1 or above.

**Controls (scroll down)**

- **kHz box + Go**: type a frequency, e.g. `7074`.
- **Band / Mode**: 80 m–10 m (including 60 m), and USB, LSB, AM, CW, CWR, FT8, DIGI.
- **Waterfall**: **tap** to tune to a signal (in FT8, tapping sets your transmit tone). **Drag** left or right to tune. The blue shaded area is your receive filter. **Span** buttons zoom the waterfall.
- **Sliders**: volume, IF gain, TX drive, bandwidth, and per mode the phone mic level, compressor, CW speed and pitch.
- **Toggles**: ANR, DSP, NOTCH (noise reduction and notch), AGC, SPLIT, LOCK (VFO lock), and **TUNE** (sends a short carrier at the radio's tune power for an antenna tuner).

**Bottom**

- **Tuning knob**: drag your finger around it in a circle to tune.
- **PTT button** (phone modes only). See below.

**Rotation:** turn the phone sideways for a landscape layout with the knob and PTT on the right. The app follows your phone's auto-rotate setting.

---

## 8. Operating each mode

### SSB / AM (voice)

- **Hold** the green **HOLD TO TALK** button to transmit and **release** to listen. It turns **orange** while the radio is switching and **red** only once the radio confirms it's on the air.
- Speak into your **phone's microphone**. Receive audio is muted while you talk so the speaker can't feed back.
- Set your level with the **Phone mic** slider. The radio's own MIC control doesn't affect phone audio. Watch the level bar on the PTT button while talking: aim for mostly white, with only an occasional yellow flash on peaks.
- **Compressor** adds punch. Start around 3–5.

### CW / CWR

- **Decoded text** scrolls in the CW panel. Your sent text shows in orange.
- **Type to send**: type a message and tap **Send**. The radio's keyer sends it at the **WPM** you set. **Sending:** shows what is still queued.
- **Logger row**: fill in **Call** (Sent auto-fills `599`), **Rcvd**, and **My exch** if you're in a contest, then tap **Log QSO**. **Wipe** clears it for the next contact.
- **Macros (F1–F12)**: tap a key to send it, or **long-press** to preview its text first. Macros fill in your call, their call (from the logger), the report and so on automatically.
- **Stop** aborts whatever is being sent.

### FT8

- Decodes appear in the list, color-coded like the radio's screen.
- **Tap a decode to answer it.** The radio runs the whole QSO itself (reports, RR73, 73), just like tapping a decode in the zBitx web interface.
- **Auto**: with this on, the radio also answers stations that call *you* without you tapping anything.
- **All / CQ only**: **CQ only** hides everything except stations calling CQ, plus anything to or from you. It's the same setting as the radio's web interface, so changing it in one place changes it in both.
- **Long-press a decode** to put that station in the logger's **Call** field without answering, ready for a macro.
- **Logger row**: fills in as the QSO progresses (Call, Sent, Rcvd, Grid). The radio logs the QSO by itself when it finishes (RR73/73). Use **Log QSO** for a contact the radio didn't complete itself.
- **Macros (F1–F12)**: CQ, Call, report, RR73, 73 and so on, from the radio's FT8 macro file. **Free FT8 message** lets you send anything else.

### Macro files

Each mode's macro panel has a **File** picker listing the macro files stored on the radio (`.mc` files in `~/sbitx/web`). CW/CWR and FT8 each remember their own choice (defaults `CW1` and `FT8`). Add or edit `.mc` files on the radio and they appear in the list.

### Logbook

Tap **Log** at the top. The newest 50 QSOs show first. Search by the start of a callsign, or scroll down and tap **Load older QSOs**. QSOs logged while it's open appear at the top automatically.

---

## 9. Remote access over the internet (Tailscale)

[Tailscale](https://tailscale.com) creates a private, encrypted network between your phone and the radio. The radio is **never exposed to the open internet**, and there's no router port-forwarding to set up.

> ⚠️ **Don't port-forward 8443 on your router** to reach the radio. Use Tailscale instead.

### 9.1 Create a Tailscale account

Sign up free at https://tailscale.com with a Google, Microsoft or GitHub account.

### 9.2 Generate an auth key

1. Go to https://login.tailscale.com/admin/settings/keys
2. Click **Generate auth key…**. A single-use key is fine; tick **Pre-approved** if you see it.
3. Copy the key. It starts with `tskey-auth-…`.

### 9.3 Run the setup script on the radio

The script installs Tailscale on the radio's Raspberry Pi, joins it to your Tailscale network, makes it start on every boot, and turns off Wi-Fi power saving (a common reason a remote Pi drops offline).

From a computer on the same network as the radio (Windows PowerShell, macOS or Linux terminal). On Windows, type `curl.exe` instead of `curl`:

```bash
# 1. Download the script from this repository
curl -LO https://raw.githubusercontent.com/drexjj/zbitx-remote/main/scripts/sbitx-remote-setup.sh

# 2. Copy it to the radio
scp sbitx-remote-setup.sh pi@zbitx.local:/home/pi/

# 3. Log in to the radio and run it with your auth key
ssh pi@zbitx.local
sudo bash /home/pi/sbitx-remote-setup.sh tskey-auth-XXXXXXXXXXXX
```

When it finishes, it prints the radio's **Tailscale IP address** (`100.x.y.z`). **Write it down.**

> The script names the radio **`sbitx`** in your Tailscale device list. That's just a label; it works the same for a zBitx.

### 9.4 Disable key expiry (one time)

Otherwise the radio drops off Tailscale after about 6 months.

1. Go to https://login.tailscale.com/admin/machines
2. Find the radio (**sbitx**), click the **⋯** menu → **Disable key expiry**.

### 9.5 Set up your phone

1. Install **Tailscale** from the Play Store.
2. Sign in with the **same account** and turn the VPN switch **on**.
3. You should see the radio (`sbitx`) in the device list.

### 9.6 Connect from anywhere

On the app's connect screen:

1. Tap **Tailscale (internet)**.
2. **Tailscale IP**: the `100.x.y.z` address from step 9.3.
3. **Port** `8443`, **TLS** on, your **passkey**, then **Connect**.

The app keeps your home and Tailscale settings separately, so switching is one tap. If the connection drops (switching from Wi-Fi to mobile data, a tunnel), the app shows an orange **Reconnecting** bar and reconnects by itself. If the link dropped while you were transmitting, the radio is unkeyed as soon as it reconnects.

**Tip:** remote operation uses about **2 MB per minute** (roughly 120 MB per hour) of mobile data, mostly receiver audio. Keep an eye on it on a limited data plan.

---

## 10. Updating the app

Download the new APK with the same link as before:

**https://github.com/drexjj/zbitx-remote/releases/latest/download/zBitx-Remote.apk**

Open it and tap **Update**. It installs over the existing app and keeps your settings. Check the version under the title to confirm.

---

## 11. Troubleshooting

| Problem | What to try |
|---|---|
| **"Login failed – check the passkey"** | The passkey is case-sensitive. Check it on the front panel under **MENU → SETUP → PASS KEY**. |
| **"Connection failed" at home** | Make sure the phone is on the **same Wi-Fi** as the radio (not mobile data). Try the radio's **IP address** instead of `zbitx.local`. Check the port is `8443` and **TLS is ticked**. Confirm `https://zbitx.local:8443` opens in a browser (Section 3). |
| **"The radio redirected to HTTPS"** | Turn **Use TLS** on and use port **8443**. |
| **"Another device logged in to the radio"** | The radio allows one remote session. Close the radio's web page on other devices, then tap **Reconnect**. |
| **Can't connect over Tailscale** | Make sure the Tailscale app on the phone is **on** (VPN key icon in the status bar) and the radio shows as connected in the Tailscale app. Use the `100.x.y.z` address, not `zbitx.local`. |
| **No receive audio** | Check the app's **Volume** slider and your phone's media volume. Make sure the phone isn't in silent/Do Not Disturb mode for media. |
| **My transmit audio is quiet or distorted** | Adjust the **Phone mic** slider (the radio's MIC setting doesn't apply to phone audio). Add a little **Compressor**. Hold the phone 5–10 cm from your mouth. |
| **"Phone mic unavailable"** | Give the app microphone permission: Android **Settings → Apps → zBitx Remote → Permissions → Microphone → Allow**. |
| **FT8 says to set your callsign** | Tap **Set** and enter your callsign and grid (Section 6). |
| **"App not installed" when updating** | You have a build from before v1.0.3. Uninstall it once, then install the new APK. |
| **The session drops when the screen turns off** | Allow notifications for the app, and set Android **Settings → Apps → zBitx Remote → Battery** to **Unrestricted**. |
| **Something else** | Note the version under the title and [open an issue](https://github.com/drexjj/zbitx-remote/issues) describing what happened. |

---

## 12. Operating responsibly

- **You are the control operator** whenever you transmit remotely. Make sure your licence and your local regulations allow remote operation.
- Check the **frequency, mode and power** on the screen before you transmit.
- Keep the radio's **passkey private**, and use Tailscale rather than exposing the radio to the internet.
- Know how to stop transmitting: release PTT, tap **Stop** (CW/FT8), or tap **Disconnect**.

73 and enjoy operating your zBitx from anywhere!
