#!/data/data/com.termux/files/usr/bin/bash
# Makes Termux sshd reachable over Tailscale, then verifies it.
set -u
DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
[ -z "$DIR" ] && DIR="$(find /storage /mnt/sdcard /sdcard "$HOME" -maxdepth 6 -name fix_sshd.sh 2>/dev/null | head -1 | xargs -r dirname)"
echo "== folder: $DIR"

# adb first, so the phone is ready the moment I'm in.
if ! command -v adb >/dev/null; then
  echo "== installing android-tools"
  pkg install -y android-tools >/dev/null 2>&1 || pkg install -y adb >/dev/null 2>&1
fi
command -v adb >/dev/null && echo "== adb: $(command -v adb)" || echo "== WARNING adb missing"

mkdir -p "$HOME/.ssh"
# Minimal config: no AddressFamily / ListenAddress restriction, so sshd
# binds every interface (incl. the Tailscale tun) instead of loopback only.
cat > "$HOME/.ssh/sshd_config" <<'EOF'
Port 8022
ListenAddress 0.0.0.0
ListenAddress ::
PasswordAuthentication yes
PubkeyAuthentication yes
PermitRootLogin no
UsePAM no
EOF
chmod 600 "$HOME/.ssh/sshd_config"

echo "== restarting sshd"
pkill -f 'sshd -D' 2>/dev/null
pkill -x sshd 2>/dev/null
sleep 1
setsid sshd -D -p 8022 -o ListenAddress=0.0.0.0 >/dev/null 2>&1 &
sleep 3

echo "== sshd processes"
pgrep -a -f sshd 2>/dev/null | head -5 || echo "(pgrep found none)"

echo "== bind check (8022 = hex 1F6E)"
for f in /proc/net/tcp /proc/net/tcp6; do
  [ -r "$f" ] || continue
  awk -v F="$f" 'NR>1 {split($2,a,":"); if (a[2]=="1F6E") print F": local="a[1]}' "$f" 2>/dev/null
done
echo "== done - keep Termux open; sshd stays running"
