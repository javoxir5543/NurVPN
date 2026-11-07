#!/usr/bin/env bash
set -euo pipefail
cd /home/pc/NurVPN

GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
step() { echo -e "\n${CYAN}▶ $*${NC}"; }
ok()   { echo -e "${GREEN}✅ $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}"; exit 1; }

# 1. Versiya bump (1.12.1)
step "1/5 Versiya bump (1.12.1)"
python3 - <<'PY'
import re
from pathlib import Path
p = Path('app/build.gradle')
t = p.read_text()
t = re.sub(r'versionCode\s+\d+', 'versionCode 23', t)
t = re.sub(r'versionName\s+"[^"]+"', 'versionName "1.12.1"', t)
p.write_text(t)
PY
ok "Versiya 1.12.1 (versionCode 23) ga ko'tarildi"

# 2. Keystore tekshiruvi
step "2/5 Keystore tekshiruvi"
[ -f "keystore.properties" ] || err "keystore.properties topilmadi!"
[ -f "keystore/nurvpn-release.jks" ] || err "nurvpn-release.jks topilmadi!"
ok "Keystore tayyor"

# 3. Release APK yig'ish
step "3/5 Release APK yig'ish"
./gradlew clean assembleRelease --console=plain | tail -5
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || err "Release APK yig'ilmadi"
SIZE=$(du -m "$APK" | cut -f1)
ok "Release APK tayyor: ${SIZE} MB"

# 4. Git Commit & Push
step "4/5 Git Commit va Push"
# Xavfsizlik: .bak fayllar va keystore git'ga tushmasligi uchun
echo "*.bak*" >> .gitignore
echo "keystore.properties" >> .gitignore
echo "*.jks" >> .gitignore
echo "keystore/" >> .gitignore

git add .
git commit -m "v1.12.1: UI redesign, Hysteria2, FinalMask, 3-language translations, Optimizations" || echo "Commit o'tkazib yuborildi (o'zgarish yo'q)"
git push origin main
git push gitlab main
ok "GitHub va GitLab'ga yuborildi"

# 5. GitHub Release
step "5/5 GitHub Release yaratish"
cat > /tmp/release_notes_v1121.md << 'EOF'
## 🚀 NurVPN v1.12.1 — Xato va kamchiliklar tuzatildi

### 🇺🇿 O'zbekcha (UZ)
Ushbu yangilanishda quyidagi muhim xatolar tuzatildi va yangilandi:
- **Yangi zamonaviy UI**: Obuna kartalari to'liq qayta loyihalandi. Endi barcha ma'lumotlar (kun, GB, support, sayt) chiroyli va tartibli ko'rinadi.
- **Hysteria2 ulanishi**: `insecure=true` va `salamander` obfuskatsiyasi to'liq ishlaydi. Bloklanishlarga chidamli.
- **FinalMask JSON**: Qo'lda server qo'shishda JSON maydoni qo'shildi.
- **Shifrlangan obunalar**: `crypt1://` havolalari to'g'ri ishlaydi.
- **Tillar (UZ/RU/EN)**: Barcha matnlar 3 tilda to'liq tarjima qilindi. Ilova telefon tiliga avtomatik moslashadi.
- **Optimizatsiya**: Keraksiz fayllar tozalandi, xotira va batareya sarfi optimallashtirildi.
- **Scroll va Limitlar**: 200 limiti olib tashlandi, scroll muammolari tuzatildi.

### 🇷🇺 Русский (RU)
В этом обновлении исправлены следующие ошибки и улучшения:
- **Новый современный UI**: Карточки подписок полностью переработаны.
- **Hysteria2**: Полностью исправлена работа `insecure=true` и `salamander`.
- **FinalMask JSON**: Добавлено поле для ручного ввода JSON.
- **Зашифрованные подписки**: Ссылки `crypt1://` работают корректно.
- **Языки (UZ/RU/EN)**: Все тексты переведены на 3 языка. Приложение подстраивается под язык телефона.
- **Оптимизация**: Очищены лишние файлы, улучшена производительность и энергопотребление.
- **Скролл и лимиты**: Лимит 200 удален, исправлены проблемы со скроллом.

### 🇬🇧 English (EN)
This update includes critical bug fixes and improvements:
- **New modern UI**: Subscription cards completely redesigned.
- **Hysteria2**: Fixed `insecure=true` and `salamander` obfs support.
- **FinalMask JSON**: Added manual JSON input field.
- **Encrypted subscriptions**: `crypt1://` links work correctly.
- **Languages (UZ/RU/EN)**: Fully translated into 3 languages. The app automatically adapts to the phone's language.
- **Optimization**: Cleaned up unnecessary files, improved performance and battery life.
- **Scroll & Limits**: 200 limit removed, scroll issues fixed.
EOF

gh release create v1.12.1 "$APK" \
    --title "NurVPN v1.12.1" \
    --notes-file /tmp/release_notes_v1121.md
ok "GitHub Release yaratildi!"
echo ""
echo "🎉 BARCHASI TAYYOR! Rasmiy reliz havolasi:"
echo "https://github.com/javoxir5543/NurVPN/releases/tag/v1.12.1"
