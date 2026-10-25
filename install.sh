#!/usr/bin/env bash
# Glasshouse installer: makes Glasshouse your TV's Home screen over Wi-Fi, safely, and puts the look on it.
#
#   ./install.sh 192.168.1.50                       build from source (needs JDK 17 and the Android SDK)
#   APK=glasshouse.apk ./install.sh 192.168.1.50    use a prebuilt APK (needs only adb and Python 3)
#
# What it does, in this order (the order is what keeps a Google TV out of a boot loop):
#   1. connects over ADB and checks the TV (Android 8 or newer)
#   2. on Google TV, checks that the setup wizard does not own the Wi-Fi network you are on, then turns the
#      wizard off while the stock launcher is still Home (the wizard's recovery screen crash-loops otherwise)
#   3. installs Glasshouse, opens it, turns the stock launcher off and makes Glasshouse Home, and puts the
#      stock launcher back if Home does not land on Glasshouse
#   4. turns on the remote-button helpers, lets Now Playing see what is playing, and pushes the theme
# Undo everything with ./uninstall.sh.
set -uo pipefail
cd "$(dirname "$0")"

PKG=com.gathin.tvhome
WIZARD=com.google.android.tungsten.setupwraith
STOCK_LAUNCHERS="com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher com.mitv.tvhome"
THEME=${THEME:-themes/glasshouse}

say()  { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

TV=${1:-${TV_HOST:-}}
[ -n "$TV" ] || read -rp "Your TV's IP address (Settings → Network & Internet → your network): " TV
TV=${TV%:5555}
SERIAL="$TV:5555"
STATE=".glasshouse-state-$TV"

# ---- 0. tools -------------------------------------------------------------------------------------
say "Checking your computer"
command -v adb >/dev/null || die "adb is missing. macOS: brew install android-platform-tools · Linux: apt install adb"
command -v python3 >/dev/null || die "Python 3 is missing (macOS: brew install python)"
if [ ! -x tools/venv/bin/python ]; then
  python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt \
    || die "Could not set up the Python tools in tools/venv"
fi
ok "adb and Python tools ready"

# ---- 1. the TV ------------------------------------------------------------------------------------
say "Connecting to $TV"
adb connect "$SERIAL" >/dev/null 2>&1
for _ in $(seq 1 30); do
  state=$(adb -s "$SERIAL" get-state 2>/dev/null || true)
  [ "$state" = device ] && break
  [ "$_" = 1 ] && warn "On the TV, accept \"Allow USB debugging?\" (tick Always allow). Waiting…"
  sleep 1
  adb connect "$SERIAL" >/dev/null 2>&1
done
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = device ] || die "Can't reach $SERIAL. Is the TV on, on the same Wi-Fi as this computer, with USB debugging on (Settings → System → Developer options)?"
A() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }
SDK=$(A getprop ro.build.version.sdk)
[ "${SDK:-0}" -ge 26 ] || die "This TV runs Android SDK ${SDK:-?}; Glasshouse needs Android 8 (SDK 26) or newer."
ok "$(A getprop ro.product.model) · Android $(A getprop ro.build.version.release)"
home_now() { A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; }
HOME_BEFORE=$(home_now)
ok "Home is $HOME_BEFORE"
touch "$STATE"

# ---- 2. Google TV's setup wizard --------------------------------------------------------------------
if A pm list packages -e | grep -qx "package:$WIZARD" \
   && A cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q "$WIZARD"; then
  say "Google TV's setup wizard also answers Home; turning it off safely"
  # The wizard can own the Wi-Fi network saved during setup: turning it off then can drop Wi-Fi, and with it this
  # connection. Check the network the TV is on right now.
  owner=$(A dumpsys wifi | awk '/Currently Connected: true/ {getline; print; exit}' | grep -o 'cname=[^ ]*' || true)
  if printf '%s' "$owner" | grep -q "$WIZARD"; then
    die "Your Wi-Fi network was saved by the setup wizard, so turning it off could drop Wi-Fi.
   On the TV: Settings → Network & Internet → your network → Forget, then connect to it again with the password.
   (Or plug in Ethernet for the install.) Then run this again."
  fi
  ok "Wi-Fi belongs to the system ($owner)"
  A pm disable-user --user 0 "$WIZARD" >/dev/null
  grep -qx "wizard=$WIZARD" "$STATE" || echo "wizard=$WIZARD" >> "$STATE"
  ok "Setup wizard off (it comes back with ./uninstall.sh, which you should run before any factory reset)"
fi

# ---- 3. Glasshouse as Home --------------------------------------------------------------------------
say "Installing Glasshouse"
STOCK=""
for k in $STOCK_LAUNCHERS; do case "$(home_now)" in "$k"/*) STOCK=$k ;; esac; done
if [ -n "${APK:-}" ]; then
  [ -f "$APK" ] || die "No APK at $APK"
  adb -s "$SERIAL" install -r "$APK" >/dev/null || die "The TV refused the APK (adb install failed)"
  A pm grant $PKG android.permission.ACCESS_FINE_LOCATION >/dev/null
  A cmd package compile -m speed -f $PKG >/dev/null
  A am start -W -n $PKG/.MainActivity >/dev/null      # Glasshouse up before the stock launcher goes
  if [ -n "$STOCK" ]; then
    A pm disable-user --user 0 "$STOCK" >/dev/null
    grep -qx "stock=$STOCK" "$STATE" || echo "stock=$STOCK" >> "$STATE"
  fi
  A cmd package set-home-activity --user 0 $PKG/.MainActivity >/dev/null   # last: Android drops it on any HOME change
  case "$(home_now)" in
    $PKG/*) ok "Home is Glasshouse" ;;
    *) [ -n "$STOCK" ] && A pm enable --user 0 "$STOCK" >/dev/null
       die "Home did not land on Glasshouse (it is $(home_now)); the stock launcher is back. Nothing else changed." ;;
  esac
  enable_service() {
    svc=$PKG/.$1
    A "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1" >/dev/null
  }
  enable_service HomeKeyService
  enable_service InputKeyService
else
  [ -x launcher/build-and-install.sh ] || die "launcher/build-and-install.sh is missing"
  [ -n "$STOCK" ] && { grep -qx "stock=$STOCK" "$STATE" || echo "stock=$STOCK" >> "$STATE"; }
  HOME_KEY_SERVICE=1 INPUT_KEY_SERVICE=1 TV_HOST="$TV" launcher/build-and-install.sh || die "The build or install failed (see above)"
fi
ok "Remote buttons on (Settings opens the Quick panel; colour keys open apps)"

# ---- 4. Now Playing and the look --------------------------------------------------------------------
if A cmd notification allow_listener $PKG/$PKG.MediaListener 2>&1 | grep -qi "error\|denied\|exception"; then
  warn "The TV refused Now Playing's permission; everything else works without it"
else
  ok "Now Playing can see what is playing"
fi
say "Applying the $(basename "$THEME") look"
TV_HOST="$TV" tools/theme.sh "$THEME" >/dev/null || die "The theme push failed: run TV_HOST=$TV tools/theme.sh $THEME to see why"
ok "Done"
echo
say "Press Home on your remote. Welcome to Glasshouse."
echo "  Change the look later:   TV_HOST=$TV tools/theme.sh themes/<name>"
echo "  Put the TV back:         ./uninstall.sh $TV"
