package ir.example.divaraccounts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DivarAccount(
    val id: String,
    val profileName: String,
    val phone: String,
    val createdAt: Long
)

/** Stores only account labels/metadata. Login credentials and OTPs are never collected here. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account_list", Context.MODE_PRIVATE)
    private val key = "accounts_v1"

    fun all(): List<DivarAccount> {
        val array = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val profile = obj.optString("profile")
                val phone = obj.optString("phone")
                if (id.isNotBlank() && profile.isNotBlank() && phone.isNotBlank()) {
                    add(DivarAccount(id, profile, phone, obj.optLong("created", 0L)))
                }
            }
        }
    }

    fun add(phone: String): DivarAccount {
        val account = DivarAccount(
            id = UUID.randomUUID().toString(),
            profileName = "divar_${UUID.randomUUID().toString().replace("-", "")}",
            phone = phone,
            createdAt = System.currentTimeMillis()
        )
        save(all() + account)
        return account
    }

    fun remove(id: String) = save(all().filterNot { it.id == id })

    private fun save(accounts: List<DivarAccount>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(JSONObject().apply {
                put("id", account.id)
                put("profile", account.profileName)
                put("phone", account.phone)
                put("created", account.createdAt)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
