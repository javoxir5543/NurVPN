#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# NurVPN — To'liq backup skript
# Yaratilgan: 2026-09-26
# ═══════════════════════════════════════════════════════════════
#
# Nima qiladi:
#   1. Butun loyihani timestamp bilan backup qiladi
#   2. Git commit + push
#   3. Release APK yig'adi
#   4. NURVPN_FULL_CODE.md yangilaydi
#   5. Eski backuplarni tozalaydi (oxirgi 5 tasini qoldiradi)
#
# Ishlatish:
#   ./NURVPN-BACKUP-FULL.sh              # oddiy backup
#   ./NURVPN-BACKUP-FULL.sh --release    # + release APK
#
# ═══════════════════════════════════════════════════════════════

set -e

PROJ_DIR="$HOME/NurVPN"
BACKUP_ROOT="$HOME/NurVPN-backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="$BACKUP_ROOT/backup_$TIMESTAMP"
RELEASE_MODE=false

if [ "$1" = "--release" ]; then
    RELEASE_MODE=true
fi

# Rangli chiqish
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $1"; }
ok()   { echo -e "${GREEN}✅ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $1${NC}"; }
err()  { echo -e "${RED}❌ $1${NC}"; exit 1; }

# ═══ 1. Tekshirish ═══
log "Loyiha tekshirilmoqda: $PROJ_DIR"
[ -d "$PROJ_DIR" ] || err "Loyiha topilmadi: $PROJ_DIR"
cd "$PROJ_DIR"

# ═══ 2. Backup papka yaratish ═══
mkdir -p "$BACKUP_ROOT"
log "Backup papka: $BACKUP_DIR"

# ═══ 3. Kodni backup qilish (git va build papkalarsiz) ═══
log "Kod nusxalanmoqda..."
rsync -a --exclude='.git' \
         --exclude='.gradle' \
         --exclude='build' \
         --exclude='app/build' \
         --exclude='local.properties' \
         "$PROJ_DIR/" "$BACKUP_DIR/"

ok "Kod nusxalandi: $(du -sh "$BACKUP_DIR" | cut -f1)"

# ═══ 4. Git commit + push ═══
log "Git holati tekshirilmoqda..."
if [ -n "$(git status --porcelain)" ]; then
    git add -A
    git commit -m "Auto-backup: $TIMESTAMP" || warn "Commit qilinmadi"
    git push origin main || warn "Push qilinmadi"
    ok "Git commit + push"
else
    ok "Git toza — o'zgarish yo'q"
fi

# ═══ 5. NURVPN_FULL_CODE.md yangilash ═══
log "NURVPN_FULL_CODE.md yangilanmoqda..."
python3 << 'PYEOF'
from pathlib import Path

base = Path("app/src/main/java")
out_file = Path("NURVPN_FULL_CODE.md")
kt_files = sorted(base.rglob("*.kt"))

with open(out_file, "w") as out:
    out.write("# NurVPN — To'liq kod\n\n")
    out.write("> Barcha Kotlin fayllar bitta faylda. Avtomatik yaratilgan.\n\n")
    out.write(f"**Jami fayllar:** {len(kt_files)}\n\n---\n\n")
    out.write("## 📁 Struktura\n\n```\n")
    for f in kt_files:
        out.write(f"{f.relative_to(base)}\n")
    out.write("```\n\n---\n\n")
    total = 0
    for f in kt_files:
        content = f.read_text()
        lines = content.count("\n") + 1
        total += lines
        out.write(f"## 📄 `{f.relative_to(base)}`\n\n")
        out.write(f"*{lines} qator*\n\n```kotlin\n")
        out.write(content)
        if not content.endswith("\n"):
            out.write("\n")
        out.write("```\n\n---\n\n")
    out.write(f"\n**Jami qatorlar:** {total}\n")

print(f"OK: {len(kt_files)} fayl, {total} qator")
PYEOF

git add NURVPN_FULL_CODE.md 2>/dev/null || true
git commit -m "Docs: NURVPN_FULL_CODE.md auto-yangilandi ($TIMESTAMP)" 2>/dev/null || true
git push origin main 2>/dev/null || true
ok "NURVPN_FULL_CODE.md yangilandi"

# ═══ 6. Release APK (agar --release) ═══
if [ "$RELEASE_MODE" = true ]; then
    log "Release APK yig'ilmoqda..."
    ./gradlew --no-daemon clean assembleRelease > /tmp/nurvpn_relbuild.log 2>&1
    if [ -f app/build/outputs/apk/release/app-arm64-v8a-release.apk ]; then
        cp app/build/outputs/apk/release/*.apk "$BACKUP_DIR/" 2>/dev/null || true
        ok "Release APK backupga qo'shildi"
    else
        warn "Release APK yig'ilmadi (log: /tmp/nurvpn_relbuild.log)"
    fi
fi

# ═══ 7. Eski backuplarni tozalash (oxirgi 5 ta qoldirish) ═══
log "Eski backuplar tozalanmoqda (oxirgi 5 ta qoldiriladi)..."
cd "$BACKUP_ROOT"
ls -1dt backup_* 2>/dev/null | tail -n +6 | while read old; do
    rm -rf "$old"
    echo "  O'chirildi: $old"
done
cd "$PROJ_DIR"

ok "Backup yakunlandi!"
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  📁 Backup: $BACKUP_DIR"
echo "  📊 Hajmi:  $(du -sh "$BACKUP_DIR" | cut -f1)"
echo "  📅 Sana:   $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════════════════════════════"
