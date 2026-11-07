#!/bin/bash
# ★ FIX135/136 SINOV SKRIPTI
# Home'da karta ochish va ping frizini o'lchaydi.
# Uzilishlarga chidamli — har qadamda qayta ulanadi.

PKG=com.nurvpn.app
ADB="adb -s 3090RH2001038550"

wait_dev() {
    for i in $(seq 1 60); do
        if adb devices 2>/dev/null | grep -q "3090RH2001038550.*device$"; then
            return 0
        fi
        sleep 3
    done
    echo "✗ Telefon ulanmadi"; return 1
}

step() { echo; echo "──── $1 ────"; }

wait_dev || exit 1
$ADB shell input keyevent KEYCODE_WAKEUP 2>/dev/null
$ADB shell am force-stop $PKG 2>/dev/null
sleep 2
$ADB logcat -c 2>/dev/null

step "Ilovani ochamiz"
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 7
wait_dev || exit 1

step "Yuqoriga scroll (kartalar ko'rinishi uchun)"
$ADB shell input swipe 360 400 360 1200 400
sleep 2

step "Kartalar joyini aniqlaymiz"
$ADB shell screencap -p /sdcard/t1.png 2>/dev/null
$ADB pull /sdcard/t1.png /tmp/t1.png >/dev/null 2>&1
python3 - <<'PY'
from PIL import Image
try:
    im=Image.open('/tmp/t1.png').convert('RGB');w,h=im.size
    print(f"ekran {w}x{h}")
    rows=[]
    for y in range(600,1400,10):
        bright=0
        for x in range(20,w-20,10):
            r,g,b=im.getpixel((x,y))
            if (r+g+b)//3>90: bright+=1
        rows.append((y,bright))
    # Matn qatorlarini topamiz
    print("yorqin piksellar (matn joylari):")
    for y,b in rows:
        if b>3: print(f"  y={y}: {b}")
except Exception as e:
    print("xato:",e)
PY

step "1-KARTANI OCHAMIZ"
$ADB logcat -c 2>/dev/null
$ADB shell input tap 360 780
sleep 5
wait_dev || exit 1

step "NATIJA"
$ADB logcat -d 2>/dev/null | grep -aE "NurVPN-PERF|Skipped.*frames" | tail -8
