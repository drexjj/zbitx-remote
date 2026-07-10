#!/bin/bash
#=============================================================================
# sBitx one-time remote setup script — by VU3UBP
#
# Run ONCE on the sBitx Raspberry Pi. After this, the radio needs no further
# touching for remote access: it joins your Tailscale network on every boot
# and stays reachable from the sBitx Remote Android app.
#
# Usage:
#   1. Get an auth key from https://login.tailscale.com/admin/settings/keys
#      (single-use is fine; tick "Pre-approved" if your tailnet uses approval)
#   2. Copy this script to the radio and run:
#        sudo bash sbitx-remote-setup.sh tskey-auth-XXXXXXXXXXXX
#   3. IMPORTANT: afterwards, in https://login.tailscale.com/admin/machines
#      open the sbitx device menu and choose "Disable key expiry".
#      That is the only manual step, and it makes the setup permanent.
#=============================================================================
set -e

AUTHKEY="$1"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run with sudo:  sudo bash $0 tskey-auth-..."
    exit 1
fi

if [ -z "$AUTHKEY" ]; then
    echo "Usage: sudo bash $0 tskey-auth-XXXXXXXXXXXX"
    echo "Generate a key at: https://login.tailscale.com/admin/settings/keys"
    exit 1
fi

echo "==> [1/6] Installing Tailscale (if not already installed)..."
if ! command -v tailscale >/dev/null 2>&1; then
    curl -fsSL https://tailscale.com/install.sh | sh
else
    echo "    Tailscale already installed, skipping."
fi

echo "==> [2/6] Joining your tailnet (with Tailscale SSH enabled for remote recovery)..."
tailscale up --authkey "$AUTHKEY" --ssh --hostname sbitx

echo "==> [3/6] Enabling Tailscale on every boot..."
systemctl enable --now tailscaled

echo "==> [4/6] Disabling WiFi power management (prevents the radio dropping off WiFi)..."
# WiFi power-save is a common cause of a remote Pi becoming unreachable.
if iw dev wlan0 get power_save 2>/dev/null | grep -q on; then
    iw dev wlan0 set power_save off || true
fi
# Persist across reboots
cat > /etc/systemd/system/wifi-powersave-off.service << 'EOF'
[Unit]
Description=Disable WiFi power save
After=network.target

[Service]
Type=oneshot
ExecStart=/usr/sbin/iw dev wlan0 set power_save off
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl enable wifi-powersave-off.service 2>/dev/null || true

echo "==> [5/6] Basic safety check: default SSH password..."
if grep -q '^pi:' /etc/passwd; then
    echo "    NOTE: if the 'pi' user still has the default password (hf12345),"
    echo "    change it now with:  passwd pi"
fi

echo "==> [6/6] Done! Radio details:"
echo "------------------------------------------------------------"
echo "  Tailscale IPv4 : $(tailscale ip -4 2>/dev/null | head -1)"
echo "  Hostname       : sbitx (MagicDNS: sbitx.<your-tailnet>.ts.net)"
echo "  Web interface  : https://$(tailscale ip -4 2>/dev/null | head -1):8443"
echo "  App settings   : Tailscale (internet) -> IP above, port 8443, TLS on"
echo "------------------------------------------------------------"
echo ""
echo "  FINAL STEP (one time, from any browser):"
echo "  https://login.tailscale.com/admin/machines"
echo "    -> sbitx -> ... menu -> Disable key expiry"
echo ""
echo "  After that, the radio auto-joins your tailnet on every boot."
echo "  Recovery access from anywhere: 'ssh pi@sbitx' via Tailscale SSH."
echo ""
echo "  73 de VU3UBP"
