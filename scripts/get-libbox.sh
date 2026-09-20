#!/bin/bash
set -e

# ═══════════════════════════════════════════════════════
#  libbox.aar yuklovchi
#  NurVPN custom build (with_clash_api SIZ)
# ═══════════════════════════════════════════════════════

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/libbox.aar"
mkdir -p "$(dirname "$DEST")"

echo "════════════════════════════════════════════════════════"
echo "  libbox.aar yuklanmoqda (~112 MB)"
echo "════════════════════════════════════════════════════════"
echo ""

# ═══ Manbalar (ko'p marta urinish) ═══
URLS=(
    # 1. archive.org (asosiy)
    "https://archive.org/download/libbox/libbox.aar"
    # 2. archive.org (item sahifasi orqali)
    "https://archive.org/download/libbox/libbox.aar?download=1"
    # 3. GitHub Releases (agar bo'lsa)
    "https://github.com/javoxir5543/NurVPN/releases/download/v1.1.0/libbox.aar"
)

# ═══ Har bir manbadan urinish ═══
for url in "${URLS[@]}"; do
    echo "▶ Urinish: ${url:0:80}..."
    if curl -fL --connect-timeout 15 --max-time 600 \
         --retry 3 --retry-delay 5 \
         "$url" -o "$DEST" 2>/dev/null; then
        size=$(du -h "$DEST" | cut -f1)
        echo "✅ Muvaffaqiyat: $size"
        echo ""
        echo "════════════════════════════════════════════════════════"
        echo "  Fayl: $DEST"
        echo "  Hajm: $size"
        echo "════════════════════════════════════════════════════════"
        exit 0
    fi
    echo "   ❌ Ishlamadi, keyingi manbaga o'tamiz"
    rm -f "$DEST"
done

echo ""
echo "════════════════════════════════════════════════════════"
echo "  ❌ Barcha manbalar ishlamadi"
echo "════════════════════════════════════════════════════════"
echo ""
echo "Qo'lda yuklash:"
echo "  1. https://archive.org/download/libbox/libbox.aar"
echo "  2. app/libs/ papkasiga qo'ying"
echo "  3. ./gradlew assembleRelease"
echo ""
exit 1
