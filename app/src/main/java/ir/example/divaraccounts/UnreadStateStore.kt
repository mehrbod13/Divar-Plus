package ir.example.divaraccounts

import android.content.Context

/** Stores only aggregate unread counts locally; no message text is downloaded or retained. */
object UnreadStateStore {
    private const val PREFS = "unread_state_v1"

    fun count(context: Context, accountId: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key(accountId), 0)

    /** Saves the latest count and returns a positive delta only when the unread total increased. */
    fun update(context: Context, accountId: String, newCount: Int): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(accountId)
        val hadBaseline = prefs.contains(k)
        val oldCount = prefs.getInt(k, 0)
        prefs.edit().putInt(k, newCount.coerceAtLeast(0)).apply()
        return if (hadBaseline) (newCount - oldCount).coerceAtLeast(0) else 0
    }

    fun clear(context: Context, accountId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String) = "unread_$accountId"
}
