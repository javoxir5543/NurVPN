#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# NurVPN — libbox.aar tekshiruvi
#
# NIMA QILADI:
#   1. AAR tuzilishi butunligini tekshiradi (classes.jar, jni/arm64-v8a)
#   2. APK ichidagi libbox.so ni AAR dagi bilan SHA-256 bo'yicha solishtiradi
#   3. APK'da faqat arm64-v8a borligini va boshqa arxitektura yo'qligini tasdiqlaydi
#   4. APK libbox.so siqilganini (Defl) tasdiqlaydi
#
# ISHLATISH:
#   ./tools/verify-libbox-aar.sh
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
AAR="$REPO_ROOT/app/libs/libbox.aar"
APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"

if [ -t 1 ]; then
    R=$'\e[31m'; G=$'\e[32m'; Y=$'\e[33m'; B=$'\e[36m'; BOLD=$'\e[1m'; N=$'\e[0m'
else
    R=""; G=""; Y=""; B=""; BOLD=""; N=""
fi
ok()   { echo "${G}✓${N} $*"; }
fail() { echo "${R}✗${N} $*"; FAILED=1; }
warn() { echo "${Y}!${N} $*"; }
FAILED=0

echo "${BOLD}═══ libbox AAR/APK tekshiruvi ═══${N}"
echo

# ─── 1. AAR ─────────────────────────────────────────────────────────────────
[ -f "$AAR" ] || { fail "AAR topilmadi: $AAR"; exit 1; }
unzip -tqq -- "$AAR" >/dev/null 2>&1 && ok "AAR ZIP butun" || fail "AAR ZIP buzilgan"
unzip -l -- "$AAR" | grep -q "classes.jar" && ok "classes.jar bor" || fail "classes.jar yo'q"

echo
echo "${BOLD}AAR jni/ tarkibi:${N}"
unzip -l -- "$AAR" | awk '$4 ~ /^jni\// {printf "  %12d  %s\n", $1, $4}' | sort -k2

# faqat arm64-v8a qolganini tekshirish
STRAY=$(unzip -Z1 -- "$AAR" | grep '^jni/' | grep -v '^jni/arm64-v8a/' || true)
if [ -z "$STRAY" ]; then
    ok "Faqat jni/arm64-v8a/ bor (keraksiz arxitektura yo'q)"
else
    warn "Boshqa arxitektura ham bor:"
    echo "$STRAY" | sed 's/^/    /'
fi

unzip -l -- "$AAR" | grep -q "jni/arm64-v8a/.*\.so" \
    && ok "jni/arm64-v8a/*.so mavjud" || fail "jni/arm64-v8a/*.so topilmadi!"

# ─── 2. APK ─────────────────────────────────────────────────────────────────
echo
if [ ! -f "$APK" ]; then
    warn "Release APK topilmadi — APK tekshiruvi o'tkazib yuborildi."
    warn "Avval: ./gradlew assembleRelease"
    echo
    [ "$FAILED" -eq 0 ] && ok "AAR tekshiruvi muvaffaqiyatli" || { echo "${R}Muammolar bor${N}"; exit 1; }
    exit 0
fi

info_size() { numfmt --to=iec-i --suffix=B "$1" 2>/dev/null || echo "$1 B"; }

echo "${BOLD}APK: $(basename -- "$APK") ($(info_size "$(stat -c %s -- "$APK")"))${N}"

# APK'da faqat arm64-v8a
APK_LIBS=$(unzip -Z1 -- "$APK" | grep '^lib/' || true)
if [ -z "$APK_LIBS" ]; then
    fail "APK'da lib/ umuman yo'q!"
else
    echo "$APK_LIBS" | sed 's/^/  /'
    if echo "$APK_LIBS" | grep -qv '^lib/arm64-v8a/'; then
        fail "APK'da arm64-v8a dan boshqa arxitektura bor!"
    else
        ok "APK'da faqat arm64-v8a"
    fi
fi

# ─── 3. SHA-256 solishtirish ────────────────────────────────────────────────
TMP="$(mktemp -d)"; trap 'rm -rf -- "$TMP"' EXIT
unzip -qq -o -- "$APK" 'lib/*' -d "$TMP/apk" 2>/dev/null || true
unzip -qq -o -- "$AAR" 'jni/arm64-v8a/*' -d "$TMP/aar" 2>/dev/null || true

APK_SO="$TMP/apk/lib/arm64-v8a/libbox.so"
AAR_SO="$TMP/aar/jni/arm64-v8a/libbox.so"

echo
if [ -f "$APK_SO" ] && [ -f "$AAR_SO" ]; then
    H1=$(sha256sum -- "$APK_SO" | cut -d' ' -f1)
    H2=$(sha256sum -- "$AAR_SO" | cut -d' ' -f1)
    echo "  APK libbox.so : $H1"
    echo "  AAR libbox.so : $H2"
    if [ "$H1" = "$H2" ]; then
        ok "SHA-256 AYNAN BIR XIL — native lib zarar ko'rmagan"
    else
        fail "SHA-256 FARQ QILADI — native lib buzilgan!"
    fi
else
    warn "libbox.so ni solishtirish imkonsiz (fayl topilmadi)"
fi

# ─── 4. Siqilganini tekshirish ──────────────────────────────────────────────
echo
echo "${BOLD}libbox.so APK ichida:${N}"
unzip -v -- "$APK" | grep -E 'libbox\.so' | awk '{printf "  siqilmagan: %s  siqilgan: %s  nisbat: %s  %s\n", $1, $3, $4, $8}' || true

STORED=$(unzip -v -- "$APK" | awk '/libbox\.so/ {print $3}')
RAW=$(unzip -v -- "$APK" | awk '/libbox\.so/ {print $1}')
if [ -n "$STORED" ] && [ -n "$RAW" ] && [ "$STORED" -lt "$RAW" ]; then
    PCT=$(( STORED * 100 / RAW ))
    ok "libbox.so SIQILGAN ($PCT% nisbat, extractNativeLibs=true ishlayapti)"
else
    fail "libbox.so SIQILMAGAN — useLegacyPackaging ishlamayapti!"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "${G}${BOLD}Barcha tekshiruvlar muvaffaqiyatli ✓${N}"
else
    echo "${R}${BOLD}Ba'zi tekshiruvlar muvaffaqiyatsiz ✗${N}"
    exit 1
fi
