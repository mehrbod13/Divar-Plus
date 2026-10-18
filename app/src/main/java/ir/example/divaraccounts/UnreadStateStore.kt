package ir.example.divaraccounts

import android.content.Context

/**
 * Local-only unread state per account. Divar's web UI shows no unread number, so we keep a set of
 * short one-way fingerprints (hash of conversation id + preview text) of the conversations that
 * currently look unread. A notification is due only when a fingerprint appears that was not there
 * in the previous successful scan. No message text is stored.
 */
object UnreadStateStore {
    private const val PREFS = "unread_state_v2"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasUnread(context: Context, accountId: String): Boolean =
        prefs(context).getBoolean("has_$accountId", false)

    fun needsLogin(context: Context, accountId: String): Boolean =
        prefs(context).getBoolean("login_$accountId", false)

    /**
     * Saves a successful scan. Returns true when at least one unread conversation/message is new
     * compared with the previous scan. The very first scan only records a baseline.
     */
    fun applyScan(context: Context, accountId: String, fingerprints: Set<String>): Boolean {
        val p = prefs(context)
        val hadBaseline = p.getBoolean("base_$accountId", false)
        val previous = p.getStringSet("sigs_$accountId", emptySet()).orEmpty()
        p.edit()
            .putStringSet("sigs_$accountId", HashSet(fingerprints))
            .putBoolean("has_$accountId", fingerprints.isNotEmpty())
            .putBoolean("base_$accountId", true)
            .putBoolean("login_$accountId", false)
            .apply()
        return hadBaseline && fingerprints.any { it !in previous }
    }

    /** The account is signed out: keep the old state, only remember that a re-login is needed. */
    fun markNeedsLogin(context: Context, accountId: String) {
        prefs(context).edit().putBoolean("login_$accountId", true).apply()
    }

    fun clear(context: Context, accountId: String) {
        prefs(context).edit()
            .remove("sigs_$accountId").remove("has_$accountId")
            .remove("base_$accountId").remove("login_$accountId")
            .apply()
    }
}