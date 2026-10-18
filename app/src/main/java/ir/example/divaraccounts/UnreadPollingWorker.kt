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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume

/** Experimental, device-local polling of the visible chat badge in Divar's official web UI. */
class UnreadPollingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A startup check and the hourly check can become eligible together. Never probe the same
        // WebView profiles concurrently; it can race the stored baseline and duplicate/miss alerts.
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

            for (account in AccountStore(app).all()) {
                if (isStopped) return Result.retry()
                val unread = try {
                    withTimeoutOrNull(35_000L) {
                        DivarUnreadProbe.readCount(app, account.profileName)
                    }
                } catch (e: CancellationException) {
                    // Let WorkManager reschedule when a network constraint stops this scan.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Unread probe failed for account ${account.id.take(8)}", e)
                    null
                }
                if (unread == null) {
                    Log.w(TAG, "Unread count unavailable for account ${account.id.take(8)}; keeping previous value")
                    continue
                }

                val newUnread = UnreadStateStore.update(app, account.id, unread)
                app.sendBroadcast(Intent(NotificationHelper.ACTION_UNREAD_UPDATED).setPackage(app.packageName))
                if (newUnread > 0) {
                    NotificationHelper.showNewMessages(app, account, unread, newUnread)
                }
            }
            return Result.success()
        } finally {
            UnreadPollingGate.mutex.unlock()
        }
    }

    private companion object {
        const val TAG = "DivarUnreadWorker"
    }
}

private object UnreadPollingGate {
    val mutex = Mutex()
}

/** Loads the city page in a hidden WebView under the account's own cookie profile. */
private object DivarUnreadProbe {
    private const val HOME_URL = "https://divar.ir/s/rasht"
    private const val EXTRACT_BADGE_JS = """(function(){
        const norm = s => (s || '').replace(/[۰-۹]/g, d => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d))
                                   .replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));
        const items = Array.from(document.querySelectorAll('a,button,[role="button"],[aria-label],[title]'));
        const target = items.find(e => {
            const label = norm((e.innerText || e.textContent || '') + ' ' +
                (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || ''));
            return label.includes('چت و تماس');
        });
        if (!target) return -2;
        const body = norm(document.body ? document.body.innerText : '');
        if (body.includes('ورود به حساب کاربری')) return -1;
        const ancestors = [];
        let node = target;
        for (let i=0; node && i<5; i++, node=node.parentElement) ancestors.push(node);
        for (const parent of ancestors) {
            const candidates = [parent, ...Array.from(parent.querySelectorAll(
                '[aria-label],[title],[data-count],[class*="badge" i],[class*="count" i]'))].slice(0,100);
            for (const el of candidates) {
                const values = [el.innerText, el.textContent, el.getAttribute('aria-label'),
                    el.getAttribute('title'), el.getAttribute('data-count')].filter(Boolean).map(norm);
                for (const value of values) {
                    const labelled = value.match(/(?:پیام|خوانده|چت(?:\s*و\s*تماس)?)[^0-9]{0,16}([0-9]{1,3})/);
                    if (labelled) return parseInt(labelled[1],10);
                    const reversed = value.match(/([0-9]{1,3})[^0-9]{0,16}(?:پیام|خوانده|چت)/);
                    if (reversed) return parseInt(reversed[1],10);
                    const onlyNumber = value.trim().match(/^([0-9]{1,3})$/);
                    if (onlyNumber && (el.getAttribute('data-count') || /badge|count/i.test(String(el.className))))
                        return parseInt(onlyNumber[1],10);
                }
            }
        }
        return 0;
    })();"""

    suspend fun readCount(context: Context, profileName: String): Int? = withContext(Dispatchers.Main.immediate) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var attempts = 0
            lateinit var timeout: Runnable
            lateinit var inspect: Runnable

            fun finish(value: Int?) {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
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

            timeout = Runnable { finish(null) }
            inspect = Runnable {
                val view = webView ?: return@Runnable
                if (finished || !continuation.isActive) return@Runnable
                attempts++
                view.evaluateJavascript(EXTRACT_BADGE_JS) { result ->
                    if (finished || !continuation.isActive) return@evaluateJavascript
                    val code = result?.trim('"')?.toIntOrNull()
                    when {
                        code != null && code >= 0 -> finish(code)
                        code == -1 -> finish(null) // Account is not signed in.
                        attempts < 8 -> handler.postDelayed(inspect, 900L)
                        else -> finish(null) // Unknown DOM/layout: leave the badge unchanged.
                    }
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
                        val uri = android.net.Uri.parse(url.orEmpty())
                        if (uri.host == "divar.ir" && uri.path.orEmpty().startsWith("/s/")) {
                            handler.postDelayed(inspect, 1_200L)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    handler.post { finish(null) }
                }
                handler.postDelayed(timeout, 32_000L)
                view.loadUrl(HOME_URL)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
}
