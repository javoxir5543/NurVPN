#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  FIX v1.12.0 — BUG #2: Versiya ko'tarilmagan                               ║
╠══════════════════════════════════════════════════════════════════════════╣
║  TASDIQLANGAN FAKTLAR:                                                   ║
║                                                                          ║
║   ✓ `HomeFragment.kt:1914` — `.setMessage(url)` TO'G'RI (URL ko'rinadi)  ║
║   ✓ `encrypted_url_hidden` — 3 tildan ham O'CHIRILGAN                    ║
║   ✓ Kod tomoni TAYYOR                                                    ║
║                                                                          ║
║   ❌ `app/build.gradle:36-37`:                                           ║
║        versionCode 19                                                    ║
║        versionName "1.11.1"                                              ║
║                                                                          ║
║  → Telefonda v1.11.1 ishlayapti. Yangi APK o'rnatilmagan YOKI           ║
║    versiya ko'tarilmagani uchun eski APK qayta o'rnatilmagan.           ║
║                                                                          ║
║  YECHIM: versionCode 19 → 22, versionName "1.11.1" → "1.12.0"          ║
║                                                                          ║
║  ⚠️ NEga versionCode muhim: Android bir xil yoki kichik versionCode    ║
║     bilan APK'ni "yangilash" deb hisoblamaydi (INSTALL_FAILED_         ║
║     VERSION_DOWNGRADE). Shu sabab brauzerdagi deep link eski           ║
║     o'rnatilgan versiyaga borardi.                                      ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app/build.gradle"

TARGET_CODE = 22
TARGET_NAME = "1.12.0"


def fix_version() -> bool:
    src = GRADLE.read_text(encoding="utf-8")

    m_code = re.search(r'versionCode\s+(\d+)', src)
    m_name = re.search(r'versionName\s+"([^"]+)"', src)

    if not m_code or not m_name:
        print("❌ app/build.gradle: versionCode/versionName topilmadi")
        return False

    cur_code = int(m_code.group(1))
    cur_name = m_name.group(1)

    if cur_code == TARGET_CODE and cur_name == TARGET_NAME:
        print(f"ℹ️  Versiya allaqachon {TARGET_NAME} ({TARGET_CODE}) — "
              f"o'zgartirish kerak emas")
        return True

    safe_backup(GRADLE, "fix_v112_bug2")

    new = src
    new = re.sub(r'versionCode\s+\d+',
                 f'versionCode {TARGET_CODE}', new, count=1)
    new = re.sub(r'versionName\s+"[^"]+"',
                 f'versionName "{TARGET_NAME}"', new, count=1)

    # Izoh qo'shamiz (versiya tarixi)
    if "FIX112-BUG2" not in new:
        new = new.replace(
            f'        versionName "{TARGET_NAME}"',
            f'        versionName "{TARGET_NAME}"   '
            f'// \u2605 FIX112-BUG2: deep link tuzatishlari',
            1)

    GRADLE.write_text(new, encoding="utf-8")
    print(f"✅ app/build.gradle: {cur_name} ({cur_code}) → "
          f"{TARGET_NAME} ({TARGET_CODE})")
    return True


def fix_manifest_version() -> bool:
    """
    AndroidManifest'da versiya qo'lda yozilgan bo'lsa — ziddiyat bo'lmasin.
    (Odatda Gradle'dan olinadi, lekin tekshiramiz.)
    """
    p = ROOT / "app/src/main/AndroidManifest.xml"
    src = p.read_text(encoding="utf-8")

    if "android:versionCode=" in src or "android:versionName=" in src:
        print("⚠️  Manifest'da qo'lda versiya bor — Gradle bilan ziddiyat!")
        safe_backup(p, "fix_v112_bug2")
        src = re.sub(r'\s*android:versionCode="[^"]*"', '', src)
        src = re.sub(r'\s*android:versionName="[^"]*"', '', src)
        p.write_text(src, encoding="utf-8")
        print("✅ Manifest'dan versiya olib tashlandi (Gradle boshqaradi)")
    else:
        print("ℹ️  Manifest toza (versiya Gradle'dan olinadi)")
    return True


def verify() -> bool:
    src = GRADLE.read_text(encoding="utf-8")
    ok = True
    print()
    print("── Tekshiruv ──")

    m_code = re.search(r'versionCode\s+(\d+)', src)
    m_name = re.search(r'versionName\s+"([^"]+)"', src)

    if m_code and int(m_code.group(1)) == TARGET_CODE:
        print(f"  ✓ versionCode {m_code.group(1)}")
    else:
        print(f"  ✗ versionCode noto'g'ri: {m_code.group(1) if m_code else '?'}")
        ok = False

    if m_name and m_name.group(1) == TARGET_NAME:
        print(f"  ✓ versionName {m_name.group(1)}")
    else:
        print(f"  ✗ versionName noto'g'ri: "
              f"{m_name.group(1) if m_name else '?'}")
        ok = False

    # Kod tomoni ham tasdiqlansin
    home = (ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt") \
        .read_text(encoding="utf-8")
    if "encrypted_url_hidden" not in home:
        print("  ✓ HomeFragment: yashirish yo'q (URL ko'rinadi)")
    else:
        print("  ✗ HomeFragment: yashirish hali ham bor!")
        ok = False

    return ok


def main() -> int:
    a = fix_version()
    print()
    b = fix_manifest_version()
    v = verify()
    return 0 if (a and b and v) else 1


if __name__ == "__main__":
    sys.exit(main())
