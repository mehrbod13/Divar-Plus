package ir.example.divaraccounts

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {
    private lateinit var store: AccountStore
    private lateinit var root: LinearLayout
    private var accountScrollView: ScrollView? = null
    private var accountScrollY = 0
    private var unreadReceiverRegistered = false
    private var notificationPermissionRequestedThisLaunch = false
    private val unreadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::store.isInitialized && hasWindowFocus()) render()
        }
    }
    private val ink = Color.rgb(31, 38, 50)
    private val muted = Color.rgb(105, 115, 130)
    private val accent = Color.rgb(190, 82, 75)
    private val backgroundColorValue = Color.rgb(247, 248, 250)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountScrollY = savedInstanceState?.getInt(STATE_ACCOUNT_SCROLL_Y, 0) ?: 0
        store = AccountStore(this)
        NotificationHelper.createChannel(applicationContext)
        NotificationScheduler.ensureScheduled(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        if (!unreadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                unreadReceiver,
                IntentFilter(NotificationHelper.ACTION_UNREAD_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            unreadReceiverRegistered = true
        }
        // Refresh after returning from the account's WebView, and after any add/delete action.
        if (::store.isInitialized) {
            render()
            NotificationScheduler.requestImmediateCheck(applicationContext)
            maybeRequestNotificationPermission()
        }
    }

    override fun onPause() {
        if (unreadReceiverRegistered) {
            unregisterReceiver(unreadReceiver)
            unreadReceiverRegistered = false
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        accountScrollView?.let { accountScrollY = it.scrollY }
        outState.putInt(STATE_ACCOUNT_SCROLL_Y, accountScrollY)
        super.onSaveInstanceState(outState)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        // Ask once on each fresh MainActivity launch while permission is missing.
        // The per-instance flag prevents repeated prompts when returning from WebActivity.
        if (notificationPermissionRequestedThisLaunch) return
        notificationPermissionRequestedThisLaunch = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2201)
    }

    private fun supportsProfiles() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun render() {
        accountScrollView?.let { accountScrollY = it.scrollY }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(backgroundColorValue)
            setPadding(dp(20), dp(24), dp(20), dp(18))
        }

        val header = TextView(this).apply {
            text = "مدیریت اکانت‌های دیوار"
            textSize = 23f
            setTextColor(ink)
            typeface = AradFont.bold(this@MainActivity)
            gravity = Gravity.RIGHT
        }
        root.addView(header, matchWrap())
        root.addView(TextView(this).apply {
            text = "برای هر شماره یک حساب جدا دارید. برای ورود، کد تأیید را در خود دیوار وارد کنید."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            setPadding(0, dp(8), 0, dp(18))
        }, matchWrap())

        val addButton = Button(this).apply {
            text = "+ افزودن اکانت"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { showAddDialog() }
            styleButton(this, Color.WHITE, accent, accent)
            minHeight = dp(50)
        }
        root.addView(addButton, matchWrap())
        if (!NotificationHelper.areNotificationsEnabled(this)) {
            root.addView(TextView(this).apply {
                text = "برای دیدن اعلان پیام‌های جدید، اجازهٔ اعلان‌ها را فعال کنید."
                textSize = 12f
                setTextColor(muted)
                gravity = Gravity.RIGHT
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setVerticalScrollbarThumbDrawable(getDrawable(R.drawable.scrollbar_thumb))
            }
            setOnScrollChangeListener { _, _, scrollY, _, _ -> accountScrollY = scrollY }
        }
        accountScrollView = scroll
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(14), 0, dp(8))
        }
        val accounts = store.all()
        if (accounts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "هنوز حسابی اضافه نکرده‌اید. برای شروع، شمارهٔ موبایلتان را وارد کنید."
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            }, matchWrap())
        } else {
            accounts.forEach { account ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    background = roundedCardBackground()
                    clipToOutline = true
                    elevation = dp(2).toFloat()
                    setPadding(dp(16), dp(14), dp(16), dp(12))
                }
                val unreadCount = UnreadStateStore.count(this, account.id)
                val accountHeading = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.CENTER_VERTICAL
                }
                accountHeading.addView(TextView(this).apply {
                    text = PhoneNumberFormatter.formatIranian(account.phone)
                    textSize = 18f
                    setTextColor(ink)
                    typeface = AradFont.bold(this@MainActivity)
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
                if (unreadCount > 0) {
                    accountHeading.addView(TextView(this).apply {
                        text = if (unreadCount > 99) "99+" else unreadCount.toString()
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        minWidth = dp(28)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(accent)
                        }
                        contentDescription = "$unreadCount پیام خوانده‌نشده"
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                        marginStart = dp(8)
                    })
                }
                card.addView(accountHeading, matchWrap())
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val open = Button(this).apply {
                    text = "باز کردن دیوار"
                    isAllCaps = false
                    setOnClickListener { openAccount(account) }
                    styleButton(this, accent, Color.WHITE)
                }
                val delete = Button(this).apply {
                    text = "حذف"
                    isAllCaps = false
                    setOnClickListener { confirmDelete(account) }
                    styleButton(this, Color.rgb(247, 248, 250), muted, Color.rgb(226, 230, 236))
                }
                actions.addView(open, LinearLayout.LayoutParams(0, dp(46), 1f))
                actions.addView(delete, LinearLayout.LayoutParams(dp(86), dp(46)).apply {
                    marginStart = dp(10)
                })
                card.addView(actions, matchWrap().apply { topMargin = dp(10) })
                val params = matchWrap().apply { bottomMargin = dp(12) }
                list.addView(card, params)
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (!supportsProfiles()) {
            root.addView(TextView(this).apply {
                text = "برای استفاده از چند حساب، لطفاً برنامهٔ «Android System WebView» گوشی را از فروشگاه به‌روز کنید."
                textSize = 12f
                setTextColor(Color.rgb(145, 85, 10))
                gravity = Gravity.RIGHT
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, matchWrap())
        }
        AradFont.apply(this, root)
        setContentView(root)
        SystemBarInsets.apply(root)
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.scrollTo(0, accountScrollY)
            }
        })
    }

    private fun showAddDialog() {
        if (!supportsProfiles()) {
            AlertDialog.Builder(this)
                .setTitle("نیاز به به‌روزرسانی WebView")
                .setMessage("برای استفاده از چند حساب، لطفاً برنامهٔ Android System WebView گوشی را از فروشگاه به‌روز کنید.")
                .setPositiveButton("باشه", null).showArad()
            return
        }
        val input = EditText(this).apply {
            hint = "مثلاً ۰۹۱۲۱۲۳۴۵۶۷"
            inputType = InputType.TYPE_CLASS_PHONE
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input, matchWrap())
        }
        AlertDialog.Builder(this)
            .setTitle("افزودن اکانت")
            .setMessage("شماره را وارد کنید؛ کد ورود را در خود دیوار وارد خواهید کرد.")
            .setView(container)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ادامه") { _, _ ->
                val phone = input.text.toString().trim()
                val digits = phone.filter { it.isDigit() }
                if (digits.length !in 8..15) {
                    AlertDialog.Builder(this).setMessage("شماره موبایل را بررسی کنید.").setPositiveButton("باشه", null).showArad()
                    return@setPositiveButton
                }
                var created: DivarAccount? = null
                try {
                    val account = store.add(phone)
                    created = account
                    ProfileStore.getInstance().getOrCreateProfile(account.profileName)
                    openAccount(account, startAtLogin = true)
                } catch (_: Exception) {
                    created?.let {
                        store.remove(it.id)
                        try { ProfileStore.getInstance().deleteProfile(it.profileName) } catch (_: Exception) { }
                    }
                    AlertDialog.Builder(this)
                        .setTitle("ساخت حساب انجام نشد")
                        .setMessage("لطفاً برنامهٔ Android System WebView را از فروشگاه به‌روز کنید و دوباره تلاش کنید.")
                        .setPositiveButton("باشه", null).showArad()
                    render()
                }
            }.showArad()
    }

    private fun openAccount(account: DivarAccount, startAtLogin: Boolean = false) {
        if (!supportsProfiles()) {
            AlertDialog.Builder(this).setMessage("برای استفاده از چند حساب، برنامهٔ Android System WebView گوشی را به‌روز کنید.").setPositiveButton("باشه", null).showArad()
            return
        }
        startActivity(Intent(this, WebActivity::class.java).apply {
            putExtra(WebActivity.EXTRA_PROFILE, account.profileName)
            putExtra(WebActivity.EXTRA_PHONE, PhoneNumberFormatter.formatIranian(account.phone))
            putExtra(WebActivity.EXTRA_START_AT_LOGIN, startAtLogin)
        })
    }

    private fun confirmDelete(account: DivarAccount) {
        // Isolate the phone number as LTR so Persian text does not reorder its digits.
        val phoneLtr = "\u2066${PhoneNumberFormatter.formatIranian(account.phone)}\u2069"
        AlertDialog.Builder(this)
            .setTitle("حذف اکانت؟")
            .setMessage("با حذف حساب $phoneLtr، این حساب و اطلاعات ذخیره‌شده‌اش از برنامه پاک می‌شود.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("حذف") { _, _ ->
                try {
                    if (supportsProfiles()) ProfileStore.getInstance().deleteProfile(account.profileName)
                } catch (_: Exception) { }
                store.remove(account.id)
                UnreadStateStore.clear(this, account.id)
                NotificationHelper.cancel(this, account.id)
                render()
            }.showArad()
    }

    private fun AlertDialog.Builder.showArad(): AlertDialog {
        val dialog = create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.let { AradFont.apply(this@MainActivity, it) }
        }
        dialog.show()
        return dialog
    }

    private fun styleButton(button: Button, fillColor: Int, textColor: Int, strokeColor: Int? = null) {
        val radius = dp(13).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fillColor)
            strokeColor?.let { setStroke(dp(1), it) }
        }
        val rippleMask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val rippleColor = if (fillColor == accent) {
            Color.argb(38, 255, 255, 255)
        } else {
            Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        button.background = RippleDrawable(ColorStateList.valueOf(rippleColor), shape, rippleMask)
        button.backgroundTintList = null
        button.setTextColor(textColor)
        button.setPadding(dp(14), 0, dp(14), 0)
        button.minHeight = dp(44)
    }

    private fun roundedCardBackground() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(232, 236, 241))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_ACCOUNT_SCROLL_Y = "account_scroll_y"
    }
}
