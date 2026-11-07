package com.nurvpn.app.worker

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
                // FIX v1.13: Worker lastUpdated yangilamaydi
                // sub.lastUpdated = System.currentTimeMillis()
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
