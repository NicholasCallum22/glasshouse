#!/usr/bin/env bash
# Puts the TV back the way install.sh found it: the stock launcher as Home, then Google TV's setup wizard back on
# (in that order, so the wizard never becomes Home on its own). Run it before any factory reset.
#
#   ./uninstall.sh 192.168.1.50            stock Home back, Glasshouse stays installed
#   REMOVE=1 ./uninstall.sh 192.168.1.50   and uninstall Glasshouse too
set -uo pipefail
cd "$(dirname "$0")"
PKG=com.gathin.tvhome
STOCK_LAUNCHERS="com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher com.mitv.tvhome"

TV=${1:-${TV_HOST:-}}
[ -n "$TV" ] || read -rp "Your TV's IP address: " TV
TV=${TV%:5555}
SERIAL="$TV:5555"
STATE=".glasshouse-state-$TV"
adb connect "$SERIAL" >/dev/null 2>&1
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = device ] || { echo "Can't reach $SERIAL (TV on? same Wi-Fi? USB debugging on?)" >&2; exit 1; }
A() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

# The launcher install.sh turned off, else every known stock launcher that is installed but off.
stock=$(grep '^stock=' "$STATE" 2>/dev/null | head -1 | cut -d= -f2)
if [ -z "$stock" ]; then
  for k in $STOCK_LAUNCHERS; do A pm list packages -d | grep -qx "package:$k" && { stock=$k; break; }; done
fi
if [ -n "$stock" ]; then
  A pm enable --user 0 "$stock" >/dev/null && echo "✓ $stock back on"
  comp=$(A cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -o "$stock/[^ ]*" | head -1)
  [ -n "$comp" ] && A cmd package set-home-activity --user 0 "$comp" >/dev/null && echo "✓ Home is $comp"
fi
# The wizard only once the stock launcher, which outranks it, is Home again.
wizard=$(grep '^wizard=' "$STATE" 2>/dev/null | head -1 | cut -d= -f2)
[ -n "$wizard" ] && A pm enable --user 0 "$wizard" >/dev/null && echo "✓ $wizard back on"
if [ "${REMOVE:-0}" = 1 ]; then
  adb -s "$SERIAL" uninstall $PKG >/dev/null && echo "✓ Glasshouse uninstalled"
fi
rm -f "$STATE"
echo "Done. Press Home on the remote."
