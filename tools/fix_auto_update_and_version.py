#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX160-VERSION — version bump (1.12.1 → 1.12.2) + eskirgan testlarni tuzatish
════════════════════════════════════════════════════════════════════════════

 ────────────────────────────────────────────────────────────────────────
  VAZIFA
 ────────────────────────────────────────────────────────────────────────

 1. `FIX159-AUTOUPDATE` holatini TEKSHIRISH (u allaqachon qo'llangan —
    barcha 5 o'zgarish "bor" bo'lishi kerak).
 2. `app/build.gradle`: versionCode 23→24, versionName 1.12.1→1.12.2
 3. `V112FixesTest.kt` dagi 3 ta ESKIRGAN testni yangilash:
      • `versionName 1_12_0 ga kotarilgan`      → 1.12.2
      • `versionCode 22 ga kotarilgan`          → 24
      • `versionCode va versionName izchil`     → 1.12.2 / 24

 ────────────────────────────────────────────────────────────────────────
  NEGA 3 TA TEST ESKIRGAN?
 ────────────────────────────────────────────────────────────────────────

 `V112FixesTest` — v1.12.0 uchun yozilgan regressiya testlari. Ular
 `app/build.gradle` dagi versiyani QAT'IY qiymat bilan tekshiradi:

     g.contains("versionName \"1.12.0\"")     // 1.12.0 ni kutadi
     assertEquals("22", ...groupValues[1])    // 22 ni kutadi

 Loyiha allaqachon `1.12.1` / `23` ga ko'tarilgan edi (FIX112-BUG2),
 lekin testlar yangilanmagan → `./gradlew test` 3 ta xato beradi.

 ────────────────────────────────────────────────────────────────────────
  ⚠️ MUHIM: NEGA "IZCHILLIK" TESTI QIMMATLI
 ────────────────────────────────────────────────────────────────────────

 `bug2 - versionCode va versionName izchil` testi ikkalasini BIRGA
 tekshiradi. Bu `versionName` ni ko'tarib `versionCode` ni esdan
 chiqarishdan saqlaydi — Android'da `VERSION_DOWNGRADE` xatosi
 aynan shundan kelib chiqadi (APK o'rnatilmaydi).

 Shuning uchun testni QAT'IY qoldiramiz, faqat qiymatini yangilaymiz.

 ────────────────────────────────────────────────────────────────────────
  ★★ HIMOYA: TEGILMAYDIGAN FAYLLAR ★★
 ────────────────────────────────────────────────────────────────────────

   ❌ `UpdateManager.kt` / `MainActivity.kt` — FIX159 allaqachon qo'llagan
   ❌ `strings.xml` × 4 — til tarjimalari (FIX158)
   ❌ `item_home_card_header.xml` — INCY UI
   ❌ `SingBoxConfig.kt`          — Hysteria2
   ❌ `FinalMaskJson.kt`          — FinalMask
   ❌ VPN / tun / deep-link mantig'i
   ❌ `DiffUtil`

   ✅ FAQAT 2 ta fayl: `app/build.gradle` + `V112FixesTest.kt`

 ────────────────────────────────────────────────────────────────────────
  ISHLATISH
 ────────────────────────────────────────────────────────────────────────

     python3 tools/fix_auto_update_and_version.py --check
     python3 tools/fix_auto_update_and_version.py
     python3 tools/fix_auto_update_and_version.py --rollback
"""

import argparse
import glob
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")
BACKUP_DIR = ROOT / ".fix-backups"          # ⚠️ res/ ichida .bak YO'Q

GRADLE = ROOT / "app/build.gradle"
TEST = ROOT / "app/src/test/java/com/nurvpn/app/V112FixesTest.kt"
FIX159 = ROOT / "tools/fix_auto_update_permission.py"

# ── Yangi versiya ──
NEW_CODE = "24"
NEW_NAME = "1.12.2"

# ── Eski versiya (testlarni topish uchun) ──
OLD_NAME_IN_TEST = "1.12.0"
OLD_CODE_IN_TEST = "22"


# ═══════════════════════════════════════════════════════════════════════
#  TAHRIRLASH YORDAMCHISI
# ═══════════════════════════════════════════════════════════════════════

class Edit:
    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:200]!r}")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def write(self) -> bool:
        if not self.changed():
            return False
        BACKUP_DIR.mkdir(exist_ok=True)
        shutil.copy2(self.path, BACKUP_DIR / f"{self.path.name}.{STAMP}.bak")
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        pats = glob.glob(str(BACKUP_DIR / f"{self.path.name}.*.bak"))
        if not pats:
            return False
        bak = Path(max(pats, key=lambda p: Path(p).stat().st_mtime))
        cur = self.path.stat().st_size if self.path.exists() else 0
        if cur and bak.stat().st_size < cur * 0.5:
            print(f"   ✗ {self.path.name}: zaxira juda kichik — o'tkazildi")
            return False
        shutil.copy2(bak, self.path)
        return True


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX160-VERSION")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX160-VERSION — versiya ko'tarish + testlarni tuzatish")
    print("═" * 76)

    # ───────────────────────────────────────────────────────────────
    #  ROLLBACK
    # ───────────────────────────────────────────────────────────────
    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for p in (GRADLE, TEST):
            if Edit(p).rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  1) FIX159 HOLATINI TEKSHIRISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── 1. FIX159-AUTOUPDATE holati ──")
    if not FIX159.exists():
        print(f"   ✗ {FIX159.name} topilmadi")
        return 1
    try:
        r = subprocess.run(
            [sys.executable, str(FIX159), "--check"],
            capture_output=True, text=True, timeout=120)
        out = r.stdout
        applied = out.count("– bor") + out.count("– allaqachon bor")
        added = out.count("✓ QO'SHILDI")
        print(f"   Qo'llangan: {applied}/5, yangi: {added}")
        if added > 0:
            print("   ⚠️  FIX159 to'liq qo'llanmagan!")
            print("   Avval ishga tushiring:")
            print("      python3 tools/fix_auto_update_permission.py")
            return 1
        # Himoya tekshiruvlari
        for guard, label in [
            ("Ruxsat bloki to'g'ri", "ruxsat bloki"),
            ("Yangi funksiyalar joyida", "yangi funksiyalar"),
            ("MainActivity himoyalangan", "MainActivity"),
        ]:
            mark = "✓" if guard in out else "✗"
            print(f"   {mark} {label}")
            if guard not in out:
                return 1
        print("   ✓ FIX159 to'liq qo'llangan")
    except Exception as ex:  # noqa: BLE001
        print(f"   ✗ FIX159 tekshiruvi bajarilmadi: {ex}")
        return 1

    e_gradle, e_test = Edit(GRADLE), Edit(TEST)

    # ───────────────────────────────────────────────────────────────
    #  2) VERSIYA KO'TARISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── 2. Versiya ko'tarish ──")

    cur_code = re.search(r'versionCode\s+(\d+)', e_gradle.text)
    cur_name = re.search(r'versionName\s+"([^"]+)"', e_gradle.text)
    print(f"   Hozir: code={cur_code.group(1) if cur_code else '?'} "
          f"name={cur_name.group(1) if cur_name else '?'}")
    print(f"   Yangi: code={NEW_CODE} name={NEW_NAME}")

    d1 = e_gradle.sub(
        f'versionCode {cur_code.group(1)}',
        f'versionCode {NEW_CODE}',
        f"versionCode {cur_code.group(1)} → {NEW_CODE}",
        guard=f'versionCode {NEW_CODE}')

    d2 = e_gradle.sub(
        f'versionName "{cur_name.group(1)}"',
        f'versionName "{NEW_NAME}"',
        f"versionName {cur_name.group(1)} → {NEW_NAME}",
        guard=f'versionName "{NEW_NAME}"')

    for what, done in ((f"versionCode → {NEW_CODE}", d1),
                       (f"versionName → {NEW_NAME}", d2)):
        print(f"   {'✓ QO\'SHILDI' if done else '– bor':14} {what}")

    # ───────────────────────────────────────────────────────────────
    #  3) ESKIRGAN TESTLARNI TUZATISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── 3. Eskirgan testlar (3 ta) ──")

    # ── Test 1: versionName ──
    # ⚠️ Qochirish (escaping) chuqurligi sababli butun funksiyani
    #    almashtirmaymiz — faqat VERSIYA satrlarini nishonga olamiz.
    t1_ops = [
        (f'fun `bug2 - versionName {OLD_NAME_IN_TEST.replace(".", "_")} ga kotarilgan`()',
         f'fun `bug2 - versionName {NEW_NAME.replace(".", "_")} ga kotarilgan`()',
         "test nomi"),
        (f"versionName {OLD_NAME_IN_TEST} bo'lishi shart",
         f"versionName {NEW_NAME} bo'lishi shart",
         "xato matni"),
        (f'g.contains("versionName \\"{OLD_NAME_IN_TEST}\\"")',
         f'g.contains("versionName \\"{NEW_NAME}\\"")',
         "assert satri"),
    ]
    t1 = False
    if f'bug2 - versionName {NEW_NAME.replace(".", "_")} ga kotarilgan' in e_test.text:
        pass                       # allaqachon bor
    else:
        for old_s, new_s, label in t1_ops:
            if old_s not in e_test.text:
                raise SystemExit(
                    f"✗ [V112FixesTest.kt] Test1 {label} topilmadi:\n    {old_s!r}")
            e_test.text = e_test.text.replace(old_s, new_s, 1)
        e_test.changes.append(f"test: versionName → {NEW_NAME}")
        t1 = True
    print(f"   {'✓ TUZATILDI' if t1 else '– bor':14} versionName testi")

    # ── Test 2: versionCode ──
    t2 = e_test.sub(
        f'''    fun `bug2 - versionCode {OLD_CODE_IN_TEST} ga kotarilgan (19 emas)`() {{
        val g = src("app/build.gradle")
        val m = Regex("versionCode\\\\s+(\\\\d+)").find(g)
        assertNotNull("versionCode topilmadi", m)
        assertEquals(
            "⚠️ BUG#2: versionCode {OLD_CODE_IN_TEST} bo'lishi shart — aks holda Android " +
                "APK'ni yangilash deb hisoblamaydi (VERSION_DOWNGRADE)",
            "{OLD_CODE_IN_TEST}", m!!.groupValues[1]
        )
    }}''',
        f'''    fun `bug2 - versionCode {NEW_CODE} ga kotarilgan (19 emas)`() {{
        val g = src("app/build.gradle")
        val m = Regex("versionCode\\\\s+(\\\\d+)").find(g)
        assertNotNull("versionCode topilmadi", m)
        assertEquals(
            "⚠️ BUG#2: versionCode {NEW_CODE} bo'lishi shart — aks holda Android " +
                "APK'ni yangilash deb hisoblamaydi (VERSION_DOWNGRADE)",
            "{NEW_CODE}", m!!.groupValues[1]
        )
    }}''',
        f"test: versionCode → {NEW_CODE}",
        guard=f'versionCode {NEW_CODE} ga kotarilgan')
    print(f"   {'✓ TUZATILDI' if t2 else '– bor':14} versionCode testi")

    # ── Test 3: izchillik ──
    t3 = e_test.sub(
        f'''        assertEquals("versionName {OLD_NAME_IN_TEST} bo'lishi kerak", "{OLD_NAME_IN_TEST}", name)
        assertTrue("versionCode {OLD_CODE_IN_TEST} bo'lishi kerak, topildi: $code",
            code == {OLD_CODE_IN_TEST})''',
        f'''        assertEquals("versionName {NEW_NAME} bo'lishi kerak", "{NEW_NAME}", name)
        assertTrue("versionCode {NEW_CODE} bo'lishi kerak, topildi: $code",
            code == {NEW_CODE})''',
        f"test: izchillik → {NEW_NAME}/{NEW_CODE}",
        guard=f'code == {NEW_CODE}')
    print(f"   {'✓ TUZATILDI' if t3 else '– bor':14} izchillik testi")

    # ── Sarlavha izohini yangilaymiz ──
    d4 = e_test.sub(
        " *  v1.12.0 — TASDIQLANGAN FAKTLAR UCHUN REGRESSIYA TESTLARI",
        f" *  v{NEW_NAME} — TASDIQLANGAN FAKTLAR UCHUN REGRESSIYA TESTLARI",
        "test sarlavhasi → v" + NEW_NAME,
        guard=f"v{NEW_NAME} — TASDIQLANGAN")
    print(f"   {'✓ TUZATILDI' if d4 else '– bor':14} sarlavha izohi")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")

    # Gradle: eski qiymat qolmaganini tekshiramiz
    gcode = re.search(r'versionCode\s+(\d+)', e_gradle.text)
    gname = re.search(r'versionName\s+"([^"]+)"', e_gradle.text)
    if gcode.group(1) != NEW_CODE or gname.group(1) != NEW_NAME:
        print(f"   ✗ Gradle qiymati noto'g'ri: {gcode.group(1)}/{gname.group(1)}")
        return 1
    print(f"   ✓ Gradle: versionCode={gcode.group(1)}, versionName=\"{gname.group(1)}\"")

    # Test: eski qiymat qolmaganini tekshiramiz
    if f'versionName "{OLD_NAME_IN_TEST}"' in e_test.text:
        print(f"   ✗ Testda eski versionName {OLD_NAME_IN_TEST} qoldi")
        return 1
    if f'"{OLD_CODE_IN_TEST}", m!!.groupValues[1]' in e_test.text:
        print(f"   ✗ Testda eski versionCode {OLD_CODE_IN_TEST} qoldi")
        return 1
    if f'code == {OLD_CODE_IN_TEST}' in e_test.text:
        print(f"   ✗ Testda eski `code == {OLD_CODE_IN_TEST}` qoldi")
        return 1
    print("   ✓ Testlarda eski qiymat YO'Q")

    # Test: yangi qiymatlar bor
    # ⚠️ Xom faylda qochirilgan qo'shtirnoq bor:
    #    g.contains("versionName \"1.12.2\"")
    BS = chr(92)                      # orqaga qiyshiq chiziq
    VER_LIT = 'g.contains("versionName ' + BS + '"' + NEW_NAME + BS + '"")'
    checks = [
        (VER_LIT, f"versionName {NEW_NAME}"),
        (f'"{NEW_CODE}", m!!.groupValues[1]', f"versionCode {NEW_CODE}"),
        (f'"{NEW_NAME}", name', f"izchillik name {NEW_NAME}"),
        (f'code == {NEW_CODE}', f"izchillik code {NEW_CODE}"),
    ]
    for guard, label in checks:
        if guard not in e_test.text:
            print(f"   ✗ Testda {label} yo'q")
            return 1
    print("   ✓ Testlarda yangi qiymatlar joyida")

    # Testlarni sanab chiqamiz — 11 ta (o'zgarmasligi shart)
    n_tests = len(re.findall(r'@Test', e_test.text))
    if n_tests != 11:
        print(f"   ✗ Testlar soni o'zgardi: {n_tests} (11 kutilgan)")
        return 1
    print(f"   ✓ Testlar soni saqlandi: {n_tests} ta (o'zgarmagan)")

    # Boshqa (versionga aloqasiz) testlar tegmaganini tasdiqlaymiz
    for guard, label in [
        ("encrypted_url_hidden", "URL yashirish testi"),
        ("RESTART_COOLDOWN_MS", "cooldown testi"),
        ("START_NOT_STICKY", "START_NOT_STICKY testi"),
        ("connectLocked", "connectLocked testi"),
        ("pendingRestartReason", "pending testi"),
    ]:
        if guard not in e_test.text:
            print(f"   ✗ HIMOYA BUZILDI: {label} — BEKOR")
            return 1
    print("   ✓ Boshqa 9 ta test tegilmagan")

    # Gradle strukturasi saqlangan
    for guard, label in [
        ("applicationId \"com.nurvpn.app\"", "applicationId"),
        ("minSdk 24", "minSdk"),
        ("targetSdk 34", "targetSdk"),
    ]:
        if guard not in e_gradle.text:
            print(f"   ✗ {label} yo'qoldi — BEKOR")
            return 1
    print("   ✓ Gradle strukturasi saqlandi")

    total = sum(1 for d in (d1, d2, t1, t2, t3, d4) if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yozish ──")
    for e in (e_gradle, e_test):
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    print("\n" + "═" * 76)
    print(f"  ✅ TAYYOR — v{NEW_NAME} (code {NEW_CODE})")
    print("═" * 76)
    print(f"""
  BAJARILDI:
     1. FIX159 tekshirildi — 5/5 o'zgarish qo'llangan
     2. versionCode {cur_code.group(1)} → {NEW_CODE}
        versionName {cur_name.group(1)} → {NEW_NAME}
     3. 3 ta eskirgan test {NEW_NAME}/{NEW_CODE} ga moslandi

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain   ← endi 0 xato
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) GitHub'ga v{NEW_NAME} reliz qiling (APK bilan)
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
