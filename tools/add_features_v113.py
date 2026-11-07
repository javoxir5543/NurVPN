#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ v1.13: IKKI YANGI FUNKSIYA
═══════════════════════════════════════════════════════════════════════

  FUNKSIYA 1 — Ochiq manbalarni 24 soatda avtomatik yangilash
  ────────────────────────────────────────────────────────────
    • WorkManager: PeriodicWorkRequest, 24h, NetworkType.CONNECTED
      Unique name: "open_sources_refresh", Policy: KEEP
    • HomeFragment.autoLoadPendingSubscriptions():
        shart: lastUpdated == 0  YOKI  (now - lastUpdated > 24h)
    • MainActivity.onCreate(): WorkManager'ni BIR MARTA rejalashtirish
    • Yangi fayl: worker/OpenSourcesRefreshWorker.kt

  FUNKSIYA 2 — Obuna yo'q bo'lsa, Ochiq manbalarga yo'naltirish
  ────────────────────────────────────────────────────────────
    • HomeFragment.toggleConnection():
        showNoSubscriptionDialog() → switchToTab(2) + flag
    • SettingsFragment: flag bo'lsa → scroll + highlight (1.5s)
    • fragment_settings.xml: ScrollView'ga android:id qo'shish
    • Tumbler OFF holatda qoladi (startVpn chaqirilmaydi)

═══════════════════════════════════════════════════════════════════════
 MUHIM TOPILMALAR (skript yozishdan oldin tekshirildi)
═══════════════════════════════════════════════════════════════════════

  ✓ build.gradle da androidx.work YO'Q → qo'shiladi
  ✓ switchToTab(2) MAVJUD va TO'G'RI (Ochiq manbalar Settings'da,
    Serverlar'da EMAS — SettingsFragment.setupOpenSources())
  ✓ fragment_settings.xml ildizi ScrollView, lekin android:id YO'Q
    → scroll qilish uchun id qo'shiladi
  ✓ Subscription.lastUpdated va updateIntervalHours allaqachon BOR
  ✓ SubscriptionStore.save() mavjud
  ✓ strings.xml 4 papkada: values/, values-uz/, values-ru/, values-en/

═══════════════════════════════════════════════════════════════════════
 ISHLATISH
═══════════════════════════════════════════════════════════════════════

    python3 tools/add_features_v113.py --check   # sinov (yozmaydi)
    python3 tools/add_features_v113.py           # qo'llash
"""

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKUP_DIR = ROOT / ".fix-backups"

# ═══════════════════════════════════════════════════════════════════
#  YORDAMCHI
# ═══════════════════════════════════════════════════════════════════

STAMP = datetime.now().strftime("%Y%m%d_%H%M%S")


def backup(path: Path) -> Path:
    """Faylni .fix-backups/ ga zaxiralaydi (bir marta, timestamp bilan)."""
    BACKUP_DIR.mkdir(exist_ok=True)
    try:
        rel = path.relative_to(ROOT)
    except ValueError:
        rel = Path(path.name)
    name = str(rel).replace("app/src/", "", 1).replace("/", "__")
    dest = BACKUP_DIR / f"{name}.{STAMP}.bak"
    dest.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
    print(f"   💾 zaxira: .fix-backups/{dest.name}")
    return dest


class Editor:
    """Fayl tahriri: bir marta o'qish, o'zgarishlarni kuzatish."""

    def __init__(self, path: Path, label: str):
        self.path = path
        self.label = label
        self.orig = path.read_text(encoding="utf-8")
        self.text = self.orig
        self.changes = []
        self.touched = False

    def replace_once(self, old: str, new: str, what: str,
                     guard: str = None) -> bool:
        """
        Aniq stringni bir marta almashtiradi (idempotent).

        `guard` — natijada bo'lishi kerak bo'lgan BELGI (kalit qator).
        Agar guard allaqachon mavjud bo'lsa — o'zgarish qilinmaydi.

        ⚠️ NEGA GUARD KERAK: `new in self.text` tekshiruvi ishonchsiz,
        chunki `new` blokning atrofi (indent, qo'shni qatorlar) o'zgargan
        bo'lishi mumkin. `guard` esa FAQAT biz qo'shgan qatorni bildiradi.
        """
        key = guard if guard else new
        # ⚠️ Guard bo'lsa — FAQAT unga qaraymiz. `old` hali ham mavjud
        # bo'lishi mumkin (masalan, anchor qatori o'zgarmagan), lekin
        # bizning qo'shgan belgimiz bor bo'lsa — ish allaqachon qilingan.
        if guard and guard in self.text:
            print(f"   ✓ {self.label}: {what} — allaqachon bor")
            return True
        if not guard and key in self.text and old not in self.text:
            print(f"   ✓ {self.label}: {what} — allaqachon bor")
            return True
        n = self.text.count(old)
        if n == 0:
            print(f"   ✗ {self.label}: {what} — NAQSH TOPILMADI")
            return False
        if n > 1:
            print(f"   ⚠️  {self.label}: {what} — {n} ta mos, faqat 1-chisi")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        self.touched = True
        print(f"   ✓ {self.label}: {what}")
        return True

    def replace_regex(self, pattern: str, repl: str, what: str,
                      flags=re.MULTILINE | re.DOTALL) -> bool:
        """Regex almashtirish (idempotent)."""
        if not re.search(pattern, self.text, flags):
            print(f"   ✗ {self.label}: {what} — NAQSH TOPILMADI")
            return False
        new_text, n = re.subn(pattern, repl, self.text, count=1, flags=flags)
        if new_text == self.text:
            print(f"   ✓ {self.label}: {what} — o'zgarish yo'q")
            return True
        self.text = new_text
        self.changes.append(what)
        self.touched = True
        print(f"   ✓ {self.label}: {what}")
        return True

    def insert_after(self, anchor: str, block: str, what: str) -> bool:
        """Anchor'dan keyin blok qo'shadi (idempotent)."""
        if block.strip() in self.text:
            print(f"   ✓ {self.label}: {what} — allaqachon bor")
            return True
        if anchor not in self.text:
            print(f"   ✗ {self.label}: {what} — ANCHOR TOPILMADI")
            return False
        self.text = self.text.replace(anchor, anchor + block, 1)
        self.changes.append(what)
        self.touched = True
        print(f"   ✓ {self.label}: {what}")
        return True

    def save(self, check: bool):
        if not self.touched:
            return
        if check:
            print(f"   ⏭  {self.label}: --check (yozilmadi)")
            return
        backup(self.path)
        self.path.write_text(self.text, encoding="utf-8")
        print(f"   💾 yozildi: {self.path.relative_to(ROOT)}")


# ═══════════════════════════════════════════════════════════════════
#  1. build.gradle — WorkManager qo'shish
# ═══════════════════════════════════════════════════════════════════

def step_gradle(check: bool):
    print("\n═══ 1/7 build.gradle — androidx.work ═══")
    p = ROOT / "app/build.gradle"
    e = Editor(p, "build.gradle")

    if "androidx.work:work-runtime-ktx" in e.text:
        print("   ✓ allaqachon bor")
    else:
        e.insert_after(
            "    implementation 'androidx.recyclerview:recyclerview:1.3.2'\n",
            "    implementation 'androidx.work:work-runtime-ktx:2.9.0'\n",
            "work-runtime-ktx:2.9.0 qo'shildi")

    e.save(check)
    return e.changes


# ═══════════════════════════════════════════════════════════════════
#  2. OpenSourcesRefreshWorker.kt — yangi fayl
# ═══════════════════════════════════════════════════════════════════

WORKER_SRC = '''package com.nurvpn.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.storage.SubscriptionStore
import java.util.concurrent.TimeUnit

/**
 * ★ v1.13: Ochiq manbalarni orqa fonda yangilash.
 *
 * Har 24 soatda bir marta ishlaydi. Ilova ochiq bo'lmasa ham ishlaydi
 * (WorkManager). Tarmoq bo'lmasa — Result.retry() (exponential backoff).
 *
 * ⚠️ MUHIM: `lastUpdated` FAQAT muvaffaqiyatli yangilangandan keyin
 * o'zgartiriladi. Aks holda xatoda timestamp yangilanib, keyingi urinish
 * 24 soatdan keyin bo'ladi — foydalanuvchi butun kun eski ma'lumot bilan
 * qoladi.
 */
class OpenSourcesRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_NAME = "open_sources_refresh"
        private const val TAG = "NurVPN-WORKER"

        /** 24 soat — yangilash oralig'i. */
        private const val INTERVAL_HOURS = 24L

        /**
         * ★ WorkManager'ni BIR MARTA rejalashtiradi.
         * `KEEP` policy — mavjud ish bo'lsa qayta rejalashtirmaydi.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<OpenSourcesRefreshWorker>(
                    INTERVAL_HOURS, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .addTag(UNIQUE_NAME)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                android.util.Log.i(TAG,
                    "OpenSourcesRefreshWorker rejalashtirildi (${INTERVAL_HOURS}h)")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "schedule xato: ${t.message}", t)
            }
        }
    }

    override suspend fun doWork(): Result {
        android.util.Log.i(TAG, "doWork boshlandi (attempt=$runAttemptCount)")

        return try {
            val ctx = applicationContext
            val enabled = OpenSourceStore.getEnabled(ctx)
            if (enabled.isEmpty()) {
                android.util.Log.i(TAG, "Yoqilgan ochiq manba yo'q — chiqamiz")
                return Result.success()
            }

            val subs = SubscriptionStore.load(ctx)
            val now = System.currentTimeMillis()
            val intervalMs = INTERVAL_HOURS * 60 * 60 * 1000L

            var updated = 0
            var failed = 0

            for (open in OpenSourceCatalog.ALL) {
                if (open.isAwg) continue          // AWG — inline, yangilanmaydi
                if (open.id !in enabled) continue
                val url = open.url ?: continue

                val sub = subs.firstOrNull { it.id == OpenSourceStore.subId(open.id) }
                    ?: continue

                if (now - sub.lastUpdated < intervalMs) continue   // yangi

                val body = fetch(url)
                if (body == null) {
                    failed++
                    continue
                }
                // ⚠️ lastUpdated FAQAT shu yerda — muvaffaqiyatdan keyin
                sub.lastUpdated = System.currentTimeMillis()
                updated++
            }

            if (updated > 0) {
                SubscriptionStore.save(ctx, subs)
                android.util.Log.i(TAG,
                    "doWork: $updated yangilandi, $failed xato")
            }

            when {
                updated > 0 -> Result.success()
                failed > 0 && runAttemptCount < 3 -> Result.retry()
                else -> Result.success()
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "doWork xato: ${t.message}", t)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    /** Oddiy HTTP GET. Xato bo'lsa null. */
    private fun fetch(url: String): String? {
        return try {
            val conn = java.net.URL(url).openConnection()
                    as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "NurVPN/1.13")
            if (conn.responseCode !in 200..299) {
                android.util.Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text.ifBlank { null }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "fetch xato $url: ${t.message}")
            null
        }
    }
}
'''


def step_worker(check: bool):
    print("\n═══ 2/7 OpenSourcesRefreshWorker.kt ═══")
    p = ROOT / "app/src/main/java/com/nurvpn/app/worker/OpenSourcesRefreshWorker.kt"
    if p.exists() and p.read_text(encoding="utf-8") == WORKER_SRC:
        print("   ✓ allaqachon bor va bir xil")
        return []
    if check:
        print(f"   ⏭  --check (yozilmadi): {p.relative_to(ROOT)}")
        return ["worker fayli"]
    p.parent.mkdir(parents=True, exist_ok=True)
    if p.exists():
        backup(p)
    p.write_text(WORKER_SRC, encoding="utf-8")
    print(f"   ✓ yaratildi: {p.relative_to(ROOT)}")
    return ["worker fayli"]


# ═══════════════════════════════════════════════════════════════════
#  3. MainActivity.kt — WorkManager rejalashtirish
# ═══════════════════════════════════════════════════════════════════

MA_SCHEDULE_BLOCK = '''
        // ★ v1.13: Ochiq manbalarni 24 soatda avtomatik yangilash.
        // `KEEP` policy — bir marta rejalashtiriladi.
        com.nurvpn.app.worker.OpenSourcesRefreshWorker.schedule(this)
'''


def step_main_activity(check: bool):
    print("\n═══ 3/7 MainActivity.kt — WorkManager ═══")
    p = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"
    e = Editor(p, "MainActivity.kt")

    anchor = ('        if (savedInstanceState == null) switchTab(TAB_HOME)\n')
    if "OpenSourcesRefreshWorker.schedule(this)" in e.text:
        print("   ✓ allaqachon bor")
    else:
        e.insert_after(anchor, MA_SCHEDULE_BLOCK,
                       "OpenSourcesRefreshWorker.schedule(this) qo'shildi")

    e.save(check)
    return e.changes


# ═══════════════════════════════════════════════════════════════════
#  4. HomeFragment.kt — autoLoad sharti + toggleConnection
# ═══════════════════════════════════════════════════════════════════

OLD_PENDING = '''            val pending = a.subscriptions.filter {
                it.lastUpdated == 0L && attemptedSubs.add(it.url)
            }'''

NEW_PENDING = '''            // ★ v1.13: 24 soatdan eski obunalar ham yangilanadi.
            val staleMs = 24L * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            val pending = a.subscriptions.filter {
                val never = it.lastUpdated == 0L
                val stale = it.lastUpdated > 0L && (now - it.lastUpdated) > staleMs
                (never || stale) && attemptedSubs.add(it.url)
            }'''

OLD_NO_SUB = '''        if (a0 != null &&
            a0.subscriptions.isEmpty() &&
            a0.servers.isEmpty() &&
            a0.awgConfigs.isEmpty()) {
            showNoSubscriptionDialog()
            return
        }'''

NEW_NO_SUB = '''        if (a0 != null &&
            a0.subscriptions.isEmpty() &&
            a0.servers.isEmpty() &&
            a0.awgConfigs.isEmpty()) {
            // ★ v1.13: obuna yo'q → Ochiq manbalarga yo'naltiramiz.
            // Tumbler OFF holatda qoladi (startVpn() chaqirilmaydi).
            try {
                a0.prefs.edit()
                    .putBoolean("scroll_to_open_sources", true)
                    .apply()
                a0.switchToTab(2)   // 2 = Settings (Ochiq manbalar shu yerda)
                android.widget.Toast.makeText(context,
                    getString(R.string.no_subscription_redirect),
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-DBG",
                    "open sources redirect xato", t)
            }
            return
        }'''


def step_home_fragment(check: bool):
    print("\n═══ 4/7 HomeFragment.kt — shart + yo'naltirish ═══")
    p = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
    e = Editor(p, "HomeFragment.kt")

    e.replace_once(OLD_PENDING, NEW_PENDING,
                   "autoLoad: 24 soatdan eski obunalar ham yangilanadi",
                   guard="val staleMs = 24L * 60 * 60 * 1000")
    e.replace_once(OLD_NO_SUB, NEW_NO_SUB,
                   "toggleConnection: obuna yo'q → Ochiq manbalarga yo'naltirish",
                   guard='"scroll_to_open_sources"')

    e.save(check)
    return e.changes


# ═══════════════════════════════════════════════════════════════════
#  5. fragment_settings.xml — ScrollView'ga id
# ═══════════════════════════════════════════════════════════════════

OLD_SV = '''<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg"
    android:fillViewport="true">'''

NEW_SV = '''<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/settings_scroll"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg"
    android:fillViewport="true">'''


def step_settings_layout(check: bool):
    print("\n═══ 5/7 fragment_settings.xml — ScrollView id ═══")
    p = ROOT / "app/src/main/res/layout/fragment_settings.xml"
    e = Editor(p, "fragment_settings.xml")
    e.replace_once(OLD_SV, NEW_SV, "ScrollView'ga @+id/settings_scroll",
                   guard='android:id="@+id/settings_scroll"')
    e.save(check)
    return e.changes


# ═══════════════════════════════════════════════════════════════════
#  6. SettingsFragment.kt — flag tekshiruvi + scroll + highlight
# ═══════════════════════════════════════════════════════════════════

OLD_SETUP_CALL = '''        binding = false
        setupOpenSources(v)'''

NEW_SETUP_CALL = '''        binding = false
        setupOpenSources(v)
        // ★ v1.13: obuna yo'qligi sababli kelgan bo'lsa — scroll qilamiz
        scrollToOpenSourcesIfRequested(v)'''

SCROLL_FN = '''
    // ═══════════════════════════════════════════════════════════════
    //  ★ v1.13: "Ochiq manbalar" bo'limiga scroll + highlight
    //
    //  HomeFragment.toggleConnection() da obuna yo'q bo'lsa
    //  `scroll_to_open_sources` flag o'rnatiladi va switchToTab(2)
    //  chaqiriladi. Bu metod flag bo'lsa scroll qiladi va bir marta
    //  o'chiradi (keyingi tab almashishda qayta scroll qilmaydi).
    // ═══════════════════════════════════════════════════════════════
    private fun scrollToOpenSourcesIfRequested(root: View) {
        try {
            val ctx = context ?: return
            val prefs = ctx.getSharedPreferences("main", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("scroll_to_open_sources", false)) return
            // ★ Bir martalik — darhol o'chiramiz
            prefs.edit().putBoolean("scroll_to_open_sources", false).apply()

            val sv = root.findViewById<android.widget.ScrollView>(R.id.settings_scroll) ?: return
            val box = root.findViewById<android.view.View>(R.id.open_source_container) ?: return

            sv.post {
                try {
                    val y = box.top
                    sv.smoothScrollTo(0, y)
                } catch (_: Throwable) {}
            }

            // ★ Highlight: fon rangini 1.5 soniya yorqin qilib, keyin qaytaramiz
            try {
                val orig = box.background
                box.setBackgroundColor(android.graphics.Color.parseColor("#33FFC107"))
                box.postDelayed({
                    try { box.background = orig } catch (_: Throwable) {}
                }, 1500L)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "scrollToOpenSources xato", t)
        }
    }
'''


def step_settings_fragment(check: bool):
    print("\n═══ 6/7 SettingsFragment.kt — scroll + highlight ═══")
    p = ROOT / "app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt"
    e = Editor(p, "SettingsFragment.kt")

    # 6a) setupOpenSources() chaqiruvidan keyin scroll tekshiruvi
    e.replace_once(OLD_SETUP_CALL, NEW_SETUP_CALL,
                   "scrollToOpenSourcesIfRequested() chaqiruvi",
                   guard="scrollToOpenSourcesIfRequested(v)")

    # 6b) Yangi funksiyani setupOpenSources() dan OLDIN qo'shamiz
    if "private fun scrollToOpenSourcesIfRequested" in e.text:
        print("   ✓ scrollToOpenSourcesIfRequested — allaqachon bor")
    else:
        anchor = "    private fun setupOpenSources(view: View) {"
        if anchor not in e.text:
            print("   ✗ setupOpenSources anchor topilmadi")
        else:
            e.text = e.text.replace(anchor, SCROLL_FN.rstrip() + "\n\n" + anchor, 1)
            e.changes.append("scrollToOpenSourcesIfRequested() funksiyasi")
            e.touched = True
            print("   ✓ scrollToOpenSourcesIfRequested() funksiyasi qo'shildi")

    # 6c) ⚠️ DUBLIKAT TOZALASH: agar chaqiruv bir necha marta bo'lsa,
    #     faqat bittasini qoldiramiz. (Avvalgi skript versiyasi xatosi.)
    dup = "        scrollToOpenSourcesIfRequested(v)\n"
    cnt = e.text.count(dup)
    if cnt > 1:
        # Birinchisini saqlab, qolganlarini o'chiramiz
        first = e.text.find(dup)
        head = e.text[:first + len(dup)]
        tail = e.text[first + len(dup):].replace(dup, "")
        e.text = head + tail
        e.changes.append(f"dublikat chaqiruv tozalandi ({cnt}→1)")
        e.touched = True
        print(f"   ✓ dublikat chaqiruv tozalandi ({cnt}→1)")

    e.save(check)
    return e.changes


# ═══════════════════════════════════════════════════════════════════
#  7. strings.xml — yangi matn (4 til papkasi)
# ═══════════════════════════════════════════════════════════════════

STR_KEY = "no_subscription_redirect"
STRINGS = {
    "values": "Obuna topilmadi. Ochiq manbalardan birini yoqing.",
    "values-uz": "Obuna topilmadi. Ochiq manbalardan birini yoqing.",
    "values-ru": "Подписка не найдена. Включите один из открытых источников.",
    "values-en": "No subscription found. Enable one of the open sources.",
}


def step_strings(check: bool):
    print("\n═══ 7/7 strings.xml — yangi matn ═══")
    results = []
    for folder, text in STRINGS.items():
        p = ROOT / f"app/src/main/res/{folder}/strings.xml"
        if not p.exists():
            print(f"   ⚠️  {folder}/strings.xml yo'q — o'tkazib yuborildi")
            continue
        e = Editor(p, f"{folder}/strings.xml")
        if f'name="{STR_KEY}"' in e.text:
            print(f"   ✓ {folder}: allaqachon bor")
            continue
        e.replace_once("</resources>",
                       f'    <string name="{STR_KEY}">{text}</string>\n</resources>',
                       f"{folder}: {STR_KEY}",
                       guard=f'name="{STR_KEY}"')
        e.save(check)
        results += e.changes
    return results


# ═══════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="faqat tekshirish, fayllarni o'zgartirmaslik")
    args = ap.parse_args()

    print("╔" + "═" * 62 + "╗")
    print("║  ★ v1.13: WorkManager + Ochiq manbalarga yo'naltirish" + " " * 11 + "║")
    print("╚" + "═" * 62 + "╝")
    if args.check:
        print("  REJIM: --check (fayllar YOZILMAYDI)\n")

    all_changes = []
    all_changes += step_gradle(args.check)
    all_changes += step_worker(args.check)
    all_changes += step_main_activity(args.check)
    all_changes += step_home_fragment(args.check)
    all_changes += step_settings_layout(args.check)
    all_changes += step_settings_fragment(args.check)
    all_changes += step_strings(args.check)

    print("\n" + "═" * 64)
    if args.check:
        print("✅ TEKSHIRUV TUGADI — fayllar o'zgartirilmadi")
        print("   Qo'llash: python3 tools/add_features_v113.py")
    elif all_changes:
        print(f"✅ TAYOR — {len(all_changes)} ta o'zgarish:")
        for c in all_changes:
            print(f"   • {c}")
        print("\n   Keyingi qadam:")
        print("     ./gradlew assembleDebug")
    else:
        print("✅ O'zgarish yo'q — hamma narsa allaqachon bor")
    print("═" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())
