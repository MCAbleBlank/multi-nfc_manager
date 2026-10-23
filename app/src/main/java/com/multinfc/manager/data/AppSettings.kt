package com.multinfc.manager.data

import android.content.Context

/**
 * How the home screen lays out saved cards. Carousel styles share the same
 * animated position, so volume keys and the wheel keep working when the user
 * switches between them; grid and list are scrolled with the same keys.
 */
enum class CardStyle(
    val storageKey: String,
    val title: String,
    val description: String,
) {
    Wheel(
        storageKey = "wheel",
        title = "3D 轮盘",
        description = "卡片沿虚拟圆柱排布，转动时纵深最强",
    ),
    Coverflow(
        storageKey = "coverflow",
        title = "横向封面流",
        description = "左右滑动浏览，两侧卡片带透视",
    ),
    Stack(
        storageKey = "stack",
        title = "叠层卡堆",
        description = "卡片层叠成堆，上下滑动切换",
    ),
    Grid(
        storageKey = "grid",
        title = "双列网格",
        description = "平铺展示所有卡片，适合卡片较多时",
    ),
    List(
        storageKey = "list",
        title = "纵向列表",
        description = "紧凑排列，快速浏览和查找",
    );

    val isCarousel: Boolean
        get() = this == Wheel || this == Coverflow || this == Stack

    companion object {
        fun fromKey(key: String?): CardStyle =
            entries.firstOrNull { it.storageKey == key } ?: Wheel
    }
}

data class AppSettings(
    val cardStyle: CardStyle = CardStyle.Wheel,
    val volumeKeysEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val tiltEnabled: Boolean = true,
    val enhancedEffects: Boolean = true,
)

/** Small SharedPreferences-backed store; settings are tiny and read at startup. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        cardStyle = CardStyle.fromKey(prefs.getString(KEY_CARD_STYLE, null)),
        volumeKeysEnabled = prefs.getBoolean(KEY_VOLUME_KEYS, true),
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
        tiltEnabled = prefs.getBoolean(KEY_TILT, true),
        enhancedEffects = prefs.getBoolean(KEY_EFFECTS, true),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_CARD_STYLE, settings.cardStyle.storageKey)
            .putBoolean(KEY_VOLUME_KEYS, settings.volumeKeysEnabled)
            .putBoolean(KEY_HAPTICS, settings.hapticsEnabled)
            .putBoolean(KEY_TILT, settings.tiltEnabled)
            .putBoolean(KEY_EFFECTS, settings.enhancedEffects)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "nfc_card_settings"
        const val KEY_CARD_STYLE = "card_style"
        const val KEY_VOLUME_KEYS = "volume_keys"
        const val KEY_HAPTICS = "haptics"
        const val KEY_TILT = "tilt"
        const val KEY_EFFECTS = "enhanced_effects"
    }
}
