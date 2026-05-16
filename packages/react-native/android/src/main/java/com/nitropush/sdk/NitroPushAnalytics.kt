package com.nitropush.sdk

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * One JS-shaped analytics event. Wire matches the existing `/api/sdk/events`
 * contract — moving from JS to native must not change the server schema.
 */
internal data class NlAnalyticsEvent(
    val eventType: String,
    val clientUniqueId: String,
    val appVersion: String,
    val otaVersion: Double?,
    val releaseId: String?,
    val platform: String,
    val osVersion: String?,
    val deviceModel: String?,
    val occurredAt: String,
)

/**
 * Native equivalent of the deleted JS `createAnalyticsEmitter`. Owns the
 * queue, flush timer, retry/backoff, and HTTP call. Lives entirely in
 * Kotlin so events fire even when the JS thread is asleep, mid-restart,
 * or hasn't loaded yet.
 *
 * **Threading.** All queue mutation runs on the single-thread executor;
 * callers can hammer [enqueue] from any thread without locking.
 */
internal class NlAnalytics(
    serverUrl: String,
    private val deploymentKey: String,
    private val capacity: Int = 200,
    private val flushAt: Int = 10,
    private val flushIntervalMs: Long = 30_000L,
) {
    // Trim trailing slash so we can append `/api/sdk/events` without a
    // double-slash (some reverse proxies treat them as different paths).
    private val serverUrl: String = serverUrl.trimEnd('/')

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nitropush-analytics").apply { isDaemon = true }
        }

    private val queue = ArrayDeque<NlAnalyticsEvent>()
    @Volatile private var flushing = false
    @Volatile private var stopped = false
    private var backoffMs: Long = 0
    private var pendingTimer: ScheduledFuture<*>? = null

    fun enqueue(event: NlAnalyticsEvent) {
        executor.execute {
            if (stopped) return@execute
            queue.addLast(event)
            // Drop oldest at capacity — we'd rather lose old events than
            // grow unbounded waiting on a dead network.
            while (queue.size > capacity) {
                queue.removeFirst()
            }
            if (queue.size >= flushAt) {
                flushLocked()
            } else {
                scheduleTimerLocked(flushIntervalMs)
            }
        }
    }

    fun flush() {
        executor.execute { flushLocked() }
    }

    fun stop() {
        executor.execute {
            stopped = true
            pendingTimer?.cancel(false)
            pendingTimer = null
        }
        executor.shutdown()
    }

    // MARK: - Private (must only be called on `executor`)

    private fun scheduleTimerLocked(delayMs: Long) {
        if (pendingTimer != null) return
        pendingTimer = executor.schedule({
            pendingTimer = null
            flushLocked()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flushLocked() {
        if (flushing || stopped || queue.isEmpty()) return
        val batch = queue.toList()
        queue.clear()
        flushing = true

        // Network on the executor's single worker — events are tiny and
        // serialized, no contention worries. The connection itself uses
        // its own thread under the hood; we just block this worker until
        // it returns so backoff math reflects the real RTT.
        val ok = postBatch(batch)
        flushing = false
        if (ok) {
            backoffMs = 0
        } else {
            // Re-queue head with exponential backoff up to 60s.
            for (i in batch.indices.reversed()) {
                queue.addFirst(batch[i])
            }
            while (queue.size > capacity) {
                queue.removeFirst()
            }
            backoffMs = min(max(backoffMs * 2, 1_000L), 60_000L)
            pendingTimer?.cancel(false)
            pendingTimer = null
            scheduleTimerLocked(backoffMs)
        }
    }

    private fun postBatch(batch: List<NlAnalyticsEvent>): Boolean {
        val url = try {
            URL("$serverUrl/api/sdk/events")
        } catch (_: Throwable) {
            // Bad URL — drop the batch rather than retrying forever.
            return true
        }
        val payload = JSONObject().apply {
            put("deploymentKey", deploymentKey)
            put("events", JSONArray().apply { batch.forEach { put(it.toJson()) } })
        }
        val body = payload.toString().toByteArray(Charsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            // Drain so the JVM can pool the connection.
            try { conn.inputStream.use { it.readBytes() } } catch (_: Throwable) {}
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}

private fun NlAnalyticsEvent.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("eventType", eventType)
    obj.put("clientUniqueId", clientUniqueId)
    obj.put("appVersion", appVersion)
    obj.put("platform", platform)
    obj.put("occurredAt", occurredAt)
    if (otaVersion != null) obj.put("otaVersion", otaVersion)
    if (releaseId != null) obj.put("releaseId", releaseId)
    if (osVersion != null) obj.put("osVersion", osVersion)
    if (deviceModel != null) obj.put("deviceModel", deviceModel)
    return obj
}

/**
 * Helpers shared between [NitroPushSdk] and the rollback sweep so events
 * tag with the same device fields regardless of who fires them.
 */
internal object NlAnalyticsContext {
    private val ISO: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun now(): String = ISO.get().format(Date())

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
