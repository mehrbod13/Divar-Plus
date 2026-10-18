package ir.example.divaraccounts

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class WebActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var pageLabel: TextView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val profileName by lazy { intent.getStringExtra(EXTRA_PROFILE).orEmpty() }
    private val phone by lazy { intent.getStringExtra(EXTRA_PHONE).orEmpty() }
    private val startAtLogin by lazy { intent.getBooleanExtra(EXTRA_START_AT_LOGIN, false) }
    private val openChatsTab by lazy { intent.getBooleanExtra(EXTRA_OPEN_CHATS, false) }
    private var loginButtonAutoOpened = false
    private var loginClickAttempts = 0
    private var chatsTabAutoOpened = false
    private var chatsClickAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (profileName.isBlank() || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(Color.rgb(250, 250, 250))
        }
        val back = iconButton(R.drawable.ic_arrow_back, "بازگشت").apply {
            setOnClickListener { handleWebBack() }
        }
        val home = iconButton(R.drawable.ic_home, "صفحهٔ اکانت‌ها").apply {
            setOnClickListener { finish() }
        }
        pageLabel = TextView(this).apply {
            text = "divar.ir  •  $phone"
            textSize = 13f
            setTextColor(Color.rgb(50, 55, 63))
            typeface = AradFont.regular(this@WebActivity)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), 0, dp(5), 0)
        }
        val reload = iconButton(R.drawable.ic_refresh, "بارگذاری دوباره").apply {
            setOnClickListener { webView.reload() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(home, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(pageLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.app_accent))
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        webView = WebView(this)
        // Assign the account's isolated profile before settings or navigation.
        val accountProfile = ProfileStore.getInstance().getOrCreateProfile(profileName)
        WebViewCompat.setProfile(webView, profileName)
        // Configure the cookie manager belonging to this account, not CookieManager's default profile.
        // Divar's chat/auth flow may need third-party cookies; the policy is set per WebView.
        accountProfile.cookieManager.setAcceptCookie(true)
        accountProfile.cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true // Required by Divar's official web app.
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            // Required for the system's user-selected content:// image URIs in file uploads.
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageLabel.text = title?.takeIf { it.isNotBlank() } ?: "دیوار • $phone"
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (callback == null || params == null) return false
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val pickerIntent = params.createIntent()
                    startActivityForResult(
                        Intent.createChooser(pickerIntent, "انتخاب تصویر یا فایل"),
                        FILE_CHOOSER_REQUEST
                    )
                    true
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    true
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep secure login redirects in this account's profile; reject cleartext links.
                return request.url.scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (startAtLogin && !loginButtonAutoOpened && isDivarUrl(url) &&
                    url.orEmpty().contains("my-divar")) {
                    // First launch clicks only Divar's visible sign-in button; no credential is read.
                    view?.let { tryOpenOfficialLogin(it) }
                }
                val uri = android.net.Uri.parse(url.orEmpty())
                if (openChatsTab && !chatsTabAutoOpened && isDivarUrl(url) &&
                    uri.path.orEmpty().startsWith("/s/")) {
                    view?.let { tryOpenChatsTab(it) }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SystemBarInsets.apply(root)

        if (savedInstanceState == null) {
            if (startAtLogin) webView.loadUrl("https://divar.ir/my-divar")
            else webView.loadUrl(CITY_HOME_URL)
        }
    }

    @Deprecated("Used by WebView's system file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val callback = filePathCallback ?: return
        filePathCallback = null

        val uris = if (resultCode == RESULT_OK) {
            val parsed = try { WebChromeClient.FileChooserParams.parseResult(resultCode, data) }
            catch (_: Exception) { null }
            parsed?.takeIf { it.isNotEmpty() } ?: data?.let { intent ->
                intent.clipData?.let { clip ->
                    Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                } ?: intent.data?.let { arrayOf(it) }
            }
        } else null
        callback.onReceiveValue(uris)
    }

    override fun onBackPressed() {
        handleWebBack()
    }

    /** On the sign-in/account screen, back goes to Divar's Rasht home instead of the login shell. */
    private fun handleWebBack() {
        if (!::webView.isInitialized) {
            finish()
            return
        }
        val currentUrl = webView.url.orEmpty()
        val uri = android.net.Uri.parse(currentUrl)
        val path = uri.path.orEmpty()
        val isLoginOrAccountPage = path.startsWith("/my-divar") ||
            path.contains("login", ignoreCase = true) ||
            path.contains("auth", ignoreCase = true) ||
            path.contains("otp", ignoreCase = true) ||
            uri.queryParameterNames.any { it.contains("login", ignoreCase = true) || it.contains("auth", ignoreCase = true) }

        if (isLoginOrAccountPage) {
            webView.loadUrl(CITY_HOME_URL)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun tryOpenOfficialLogin(view: WebView) {
        if (loginButtonAutoOpened || loginClickAttempts >= 8 || isFinishing) return
        loginClickAttempts++
        view.evaluateJavascript(
            """(function(){
                const nodes = Array.from(document.querySelectorAll('button,a,[role="button"]'));
                const target = nodes.find(e => ((e.innerText || e.textContent || '').replace(/\s+/g,' ').trim()).includes('ورود به حساب کاربری'));
                if (target) { target.click(); return 'opened'; }
                return 'not-found';
            })();""".trimIndent()
        ) { result ->
            if (result?.contains("opened") == true) {
                loginButtonAutoOpened = true
            } else if (loginClickAttempts < 8 && !isFinishing) {
                mainHandler.postDelayed({
                    if (::webView.isInitialized) tryOpenOfficialLogin(webView)
                }, 400L)
            }
        }
    }

    private fun tryOpenChatsTab(view: WebView) {
        if (chatsTabAutoOpened || chatsClickAttempts >= 8 || isFinishing) return
        chatsClickAttempts++
        view.evaluateJavascript(
            """(function(){
                const nodes = Array.from(document.querySelectorAll('button,a,[role="button"],[aria-label],div,span'));
                const target = nodes.find(e => {
                    const label = ((e.innerText || e.textContent || '') + ' ' +
                        (e.getAttribute('aria-label') || '')).replace(/\s+/g,' ').trim();
                    return label.length < 32 && label.includes('چت و تماس');
                });
                if (target) { target.click(); return 'opened'; }
                return 'not-found';
            })();""".trimIndent()
        ) { result ->
            if (result?.contains("opened") == true) {
                chatsTabAutoOpened = true
            } else if (chatsClickAttempts < 8 && !isFinishing) {
                mainHandler.postDelayed({
                    if (::webView.isInitialized) tryOpenChatsTab(webView)
                }, 500L)
            }
        }
    }

    private fun isDivarUrl(url: String?): Boolean = try {
        val host = android.net.Uri.parse(url).host.orEmpty()
        host == "divar.ir" || host.endsWith(".divar.ir")
    } catch (_: Exception) { false }

    private fun iconButton(icon: Int, description: String) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setColorFilter(Color.rgb(55, 63, 74))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = android.util.TypedValue().let { value ->
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
            getDrawable(value.resourceId)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROFILE = "profile_name"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_START_AT_LOGIN = "start_at_login"
        const val EXTRA_OPEN_CHATS = "open_chats"
        private const val FILE_CHOOSER_REQUEST = 4172
        private const val CITY_HOME_URL = "https://divar.ir/s/rasht"
    }
}
