#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║  NurVPN — VPN RESTART LOOP log yig'uvchi                                   ║
# ╠══════════════════════════════════════════════════════════════════════════╣
# ║  Foydalanish:                                                              ║
# ║      ./tools/capture_vpn_log.sh [SEKUND] [CHIQISH_FAYLI]                   ║
# ║                                                                             ║
# ║  Nima qiladi:                                                              ║
# ║    1. adb logcat -c        (eski logni tozalaydi)                          ║
# ║    2. Foydalanuvchi telefonda tumbler'ni ON qiladi                         ║
# ║    3. 30 sekund kutadi (loop ko'rinishi uchun)                             ║
# ║    4. Log'ni filtrlab faylga yozadi                                        ║
# ║    5. Tahlil qilib, topilgan muammolarni ko'rsatadi                        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
set -uo pipefail

DURATION="${1:-30}"
OUT="${2:-/tmp/vpn_loop.log}"
PKG="com.nurvpn.app"

# Ranglar (terminal bo'lmasa o'chiriladi)
if [ -t 1 ]; then
    R='\033[0;31m'; G='\033[0;32m'; Y='\033[0;33m'; B='\033[0;36m'; N='\033[0m'
else
    R=''; G=''; Y=''; B=''; N=''
fi

echo -e "${B}══════════════════════════════════════════════════════════${N}"
echo -e "${B}  NurVPN — VPN restart loop log yig'uvchi${N}"
echo -e "${B}══════════════════════════════════════════════════════════${N}"
echo "  Davomiylik : ${DURATION}s"
echo "  Chiqish    : ${OUT}"
echo

# ── adb bormi? ──────────────────────────────────────────────────────────
if ! command -v adb >/dev/null 2>&1; then
    echo -e "${R}✗ adb topilmadi.${N} Android SDK platform-tools PATH'da bo'lsin."
    exit 1
fi

# ── Qurilma ulanganmi? ──────────────────────────────────────────────────
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
    echo -e "${R}✗ Qurilma topilmadi.${N} USB'ni tekshiring (adb devices)."
    exit 1
fi
echo -e "${G}✓ Qurilma:${N} $(echo "$DEVICES" | tr '\n' ' ')"
echo

# ── Ilova o'rnatilganmi? ────────────────────────────────────────────────
if ! adb shell pm list packages 2>/dev/null | grep -q "$PKG"; then
    echo -e "${Y}⚠  ${PKG} o'rnatilmagan ko'rinadi.${N}"
fi

# ── Logni tozalash ──────────────────────────────────────────────────────
echo -e "${B}[1/4]${N} Log tozalanmoqda..."
adb logcat -c 2>/dev/null || true

echo
echo -e "${Y}┌────────────────────────────────────────────────────────┐${N}"
echo -e "${Y}│  HOZIR telefonda: tumbler'ni ON qiling (VPN yoqilsin)  │${N}"
echo -e "${Y}│  ${DURATION} sekund ichida. Loop 2-3 sekundda ko'rinadi.        │${N}"
echo -e "${Y}└────────────────────────────────────────────────────────┘${N}"
echo

# ── Kutish (countdown) ──────────────────────────────────────────────────
for i in $(seq "$DURATION" -1 1); do
    printf "\r  ⏳ Kutilmoqda... %2ds " "$i"
    sleep 1
done
printf "\r  ✓ Yig'ish tugadi.        \n\n"

# ── Filtrlangan logni olish ─────────────────────────────────────────────
echo -e "${B}[2/4]${N} Log eksport qilinmoqda..."
adb logcat -d -v threadtime 2>/dev/null \
    | grep -iE "NurVpnService|libbox|sing.?box|AndroidRuntime|FATAL|Exception|START_STICKY|sanitizeFp|NurVPN-|VpnService|BoxService" \
    > "$OUT" 2>/dev/null || true

# To'liq log ham (kontekst uchun)
FULL="${OUT%.log}_full.log"
adb logcat -d -v threadtime 2>/dev/null > "$FULL" || true

LINES=$(wc -l < "$OUT" 2>/dev/null || echo 0)
echo -e "  ${G}✓${N} Filtrlangan : ${OUT} (${LINES} qator)"
echo -e "  ${G}✓${N} To'liq log   : ${FULL}"
echo

if [ "$LINES" -eq 0 ]; then
    echo -e "${Y}⚠  Filtrlangan log bo'sh.${N} Ilova ishga tushmagan yoki"
    echo "   log darajasi past. To'liq logni ko'ring: $FULL"
    exit 0
fi

# ══════════════════════════════════════════════════════════════════════
#  TAHLIL
# ══════════════════════════════════════════════════════════════════════
echo -e "${B}[3/4] Tahlil${N}"
echo -e "${B}──────────────────────────────────────────────────────────${N}"

# 1) Crash bormi?
CRASH=$(grep -cE "FATAL EXCEPTION|AndroidRuntime.*Exception" "$OUT" 2>/dev/null || echo 0)
if [ "$CRASH" -gt 0 ]; then
    echo -e "${R}✗ CRASH topildi (${CRASH} ta)${N}"
    grep -A 12 "FATAL EXCEPTION" "$OUT" 2>/dev/null | head -25
    echo
else
    echo -e "${G}✓ Crash yo'q${N}"
fi

# 2) onStartCommand necha marta?
STARTS=$(grep -c "onStartCommand" "$OUT" 2>/dev/null || echo 0)
echo -e "  onStartCommand chaqiruvlari : ${STARTS}"
if [ "$STARTS" -gt 3 ]; then
    echo -e "  ${R}✗ KO'P! Bu restart loop belgisi.${N}"
fi

# 3) connect/cleanup sikllari
CONNECTS=$(grep -c "runConnect\|connectLocked\|CONNECT " "$OUT" 2>/dev/null || echo 0)
CLEANUPS=$(grep -c "cleanup" "$OUT" 2>/dev/null || echo 0)
ERRORS=$(grep -c "connect fail\|ERROR:" "$OUT" 2>/dev/null || echo 0)
echo -e "  connect urinishlari         : ${CONNECTS}"
echo -e "  cleanup chaqiruvlari        : ${CLEANUPS}"
echo -e "  connect xatolari            : ${ERRORS}"

# 4) sanitizeFp natijalari
FP=$(grep -oE "sanitizeFp[^,;]{0,60}" "$OUT" 2>/dev/null | head -5)
if [ -n "$FP" ]; then
    echo -e "  ${B}sanitizeFp:${N}"
    echo "$FP" | sed 's/^/      /'
fi

# 5) Network restart sikllari
RESTARTS=$(grep -c "Network restart rejalashtirildi\|Yangi tarmoq" "$OUT" 2>/dev/null || echo 0)
echo -e "  tarmoq restartlari          : ${RESTARTS}"

# 6) Xato turlari
echo
echo -e "  ${B}Xato manbalari (top 6):${N}"
grep -oE "connect fail.*|ERROR: .*|Config xato.*|ParseException.*" "$OUT" 2>/dev/null \
    | sort | uniq -c | sort -rn | head -6 | sed 's/^/      /' || true

echo
echo -e "${B}──────────────────────────────────────────────────────────${N}"
echo -e "${B}[4/4] Xulosa${N}"

if [ "$CRASH" -gt 0 ] || [ "$STARTS" -gt 3 ]; then
    echo -e "${R}⚠  RESTART LOOP aniqlandi.${N}"
    echo "   → tools/fix_12_vpn_loop.py ni qo'llang"
    echo "   → To'liq stack trace: grep -A 20 'FATAL' $OUT"
elif [ "$ERRORS" -gt 0 ]; then
    echo -e "${Y}⚠  Ulanish xatolari bor, lekin loop yo'q.${N}"
    echo "   → Konfiguratsiyani tekshiring (yuqoridagi xato manbalari)"
else
    echo -e "${G}✓ Loop belgisi topilmadi.${N}"
    echo "   Agar muammo davom etsa, davomiylikni oshiring:"
    echo "   ./tools/capture_vpn_log.sh 60"
fi
echo
exit 0
