package eu.kanade.tachiyomi.data.library.komga

import android.content.Context

// KMK -->
/**
 * 从 Komga 扩展自己的偏好里读出来的连接配置。
 *
 * 键名逐字对应 Komga 扩展（keiyoushi/extensions-source, Komga.kt）里的常量，
 * 偏好文件由宿主 App 按 `source_<sourceId>` 约定创建。
 */
data class KomgaSourceConfig(
    val address: String,
    val apiKey: String,
    val username: String,
    val password: String,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    val hasBasicAuth: Boolean get() = !hasApiKey && username.isNotBlank()

    companion object {
        const val KEY_ADDRESS = "Address"
        const val KEY_API_KEY = "API key"
        const val KEY_USERNAME = "Username"
        const val KEY_PASSWORD = "Password"

        val KEYS = listOf(KEY_ADDRESS, KEY_API_KEY, KEY_USERNAME, KEY_PASSWORD)

        /** 纯函数：地址为空（或只有空白）时返回 null，表示该源不能做增量。 */
        fun from(prefs: Map<String, String?>): KomgaSourceConfig? {
            val address = prefs[KEY_ADDRESS]?.trim()?.removeSuffix("/").orEmpty()
            if (address.isEmpty()) return null
            return KomgaSourceConfig(
                address = address,
                apiKey = prefs[KEY_API_KEY].orEmpty(),
                username = prefs[KEY_USERNAME].orEmpty(),
                password = prefs[KEY_PASSWORD].orEmpty(),
            )
        }
    }
}

fun readKomgaSourceConfig(context: Context, sourceId: Long): KomgaSourceConfig? {
    val prefs = context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
    return KomgaSourceConfig.from(
        KomgaSourceConfig.KEYS.associateWith { prefs.getString(it, null) },
    )
}
// KMK <--
