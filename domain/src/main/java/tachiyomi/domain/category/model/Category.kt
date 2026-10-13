package tachiyomi.domain.category.model

import java.io.Serializable

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    // KMK -->
    val hidden: Boolean,
    // SyncYomi v2 identity and merge version, see categories.sq
    val version: Long = 0,
    val uid: Long = 0,
    // KMK <--
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}
