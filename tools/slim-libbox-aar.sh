#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# NurVPN — libbox.aar hajmini kichraytirish
#
# NIMA QILADI:
#   app/libs/libbox.aar ichidagi keraksiz arxitekturalarni (x86, x86_64,
#   armeabi-v7a) JNI papkasidan olib tashlaydi, faqat arm64-v8a qoldiradi.
#
# NEGA XAVFSIZ:
#   app/build.gradle da `abiFilters 'arm64-v8a'` bor, ya'ni bu arxitekturalar
#   ALLAQACHON APK'ga tushmaydi. Ularni AAR'dan olib tashlash BUILD NATIJASINI
#   O'ZGARTIRMAYDI (APK hajmi ham, dex ham, native lib ham).
#   Faqat: repo ~93 MB kichrayadi, AGP merge qiladigan fayl soni kamayadi.
#
# XAVFSIZLIK CHORALARI:
#   - AAR tuzilishi tekshiriladi (zip + classes.jar + jni/)
#   - arm64-v8a MAJBURAN mavjud bo'lishi shart, aks holda to'xtaydi
#   - Original .aar.bak ga zaxiralanadi (bir marta)
#   - classes.jar, AndroidManifest.xml, R.txt va boshqa hamma narsa tegilmaydi
#   - --dry-run va --restore rejimlari bor
#
# ISHLATISH:
#   ./tools/slim-libbox-aar.sh --dry-run   # nima o'chishini ko'rish
#   ./tools/slim-libbox-aar.sh             # bajarish
#   ./tools/slim-libbox-aar.sh --restore   # zaxiradan qaytarish
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

# ─── Yo'llar (repo ildiziga nisbatan, qat'iy tekshiriladi) ──────────────────
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
AAR="$REPO_ROOT/app/libs/libbox.aar"
BAK="$REPO_ROOT/app/libs/libbox.aar.bak"

KEEP_ARCH="arm64-v8a"
DROP_ARCHES=("x86" "x86_64" "armeabi-v7a")

MODE="apply"
case "${1:-}" in
    --dry-run) MODE="dry" ;;
    --restore) MODE="restore" ;;
    "")        MODE="apply" ;;
    *) echo "Xato: noma'lum argument '$1'. Ishlatish: [--dry-run|--restore]" >&2; exit 2 ;;
esac

# ─── Rangli chiqish ─────────────────────────────────────────────────────────
if [ -t 1 ]; then
    R=$'\e[31m'; G=$'\e[32m'; Y=$'\e[33m'; B=$'\e[36m'; BOLD=$'\e[1m'; N=$'\e[0m'
else
    R=""; G=""; Y=""; B=""; BOLD=""; N=""
fi
info() { echo "${B}•${N} $*"; }
ok()   { echo "${G}✓${N} $*"; }
warn() { echo "${Y}!${N} $*"; }
die()  { echo "${R}✗ $*${N}" >&2; exit 1; }

# ─── --restore ──────────────────────────────────────────────────────────────
if [ "$MODE" = "restore" ]; then
    [ -f "$BAK" ] || die "Zaxira topilmadi: $BAK"
    cp -f -- "$BAK" "$AAR"
    ok "Tiklandi: $BAK -> $AAR"
    ls -la -- "$AAR"
    exit 0
fi

# ─── Old shartlar ───────────────────────────────────────────────────────────
echo "${BOLD}═══ libbox.aar slim ═══${N}"
for t in unzip zip cp; do command -v "$t" >/dev/null 2>&1 || die "'$t' topilmadi"; done
[ -f "$AAR" ] || die "AAR topilmadi: $AAR"

# Bu TASODIFAN boshqa faylga ishlamasligi uchun qat'iy tekshiruv
case "$AAR" in
    */app/libs/libbox.aar) : ;;
    *) die "Kutilmagan AAR yo'li: $AAR" ;;
esac

BEFORE_BYTES=$(stat -c %s -- "$AAR")

# ─── AAR tuzilishini tekshirish ─────────────────────────────────────────────
unzip -tqq -- "$AAR" >/dev/null 2>&1 || die "Bu yaroqli ZIP/AAR emas: $AAR"
unzip -l -- "$AAR" | grep -q "classes.jar" || die "classes.jar yo'q — bu AAR emas?"
unzip -l -- "$AAR" | grep -q "jni/" || die "jni/ papkasi yo'q — bu AAR emas?"

# arm64-v8a MAJBURIY bo'lishi shart
if ! unzip -l -- "$AAR" | grep -q "jni/$KEEP_ARCH/"; then
    die "jni/$KEEP_ARCH/ topilmadi! Bu AAR kutilgan tuzilishda emas — to'xtatildi."
fi

echo
info "AAR: $AAR"
info "Hajm (oldin): $(numfmt --to=iec-i --suffix=B "$BEFORE_BYTES" 2>/dev/null || echo "$BEFORE_BYTES B")"
echo

# ─── Hozirgi jni tarkibi ────────────────────────────────────────────────────
echo "${BOLD}Hozirgi jni/ tarkibi:${N}"
unzip -l -- "$AAR" | awk '$4 ~ /^jni\// {printf "  %12d  %s\n", $1, $4}' | sort -k2
echo

# ─── O'chiriladigan fayllar ro'yxati ────────────────────────────────────────
DROP_LIST=()
for arch in "${DROP_ARCHES[@]}"; do
    while IFS= read -r entry; do
        [ -n "$entry" ] && DROP_LIST+=("$entry")
    done < <(unzip -Z1 -- "$AAR" | grep "^jni/$arch/" || true)
done

if [ "${#DROP_LIST[@]}" -eq 0 ]; then
    ok "O'chirish uchun hech narsa yo'q — AAR allaqachon faqat $KEEP_ARCH."
    exit 0
fi

DROP_BYTES=0
echo "${BOLD}O'chiriladi:${N}"
for entry in "${DROP_LIST[@]}"; do
    sz=$(unzip -l -- "$AAR" "$entry" | awk '/^ *[0-9]+/ {print $1; exit}')
    sz=${sz:-0}
    DROP_BYTES=$((DROP_BYTES + sz))
    printf "  ${R}-${N} %-38s %10s\n" "$entry" "$(numfmt --to=iec-i --suffix=B "$sz" 2>/dev/null || echo "$sz")"
done
echo
echo "  Saqlanadi: ${G}jni/$KEEP_ARCH/${N} (+ classes.jar, manifest, R.txt, proguard.txt)"
echo "  Tejaladi (siqilmagan): $(numfmt --to=iec-i --suffix=B "$DROP_BYTES" 2>/dev/null || echo "$DROP_BYTES")"
echo

if [ "$MODE" = "dry" ]; then
    warn "--dry-run: hech narsa o'zgartirilmadi."
    exit 0
fi

# ─── Zaxira ─────────────────────────────────────────────────────────────────
if [ -f "$BAK" ]; then
    info "Zaxira allaqachon bor, qayta yaratilmaydi: $BAK"
else
    cp -p -- "$AAR" "$BAK"
    ok "Zaxira yaratildi: $BAK"
fi

# ─── Qayta qurish (vaqtinchalik papkada, atomik almashtirish) ───────────────
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT

info "AAR ochilmoqda..."
unzip -qq -- "$AAR" -d "$TMP_DIR/aar"

# Faqat keraksiz arxitektura papkalarini o'chirish
for arch in "${DROP_ARCHES[@]}"; do
    if [ -d "$TMP_DIR/aar/jni/$arch" ]; then
        rm -rf -- "$TMP_DIR/aar/jni/$arch"
        info "O'chirildi: jni/$arch/"
    fi
done

# Xavfsizlik: arm64-v8a joyidami?
[ -d "$TMP_DIR/aar/jni/$KEEP_ARCH" ] || die "Ichki tekshiruv muvaffaqiyatsiz: jni/$KEEP_ARCH yo'qolgan!"
find "$TMP_DIR/aar/jni/$KEEP_ARCH" -type f -name '*.so' | grep -q . \
    || die "Ichki tekshiruv muvaffaqiyatsiz: jni/$KEEP_ARCH ichida .so yo'q!"

# Yangi AAR yig'ish — faqat mazmun, deterministik tartibda
info "Yangi AAR yig'ilmoqda..."
( cd "$TMP_DIR/aar" && find . -type f | LC_ALL=C sort | sed 's|^\./||' \
    | zip -q -X -9 "$TMP_DIR/libbox.aar" -@ )

# Atomik almashtirish
cp -f -- "$TMP_DIR/libbox.aar" "$AAR"

AFTER_BYTES=$(stat -c %s -- "$AAR")
SAVED=$((BEFORE_BYTES - AFTER_BYTES))

echo
ok "Tayyor!"
echo "  Oldin : $(numfmt --to=iec-i --suffix=B "$BEFORE_BYTES" 2>/dev/null || echo "$BEFORE_BYTES")"
echo "  Keyin : $(numfmt --to=iec-i --suffix=B "$AFTER_BYTES" 2>/dev/null || echo "$AFTER_BYTES")"
echo "  Tejaldi: ${G}$(numfmt --to=iec-i --suffix=B "$SAVED" 2>/dev/null || echo "$SAVED")${N}"
echo
echo "${BOLD}Yangi jni/ tarkibi:${N}"
unzip -l -- "$AAR" | awk '$4 ~ /^jni\// {printf "  %12d  %s\n", $1, $4}'
echo
info "Tekshirish: ./tools/verify-libbox-aar.sh"
info "Qaytarish : ./tools/slim-libbox-aar.sh --restore"
