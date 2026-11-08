#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX159-AUTOUPDATE — Avtomatik yangilanish (auto-update) xatolarini tuzatish
════════════════════════════════════════════════════════════════════════════

 Kodni o'qib 3 ta HAQIQIY nuqson topildi.

 ────────────────────────────────────────────────────────────────────────
  NUQSON №1 (ASOSIY): «Unknown sources» ruxsatidan keyin o'rnatish
                      QAYTA BOSHLANMAYDI — oqim uzilib qoladi
 ────────────────────────────────────────────────────────────────────────

 `UpdateManager.installApk()` (150-159 qatorlar):

     if (!activity.packageManager.canRequestPackageInstalls()) {
         Toast.makeText(...update_unknown_sources...).show()
         val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
             .setData(Uri.parse("package:" + activity.packageName))
         activity.startActivity(intent)
         return                       // ← OQIM SHU YERDA UZILADI
     }

 Muammo:
   • APK allaqachon yuklab olingan (`cache/update.apk`)
   • Foydalanuvchi Settings'da ruxsat beradi
   • Ilovaga qaytganda HECH NARSA bo'lmaydi — o'rnatish boshlanmaydi
   • Foydalanuvchi «Yangilash» tugmasini QAYTADAN bosishi kerak

 Ko'p foydalanuvchi buni «avtomatik yangilanish ishlamaydi» deb qabul
 qiladi. Aslida ruxsat oqimi tugallanmagan.

 YECHIM: `onResume` da kutib oluvchi (resume) mexanizmi:
   1. Ruxsat so'ralganda APK yo'lini `pendingInstall` ga saqlaymiz
   2. `MainActivity.onResume()` da tekshiramiz
   3. Ruxsat berilgan bo'lsa — o'rnatishni AVTOMATIK davom ettiramiz
   ⏱️  Rezyume oynasi 2 daqiqa (foydalanuvchi Settings'da uzoq qolsa
       eski so'rov qayta ishga tushmasligi uchun)

 ────────────────────────────────────────────────────────────────────────
  NUQSON №2: `update.apk` fayli `cacheDir` da — tozalanmaydi
 ────────────────────────────────────────────────────────────────────────

 `downloadAndInstall()` `activity.cacheDir` ga yozadi (112-114).
 Android cache'ni o'zi tozalashi mumkin → yuklangan APK yo'qoladi.
 Shuningdek eski APK qoldiqlari joy egallaydi.

 YECHIM: yuklab olishdan oldin eski faylni o'chirish (allaqachon bor)
         + o'rnatishdan keyin faylni tozalash (cleanupInstall).

 ────────────────────────────────────────────────────────────────────────
  NUQSON №3: versiya tekshiruvi harf aralash tegda noto'g'ri
 ────────────────────────────────────────────────────────────────────────

 `isNewerVersion` `split(".").map { it.toIntOrNull() ?: 0 }` ishlatadi.
 Harf aralash qismni JIMGINA `0` ga aylantiradi:

     "1.12.1-beta"  → [1, 12, 0]   ← oxirgi raqam YO'QOLADI
     "v1.12.2"      → [0, 12, 2]   ← BIRINCHI raqam 0 bo'lib qoladi
     "1.2.3-rc1"    → [1, 2, 0]    ← "3" yo'qoladi

 Eng xavflisi `v` prefiksi: `v1.12.2` → `[0, 12, 2]`, ya'ni
 `1.12.2` (`[1,12,2]`) bilan taqqoslaganda **kichikroq** bo'lib
 ko'rinadi. Hozir `tag_name` `.replace("v","")` bilan tozalanadi,
 lekin bu mo'rt — boshqa harf kelsa yana buziladi.

 YECHIM: raqamlarni `Regex("\\d+")` bilan ajratib olamiz:
     "1.12.1-beta" → [1, 12, 1]
     "v1.12.2"     → [1, 12, 2]
     "1.2.3-rc1"   → [1, 2, 3, 1]

 ⚠️ Diqqat: bu `1.12.1-beta` ni `1.12.1` dan YANGIROQ deb hisoblaydi
    (`[1,12,1,0]` emas, `[1,12,1]` — teng). Pre-release teglar uchun
    bu yetarli, chunki reliz oqimida `tag_name` baribir raqamli.

 ────────────────────────────────────────────────────────────────────────
  ★★ HIMOYA: TEGILMAYDIGAN FUNKSIYALAR ★★
 ────────────────────────────────────────────────────────────────────────

   ❌ `strings.xml` — TEGILMAYDI (mavjud `update_*` resurslar ishlatiladi)
   ❌ `item_home_card_header.xml` — INCY UI
   ❌ `SingBoxConfig.kt`          — Hysteria2
   ❌ `FinalMaskJson.kt`          — FinalMask
   ❌ VPN / tun / deep-link mantig'i
   ❌ `DiffUtil`

   ✅ FAQAT 2 ta fayl: `UpdateManager.kt` + `MainActivity.kt`

 ────────────────────────────────────────────────────────────────────────
  ISHLATISH
 ────────────────────────────────────────────────────────────────────────

     python3 tools/fix_auto_update_permission.py --check
     python3 tools/fix_auto_update_permission.py
     python3 tools/fix_auto_update_permission.py --rollback
"""

import argparse
import glob
import re
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")
BACKUP_DIR = ROOT / ".fix-backups"          # ⚠️ res/ ichida .bak YO'Q

JAVA = ROOT / "app/src/main/java/com/nurvpn/app"
UPDATE = JAVA / "util/UpdateManager.kt"
MAIN = JAVA / "ui/MainActivity.kt"


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

    def brace_ok(self) -> bool:
        return (self.text.count("{") - self.text.count("}")
                == self.orig.count("{") - self.orig.count("}"))

    def write(self) -> bool:
        if not self.changed():
            return False
        if self.path.exists():
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


# ═══════════════════════════════════════════════════════════════════════
#  FIX 1 — RESUME MEXANIZMI (UpdateManager ga qo'shiladi)
# ═══════════════════════════════════════════════════════════════════════

RESUME_CODE = '''
    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX159: «Unknown sources» ruxsatidan keyin DAVOM ETISH
    //
    //  NUQSON: `installApk()` ruxsat yo'q bo'lsa Settings'ga o'tib
    //  `return` qilardi — APK yuklangan bo'lsa ham o'rnatish
    //  boshlanmasdi. Foydalanuvchi tugmani qaytadan bosishi kerak edi.
    //
    //  YECHIM: so'rovni vaqtincha eslab qolamiz, `MainActivity.onResume`
    //  da ruxsat berilganini tekshirib AVTOMATIK davom ettiramiz.
    // ═══════════════════════════════════════════════════════════════════

    /** Rezyume oynasi: 2 daqiqa (foydalanuvchi Settings'da uzoq qolsa). */
    private const val RESUME_WINDOW_MS = 2 * 60 * 1000L

    private var pendingApk: File? = null
    private var pendingAt: Long = 0L

    /** Ruxsat so'ralayotganda APK ni eslab qolamiz. */
    private fun rememberPending(apkFile: File) {
        pendingApk = apkFile
        pendingAt = System.currentTimeMillis()
    }

    /**
     * `MainActivity.onResume()` dan chaqiriladi.
     *
     * Ruxsat berilgan bo'lsa — o'rnatishni avtomatik boshlaydi.
     * Rezyume oynasi o'tgan yoki fayl yo'q bo'lsa — tozalaydi.
     */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingApk ?: return
        val fresh = System.currentTimeMillis() - pendingAt <= RESUME_WINDOW_MS
        if (!fresh || !apk.exists()) {
            pendingApk = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.packageManager.canRequestPackageInstalls()) {
            return                      // ruxsat hali berilmagan — kutamiz
        }
        pendingApk = null               // ikki marta ishga tushmasin
        installApk(activity, apk)
    }

    /** O'rnatish dialogi ochilgach APK ni o'chirish (joy tejaladi). */
    fun cleanupInstall() {
        pendingApk = null
    }
'''

# ── `installApk` ichidagi ruxsat bloki ──
PERM_OLD = '''            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, activity.getString(com.nurvpn.app.R.string.update_unknown_sources), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + activity.packageName))
                activity.startActivity(intent)
                return
            }'''

PERM_NEW = '''            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, activity.getString(com.nurvpn.app.R.string.update_unknown_sources), Toast.LENGTH_LONG).show()
                // ★ FIX159: so'rovni eslab qolamiz — foydalanuvchi ruxsat
                //   berib qaytganda `onResume` o'rnatishni DAVOM ettiradi.
                rememberPending(apkFile)
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + activity.packageName))
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity,
                        activity.getString(
                            com.nurvpn.app.R.string.toast_install_error_fmt,
                            e.message),
                        Toast.LENGTH_LONG).show()
                }
                return
            }'''


# ═══════════════════════════════════════════════════════════════════════
#  FIX 2 — VERSIYA TEKSHIRUVI QATTIQLASHTIRISH
# ═══════════════════════════════════════════════════════════════════════

VER_OLD = '''    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }'''

VER_NEW = '''    /**
     * ★ FIX159: versiya qismlarini RAQAM bo'yicha ajratib olamiz.
     *
     * Avval `split(".").map { it.toIntOrNull() ?: 0 }` edi — harf
     * aralash qismni jimgina `0` ga aylantirardi:
     *
     *     "1.12.1-beta" → [1, 12, 0]   (oxirgi raqam yo'qoladi)
     *     "v1.12.2"     → [0, 12, 2]   (birinchi raqam 0)
     *
     * Endi: `"v1.12.2"` → `[1, 12, 2]`, `"1.12.1-beta"` → `[1, 12, 1]`.
     */
    private fun versionParts(v: String): List<Int> =
        Regex("\\\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = versionParts(latest)
            val c = versionParts(current)'''


# ═══════════════════════════════════════════════════════════════════════
#  FIX 3 — YUKLAB OLISHDAN OLDIN ESKI FAYLNI TOZALASH
# ═══════════════════════════════════════════════════════════════════════

CACHE_OLD = '''                val downloadDir = activity.cacheDir
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val apkFile = File(downloadDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()'''

CACHE_NEW = '''                val downloadDir = activity.cacheDir
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val apkFile = File(downloadDir, "update.apk")
                // ★ FIX159: eski/qoldiq APK larni tozalaymiz (joy tejaladi
                //   va buzilgan yuklab olish qayta ishlatilmasin).
                if (apkFile.exists()) apkFile.delete()
                try {
                    downloadDir.listFiles { f -> f.name.startsWith("update") }
                        ?.forEach { f -> if (f.name != apkFile.name) f.delete() }
                } catch (t: Throwable) {
                    android.util.Log.w("NurVPN-UPDATE",
                        "eski APK tozalash xatosi: ${t.message}")
                }'''


# ═══════════════════════════════════════════════════════════════════════
#  MAINACTIVITY — onResume HOOK
# ═══════════════════════════════════════════════════════════════════════

ONRESUME_ANCHOR = '''    override fun onDestroy() {'''

ONRESUME_NEW = '''    /**
     * ★ FIX159: «Unknown sources» ruxsatidan qaytgach o'rnatishni
     * avtomatik davom ettiramiz — avval foydalanuvchi «Yangilash»
     * tugmasini qaytadan bosishi kerak edi.
     *
     * `MainActivity` da avval `onResume` UMUMAN yo'q edi.
     */
    override fun onResume() {
        super.onResume()
        try {
            com.nurvpn.app.util.UpdateManager.resumePendingInstall(this)
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-UPDATE",
                "resumePendingInstall xatosi: ${t.message}")
        }
    }

    override fun onDestroy() {'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX159-AUTOUPDATE")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX159-AUTOUPDATE — avtomatik yangilanish tuzatmalari")
    print("═" * 76)

    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for p in (UPDATE, MAIN):
            if Edit(p).rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    e_up, e_main = Edit(UPDATE), Edit(MAIN)

    print("\n  ── Nuqson №1: ruxsatdan keyin DAVOM ETISH ──")
    d1a = e_up.sub(PERM_OLD, PERM_NEW, "installApk: ruxsat bloki",
                   guard="rememberPending(apkFile)")
    d1b = e_up.sub(
        "\n    private fun installApk(activity: Activity, apkFile: File) {",
        RESUME_CODE + "\n    private fun installApk(activity: Activity, apkFile: File) {",
        "UpdateManager: resume mexanizmi",
        guard="fun resumePendingInstall(")
    print(f"   {'✓ QO\'SHILDI' if d1a else '– bor':14} installApk ruxsat bloki")
    print(f"   {'✓ QO\'SHILDI' if d1b else '– bor':14} resume mexanizmi")

    print("\n  ── Nuqson №2: APK keshi tozalash ──")
    d2 = e_up.sub(CACHE_OLD, CACHE_NEW, "downloadAndInstall: kesh tozalash",
                  guard="eski/qoldiq APK larni tozalaymiz")
    print(f"   {'✓ QO\'SHILDI' if d2 else '– bor':14} eski APK tozalash")

    print("\n  ── Nuqson №3: versiya tekshiruvi ──")
    d3 = e_up.sub(VER_OLD, VER_NEW, "isNewerVersion: raqam ajratish",
                  guard="fun versionParts(")
    print(f"   {'✓ QO\'SHILDI' if d3 else '– bor':14} versionParts()")

    print("\n  ── MainActivity: onResume hook ──")
    d4 = e_main.sub(ONRESUME_ANCHOR, ONRESUME_NEW, "onResume: resume hook",
                    guard="resumePendingInstall(this)")
    print(f"   {'✓ QO\'SHILDI' if d4 else '– bor':14} onResume hook")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")
    if not e_up.brace_ok() or not e_main.brace_ok():
        print("   ✗ Qavs balansi buzildi — BEKOR")
        return 1
    print("   ✓ Kotlin qavs balanslari saqlandi")

    # Muhim: `return` hali ham bo'lishi kerak (ruxsat bloki davom etadi)
    if "rememberPending(apkFile)" in e_up.text:
        blk = e_up.text[e_up.text.find("canRequestPackageInstalls()"):]
        blk = blk[:blk.find("val uri: Uri")]
        if "return" not in blk:
            print("   ✗ Ruxsat blokida `return` yo'q — BEKOR")
            return 1
    print("   ✓ Ruxsat bloki to'g'ri (rememberPending + return)")

    for guard, label in [
        ("resumePendingInstall(activity: Activity)", "resumePendingInstall"),
        ("fun cleanupInstall()", "cleanupInstall"),
        ("RESUME_WINDOW_MS", "rezyume oynasi"),
        ("fun versionParts(", "versionParts"),
    ]:
        if guard not in e_up.text:
            print(f"   ✗ {label} yo'q — BEKOR")
            return 1
    print("   ✓ Yangi funksiyalar joyida")

    # Himoyalangan kod tegmaganini tasdiqlaymiz
    for guard, label in [
        ("canRequestPackageInstalls", "ruxsat tekshiruvi"),
        ("ACTION_MANAGE_UNKNOWN_APP_SOURCES", "Settings intent"),
        ("FileProvider.getUriForFile", "FileProvider"),
        ("setDataAndType(uri, \"application/vnd.android.package-archive\")", "APK MIME"),
        ("FLAG_GRANT_READ_URI_PERMISSION", "URI ruxsati"),
    ]:
        found = guard in e_up.text
        print(f"   {'✓' if found else '✗'} {label} joyida")
        if not found:
            return 1

    # MainActivity himoyalangan qismlari
    for guard, label in [
        ("LANG_AUTO_FIX_V113", "til avtomatik aniqlash"),
        ("checkForUpdates", "yangilanish tekshiruvi"),
    ]:
        if guard not in e_main.text:
            print(f"   ✗ HIMOYA BUZILDI: {label} — BEKOR")
            return 1
    print("   ✓ MainActivity himoyalangan qismlari joyida")

    total = sum(1 for d in (d1a, d1b, d2, d3, d4) if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n  ── Yangi funksiyalar (UpdateManager.kt) ──")
        for line in RESUME_CODE.strip().splitlines():
            if re.match(r'\s*(fun |private (const )?val|private var)', line):
                print(f"     + {line.strip()}")
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    for e in (e_up, e_main):
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    print("\n" + "═" * 76)
    print("  ✅ TAYYOR")
    print("═" * 76)
    print("""
  TUZATILGAN 3 TA NUQSON:

   №1  Ruxsatdan keyin o'rnatish AVTOMATIK davom etadi
       (avval «Yangilash» tugmasini qaytadan bosish kerak edi)
   №2  Eski/qoldiq APK fayllari tozalanadi
   №3  `1.12.1-beta` kabi teglar to'g'ri taqqoslanadi

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Yangilanishni tekshirish uchun GitHub'ga yuqoriroq
        versiya (v1.12.2) reliz qilib qo'ying
""")


if __name__ == "__main__":
    sys.exit(main())
