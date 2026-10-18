package ir.example.divaraccounts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder

object NotificationHelper {
    private const val CHANNEL_ID = "divar_messages"
    const val ACTION_UNREAD_UPDATED = "ir.example.divaraccounts.UNREAD_UPDATED"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "پیام‌های دیوار",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اعلان پیام‌های خوانده‌نشدهٔ اکانت‌های دیوار"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun showNewMessage(context: Context, account: DivarAccount) {
        if (!areNotificationsEnabled(context)) return

        createChannel(context)
        val intent = Intent(context, WebActivity::class.java).apply {
            putExtra(WebActivity.EXTRA_PROFILE, account.profileName)
            putExtra(WebActivity.EXTRA_PHONE, PhoneNumberFormatter.formatIranian(account.phone))
            putExtra(WebActivity.EXTRA_OPEN_CHATS, true)
            // Unique data makes the PendingIntent identity unique for every account.
            data = Uri.parse("divarplus://notification/${account.id}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val phoneLtr = "\u2066${PhoneNumberFormatter.formatIranian(account.phone)}\u2069"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("پیام جدید در دیوار")
            .setContentText("$phoneLtr • پیام جدید دارید")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // Only called when something new arrived, so every call should alert again.
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(account.id, 0, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked between the check and notify.
        }
    }

    fun cancel(context: Context, accountId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(accountId, 0)
        manager.cancel(accountId.hashCode())
    }
}