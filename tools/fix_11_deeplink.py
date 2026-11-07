#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #11 — Deep Link'da obuna URL yashirilishi (BUG #2)             ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABAB (dalil):                                               ║
║                                                                          ║
║  `HomeFragment.loadSubDialog()` (1888-qator):                            ║
║                                                                          ║
║      val displayUrl = if (wasEncrypted) {                                ║
║          getString(R.string.encrypted_url_hidden)   // 🔒 ...yashirilgan ║
║      } else {                                                            ║
║          url                                                             ║
║      }                                                                   ║
║      ...                                                                 ║
║      .setMessage(displayUrl)   ← dialog oynasida URL ko'rinmaydi         ║
║                                                                          ║
║  `wasEncrypted = true` deep link yo'lidan keladi:                        ║
║      HomeFragment.kt:2698   loadSubDialog(decoded.first, wasEncrypted=true)║
║                                                                          ║
║  ⚠️ MUHIM: bu YERDA XATO EMAS! `Crypt1Decoder` bilan shifrlangan        ║
║  linklar uchun yashirish MANTIQAN TO'G'RI edi (URL maxfiy).              ║
║                                                                          ║
║  Muammo boshqa joyda: brauzerdan kelgan ODDIY `https://` havola ham     ║
║  shu yashirish shartiga tushib qolardi — chunki eski `handleDeepLink`   ║
║  faqat crypt linklarni o'tkazardi va http(s) ni ham shifrlangan deb     ║
║  belgilardi.                                                             ║
║                                                                          ║
║  YECHIM (foydalanuvchi talabi):                                          ║
║   1. Yashirish logikasini BUTUNLAY olib tashlash — URL doim ko'rinadi   ║
║   2. Brauzer va Deep Link bir xil ko'rinishga ega bo'lsin                ║
║   3. Host validatsiyasi QOLADI (xavfsizlik saqlanadi)                    ║
║   4. `encrypted_url_hidden` matnini 3 tildan o'chirish                   ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
HOME_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
STRINGS = [
    ROOT / "app/src/main/res/values/strings.xml",
    ROOT / "app/src/main/res/values-ru/strings.xml",
    ROOT / "app/src/main/res/values-uz/strings.xml",
]

OLD_DIALOG = '''        // ═══ Shifrlangan bo'lsa — URL yashirish ═══
        val displayUrl = if (wasEncrypted) {
            getString(R.string.encrypted_url_hidden)
        } else {
            url
        }
        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_sub_load)
            .setMessage(displayUrl)'''

NEW_DIALOG = '''        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #11 (BUG #2): URL HAR DOIM TO'LIQ KO'RINADI.
        //
        //  Avval `wasEncrypted=true` bo'lsa dialog "🔒 URL ... yashirilgan"
        //  ko'rsatardi. Foydalanuvchi brauzerdan havola ochganda ham shu
        //  holat yuz berib, obunani yuklab bo'lmasdi.
        //
        //  Endi: brauzer va deep link BIR XIL ko'rinishga ega.
        //  URL foydalanuvchiga doim ko'rsatiladi — u nima yuklayotganini
        //  ko'rishi va tekshirishi kerak.
        //
        //  ⚠️ XAVFSIZLIK: `wasEncrypted` parametri saqlanadi (imzo
        //  o'zgarmasin), lekin endi u faqat `loadSubscription()` ga
        //  uzatiladi — ko'rsatishga ta'sir qilmaydi.
        // ═══════════════════════════════════════════════════════════════
        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_sub_load)
            .setMessage(url)'''


def patch_dialog() -> bool:
    src = HOME_F.read_text(encoding="utf-8")
    if "TUZATISH #11" in src:
        print("ℹ️  loadSubDialog allaqachon tuzatilgan")
        return True
    if OLD_DIALOG not in src:
        print("❌ loadSubDialog: eski blok topilmadi")
        return False

    safe_backup(HOME_F, "fix11")
    src = src.replace(OLD_DIALOG, NEW_DIALOG, 1)

    # Boshqa joyda yashirish qolmaganini tekshiramiz
    leftover = src.count("encrypted_url_hidden")
    if leftover:
        print(f"   ⚠️  yana {leftover} ta havola qoldi — tozalanadi")
        src = re.sub(r'\s*getString\(R\.string\.encrypted_url_hidden\)',
                     ' url', src)

    HOME_F.write_text(src, encoding="utf-8")
    print("✅ HomeFragment: URL yashirish olib tashlandi")
    return True


def patch_strings() -> bool:
    ok = True
    for p in STRINGS:
        if not p.exists():
            print(f"❌ Topilmadi: {p}")
            ok = False
            continue
        src = p.read_text(encoding="utf-8")
        # string resursini o'chiramiz (endi ishlatilmaydi)
        new = re.sub(
            r'\n\s*<string name="encrypted_url_hidden">.*?</string>', '', src)
        if new == src:
            print(f"ℹ️  {p.parent.name}: allaqachon tozalangan")
            continue
        safe_backup(p, "fix11")
        p.write_text(new, encoding="utf-8")
        print(f"✅ {p.parent.name}: encrypted_url_hidden o'chirildi")
    return ok


def verify() -> bool:
    """Hech qayerda yashirish qolmaganini tasdiqlaydi."""
    print()
    print("Tekshiruv:")
    found = False
    for f in list(ROOT.glob("app/src/main/**/*.kt")) + \
             list(ROOT.glob("app/src/main/**/*.xml")):
        try:
            t = f.read_text(encoding="utf-8")
        except Exception:
            continue
        if "encrypted_url_hidden" in t:
            print(f"  ✗ qoldi: {f.relative_to(ROOT)}")
            found = True
    if not found:
        print("  ✓ `encrypted_url_hidden` hech qayerda yo'q")
    return not found


def main() -> int:
    a = patch_dialog()
    b = patch_strings()
    c = verify()
    return 0 if (a and b and c) else 1


if __name__ == "__main__":
    sys.exit(main())
