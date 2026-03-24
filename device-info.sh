#!/bin/sh
# Collects hardware and rendering diagnostics from a connected Android device.
# Run with: sh device-info.sh
# Requires adb in PATH and exactly one device connected (or set ANDROID_SERIAL).

set -e

ADB="adb"
PKG="de.codevoid.aNavMode"

# Check adb is available and a device is connected.
if ! command -v "$ADB" >/dev/null 2>&1; then
    echo "Error: adb not found in PATH." >&2
    exit 1
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "Error: no device connected (or device not authorised)." >&2
    exit 1
fi

DEVICE=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
DATE=$(date '+%Y-%m-%d %H:%M')

echo "========================================"
echo "aNavMode Device Hardware Report"
echo "Device: $DEVICE"
echo "Date:   $DATE"
echo "========================================"

# ---- CPU ------------------------------------------------------------------ #
echo ""
echo "--- CPU: /proc/cpuinfo ---"
"$ADB" shell cat /proc/cpuinfo | tr -d '\r'

echo ""
echo "--- CPU: core count and online state ---"
"$ADB" shell 'for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
    id=$(basename $cpu)
    online=$(cat $cpu/online 2>/dev/null || echo "n/a")
    maxfreq=$(cat $cpu/cpufreq/cpuinfo_max_freq 2>/dev/null || echo "n/a")
    curfreq=$(cat $cpu/cpufreq/scaling_cur_freq 2>/dev/null || echo "n/a")
    governor=$(cat $cpu/cpufreq/scaling_governor 2>/dev/null || echo "n/a")
    echo "$id  online=$online  max=${maxfreq}kHz  cur=${curfreq}kHz  gov=$governor"
done' | tr -d '\r'

echo ""
echo "--- CPU: cluster layout (related_cpus) ---"
"$ADB" shell 'for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
    id=$(basename $cpu)
    related=$(cat $cpu/cpufreq/related_cpus 2>/dev/null || echo "n/a")
    echo "$id  cluster=$related"
done' | tr -d '\r'

echo ""
echo "--- CPU: scheduler / nproc ---"
"$ADB" shell nproc 2>/dev/null | tr -d '\r' | { read n; echo "nproc=$n"; }

# ---- Memory --------------------------------------------------------------- #
echo ""
echo "--- Memory: /proc/meminfo ---"
"$ADB" shell cat /proc/meminfo | tr -d '\r'

echo ""
echo "--- Memory: app process (if running) ---"
"$ADB" shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' || echo "(app not running)"

# ---- GPU / Display -------------------------------------------------------- #
echo ""
echo "--- GPU: SurfaceFlinger GLES info ---"
"$ADB" shell dumpsys SurfaceFlinger 2>/dev/null | tr -d '\r' \
    | grep -iE "GLES|GL vendor|GL renderer|GL version|opengl|gpu" | head -20

echo ""
echo "--- Display: refresh rate and resolution ---"
"$ADB" shell dumpsys display 2>/dev/null | tr -d '\r' \
    | grep -iE "refresh|fps|width|height|density|mDisplayInfos|PhysicalDisplayInfo" | head -20

echo ""
echo "--- Display: wm size and density ---"
"$ADB" shell wm size 2>/dev/null | tr -d '\r'
"$ADB" shell wm density 2>/dev/null | tr -d '\r'

# ---- Frame timing --------------------------------------------------------- #
echo ""
echo "--- Frame timing: gfxinfo summary (requires 'Profile GPU rendering' in Dev Options) ---"
"$ADB" shell dumpsys gfxinfo "$PKG" 2>/dev/null | tr -d '\r' || echo "(app not running)"

echo ""
echo "--- Frame timing: gfxinfo framestats (last 120 frames, raw CSV) ---"
# Reset counters first so we get fresh data, then collect immediately.
# For best results run this script while the map is actively panning.
"$ADB" shell dumpsys gfxinfo "$PKG" reset 2>/dev/null | tr -d '\r'
"$ADB" shell dumpsys gfxinfo "$PKG" framestats 2>/dev/null | tr -d '\r' || echo "(app not running)"

# ---- Android / SoC metadata ----------------------------------------------- #
echo ""
echo "--- Build properties ---"
for prop in \
    ro.product.model \
    ro.product.board \
    ro.product.cpu.abi \
    ro.product.cpu.abilist \
    ro.hardware \
    ro.hardware.chipname \
    ro.soc.model \
    ro.soc.manufacturer \
    ro.build.version.release \
    ro.build.version.sdk \
    ro.build.description; do
    val=$("$ADB" shell getprop "$prop" 2>/dev/null | tr -d '\r')
    echo "$prop=$val"
done

# ---- Thermal -------------------------------------------------------------- #
echo ""
echo "--- Thermal zones (if available) ---"
"$ADB" shell 'for f in /sys/class/thermal/thermal_zone*/type; do
    zone=$(dirname $f)
    type=$(cat $f 2>/dev/null || echo "?")
    temp=$(cat $zone/temp 2>/dev/null || echo "?")
    echo "$zone  type=$type  temp=${temp}"
done' 2>/dev/null | tr -d '\r' | head -30

echo ""
echo "========================================"
echo "End of report."
echo "========================================"
