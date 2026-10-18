package ir.example.divaraccounts

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Checks every account for unread conversations from real users (Divar's "Postchi" system
 * messages are ignored) and posts one notification when something new arrived.
 */
class UnreadPollingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Never probe the same WebView profiles concurrently; it would race the stored baseline.
        if (!UnreadPollingGate.mutex.tryLock()) {
            Log.i(TAG, "Skipping overlapping unread scan")
            return Result.retry()
        }
        try {
            val app = applicationContext
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                Log.w(TAG, "WebView Multi-Profile is not supported; unread scan skipped")
                return Result.success()
            }
            NotificationHelper.createChannel(app)

            var anyFailed = false
            for (account in AccountStore(app).all()) {
                if (isStopped) return Result.retry()
                var result: ProbeResult? = null
                try {
                    result = withTimeoutOrNull(PROBE_TIMEOUT_MS) { DivarUnreadProbe.scan(app, account.profileName) }
                } catch (e: CancellationException) {
                    throw e // Let WorkManager reschedule when a constraint stops this scan.
                } catch (e: Exception) {
                    Log.w(TAG, "Probe failed for account ${account.id.take(8)}", e)
                }

                when (result ?: ProbeResult.Unknown) {
                    is ProbeResult.Unread -> {
                        val fingerprints = (result as ProbeResult.Unread).fingerprints
                        val hasNew = UnreadStateStore.applyScan(app, account.id, fingerprints)
                        if (hasNew) NotificationHelper.showNewMessage(app, account)
                        else if (fingerprints.isEmpty()) NotificationHelper.cancel(app, account.id)
                    }
                    ProbeResult.LoggedOut -> UnreadStateStore.markNeedsLogin(app, account.id)
                    ProbeResult.Unknown -> {
                        // Page not recognised or network problem: keep the previous state untouched.
                        Log.w(TAG, "Unread state unavailable for account ${account.id.take(8)}")
                        anyFailed = true
                    }
                }
                app.sendBroadcast(Intent(NotificationHelper.ACTION_UNREAD_UPDATED).setPackage(app.packageName))
            }
            // A failed account is retried with WorkManager's backoff instead of waiting a full period.
            return if (anyFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        } finally {
            UnreadPollingGate.mutex.unlock()
        }
    }

    private companion object {
        const val TAG = "DivarUnreadWorker"
        const val PROBE_TIMEOUT_MS = 40_000L
        const val MAX_RETRIES = 2
    }
}

private object UnreadPollingGate {
    val mutex = Mutex()
}

private sealed class ProbeResult {
    /** [fingerprints] describe every unread conversation from a real user (empty = nothing unread). */
    data class Unread(val fingerprints: Set<String>) : ProbeResult()
    object LoggedOut : ProbeResult()
    object Unknown : ProbeResult()
}

/** Loads Divar's official chat list in a hidden WebView under the account's own profile. */
private object DivarUnreadProbe {
    private const val CHAT_URL = "https://divar.ir/chat/"
    private const val POLL_MS = 900L
    private const val FIRST_POLL_DELAY_MS = 1_200L
    private const val OVERALL_TIMEOUT_MS = 34_000L

    /**
     * Reads the conversation list. Conversations of the official "Postchi" account are skipped.
     * A conversation is unread when it shows the new-message indicator next to its time.
     */
    private const val READ_LIST_JS = """(function(){
        try {
            const txt = s => (s || '').replace(/\s+/g, ' ').trim();
            const chatUi = !!document.querySelector('.kt-chat-nav-bar, .kt-tabs, [data-indexincache], .kt-conversation');
            if (!chatUi) {
                const body = txt(document.body ? (document.body.innerText || document.body.textContent) : '');
                return JSON.stringify({s: body.includes('ورود') ? 'login' : 'loading'});
            }
            const items = [];
            let total = 0;
            for (const c of Array.from(document.querySelectorAll('.kt-conversation'))) {
                const a = c.closest('a');
                const href = a ? (a.getAttribute('href') || '') : '';
                if (c.classList.contains('kt-conversation--postman') || href.indexOf('/chat/postchi') >= 0) continue;
                total++;
                const unread = !!c.querySelector('.kt-conversation__icon--new-message, [data-icon="indicator-f"]');
                if (!unread) continue;
                const m = c.querySelector('.kt-conversation__message');
                items.push({id: href.split('/').filter(Boolean).pop() || '', t: txt(m ? m.textContent : '')});
            }
            return JSON.stringify({s: 'list', total: total, items: items});
        } catch (e) { return JSON.stringify({s: 'error'}); }
    })();"""

    suspend fun scan(context: Context, profileName: String): ProbeResult = withContext(Dispatchers.Main.immediate) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@withContext ProbeResult.Unknown
        suspendCancellableCoroutine<ProbeResult> { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var started = false
            var lastSnapshot: String? = null
            var sameCount = 0
            var loginCount = 0
            lateinit var timeout: Runnable
            lateinit var poll: Runnable

            fun finish(value: ProbeResult) {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                handler.removeCallbacks(poll)
                webView?.let { view ->
                    try {
                        view.stopLoading()
                        view.webChromeClient = null
                        view.webViewClient = WebViewClient()
                        view.destroy()
                    } catch (_: Exception) { }
                }
                if (continuation.isActive) continuation.resume(value)
            }

            fun handleSnapshot(raw: String) {
                val json = try { JSONObject(raw) } catch (_: Exception) { return }
                when (json.optString("s")) {
                    "login" -> {
                        sameCount = 0; lastSnapshot = null
                        // Header may briefly look signed-out while the app starts: require a stable result.
                        if (++loginCount >= 3) finish(ProbeResult.LoggedOut)
                    }
                    "list" -> {
                        loginCount = 0
                        if (raw == lastSnapshot) sameCount++ else { lastSnapshot = raw; sameCount = 1 }
                        val total = json.optInt("total", 0)
                        // A non-empty list must be stable for two reads; an empty one for four, so a
                        // half-rendered page is never mistaken for "no unread messages".
                        val ready = if (total > 0) sameCount >= 2 else sameCount >= 4
                        if (ready) finish(ProbeResult.Unread(fingerprints(json)))
                    }
                    else -> { loginCount = 0; sameCount = 0; lastSnapshot = null }
                }
            }

            timeout = Runnable { finish(ProbeResult.Unknown) }
            poll = Runnable {
                val view = webView ?: return@Runnable
                if (finished || !continuation.isActive) return@Runnable
                view.evaluateJavascript(READ_LIST_JS) { result ->
                    if (finished || !continuation.isActive) return@evaluateJavascript
                    val raw = try { JSONTokener(result.orEmpty()).nextValue() as? String } catch (_: Exception) { null }
                    if (raw != null) handleSnapshot(raw)
                    if (!finished) handler.postDelayed(poll, POLL_MS)
                }
            }

            try {
                val view = WebView(context.applicationContext)
                webView = view
                val accountProfile = ProfileStore.getInstance().getOrCreateProfile(profileName)
                WebViewCompat.setProfile(view, profileName)
                accountProfile.cookieManager.setAcceptCookie(true)
                accountProfile.cookieManager.setAcceptThirdPartyCookies(view, true)
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        if (!started && !finished) {
                            started = true
                            handler.postDelayed(poll, FIRST_POLL_DELAY_MS)
                        }
                    }
                }
                continuation.invokeOnCancellation { handler.post { finish(ProbeResult.Unknown) } }
                handler.postDelayed(timeout, OVERALL_TIMEOUT_MS)
                view.loadUrl(CHAT_URL)
            } catch (_: Exception) {
                finish(ProbeResult.Unknown)
            }
        }
    }

    private fun fingerprints(json: JSONObject): Set<String> {
        val items = json.optJSONArray("items") ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return buildSet {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val bytes = digest.digest("${item.optString("id")}|${item.optString("t")}".toByteArray())
                add(bytes.take(6).joinToString("") { "%02x".format(it) })
            }
        }
    }
}