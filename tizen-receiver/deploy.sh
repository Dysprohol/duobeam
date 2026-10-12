#!/usr/bin/env bash
# Packages the DuoBeam receiver and installs it on a Samsung TV in Developer Mode (Tizen 5.5+, tested on Tizen 8).
#
#   ./deploy.sh <tv-ip[:sdb-port]> <tizen-certificate-profile>
#
# Needs Tizen Studio CLI. Set TIZEN / SDB if the tools are not on PATH, e.g. on Windows (Git Bash):
#   TIZEN=/c/tizen-studio/tools/ide/bin/tizen.bat SDB=/c/tizen-studio/tools/sdb.exe ./deploy.sh 192.168.1.50 MyProfile
# The certificate profile is the Samsung TV profile you created in Tizen Studio's Certificate Manager.
set -e
cd "$(dirname "$0")"
TV="${1:?usage: ./deploy.sh <tv-ip[:port]> <certificate-profile>}"
PROFILE="${2:?usage: ./deploy.sh <tv-ip[:port]> <certificate-profile>}"
case "$TV" in *:*) ;; *) TV="$TV:26101" ;; esac
TIZEN="${TIZEN:-tizen}"
SDB="${SDB:-sdb}"
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

OUT=build/wgt
rm -rf build && mkdir -p "$OUT"
cp -r config.xml icon.png index.html js service img "$OUT/"
"$TIZEN" package -t wgt -s "$PROFILE" -- "$(winpath "$PWD/$OUT")"
"$SDB" connect "$TV"
"$TIZEN" install -n DuoBeam.wgt -s "$TV" -- "$(winpath "$PWD/$OUT")"
"$TIZEN" run -p DuoBeamRcv.DuoBeam -s "$TV"
echo "Installed. The TV shows the DuoBeam receiver; its address is shown on screen."
