#!/bin/bash
set -e

# ═══════════════════════════════════════════════════
#  libbox.aar yuklovchi (sing-box Android kutubxonasi)
# ═══════════════════════════════════════════════════

VERSION="${1:-v1.10.7}"
ARCH="${2:-arm64}"
DEST="$HOME/NurVPN/app/libs/libbox.aar"

# URL tayyorlash
VER_NUM="${VERSION#v}"
URL="https://github.com/SagerNet/sing-box/releases/download/${VERSION}/libbox-${VER_NUM}-android-${ARCH}.aar"

echo "════════════════════════════════════════════════"
echo "  libbox.aar yuklanmoqda"
echo "════════════════════════════════════════════════"
echo "  Versiya: $VERSION"
echo "  Arch:    $ARCH"
echo "  URL:     $URL"
echo "  Manzil:  $DEST"
echo "════════════════════════════════════════════════"
echo ""

# Papka yaratish
mkdir -p "$(dirname "$DEST")"

# Yuklab olish
if command -v wget >/dev/null 2>&1; then
    wget --progress=bar:force:noscroll -O "$DEST" "$URL"
elif command -v curl >/dev/null 2>&1; then
    curl -L --progress-bar -o "$DEST" "$URL"
else
    echo "❌ wget ham, curl ham topilmadi"
    exit 1
fi

# Tekshirish
if [ ! -f "$DEST" ]; then
    echo "❌ Yuklab bo'lmadi"
    exit 1
fi

SIZE=$(stat -c%s "$DEST")
if [ "$SIZE" -lt 1000000 ]; then
    echo "⚠️  Fayl juda kichik ($SIZE bayt). Balki 404."
    echo "Fayl ichida:"
    head -5 "$DEST"
    echo ""
    echo "⚠️  Bu versiya uchun libbox.aar mavjud emas."
    echo "Boshqa versiyani sinab ko'ring:"
    echo "  $0 v1.10.6"
    echo "  $0 v1.11.15"
    echo "  $0 v1.12.0"
    exit 1
fi

echo ""
echo "════════════════════════════════════════════════"
echo "✅ Muvaffaqiyatli yuklandi"
echo "════════════════════════════════════════════════"
ls -lh "$DEST"
file "$DEST"
echo ""
echo "Keyingi qadam: build.gradle'ga qo'shish (men bajaraman)"
