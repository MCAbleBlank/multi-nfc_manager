package com.multinfc.manager.data

import android.content.Context
import org.json.JSONArray

/**
 * Tiny persistence layer backed by SharedPreferences.
 * The whole collection is small, so a single JSON blob is plenty.
 */
class CardRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<NfcCard> = runCatching {
        val raw = prefs.getString(KEY_CARDS, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                runCatching { NfcCard.fromJson(json) }.getOrNull()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun save(cards: List<NfcCard>) {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CARDS, array.toString()).apply()
    }

    /**
     * UID of the card currently exposed through HCE. Read by the APDU service
     * on every command so toggling emulation takes effect immediately.
     */
    fun loadEmulatedUid(): String? =
        prefs.getString(KEY_EMULATED_UID, null)?.takeIf { it.isNotBlank() }

    fun saveEmulatedUid(uid: String?) {
        prefs.edit().apply {
            if (uid == null) remove(KEY_EMULATED_UID) else putString(KEY_EMULATED_UID, uid)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "nfc_card_pocket"
        const val KEY_CARDS = "cards"
        const val KEY_EMULATED_UID = "emulated_uid"
    }
}
