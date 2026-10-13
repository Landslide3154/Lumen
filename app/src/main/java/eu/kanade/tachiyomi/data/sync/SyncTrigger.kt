package eu.kanade.tachiyomi.data.sync

/**
 * Why a sync started.
 *
 * Every library update runs a sync first, so most completions the user sees are ones they never
 * asked for; the notification names the trigger to make that obvious.
 */
enum class SyncTrigger {
    MANUAL,
    BEFORE_LIBRARY_UPDATE,
    SCHEDULED,
    APP_START,
    APP_RESUME,
    CHAPTER_OPEN,
    CHAPTER_READ,
    UNKNOWN,
    ;

    companion object {
        private val byName = entries.associateBy { it.name }

        fun fromKey(key: String?): SyncTrigger = key?.let(byName::get) ?: UNKNOWN
    }
}
