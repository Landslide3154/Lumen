package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy
import tachiyomi.domain.library.service.LibraryPreferences.LibraryUpdateMode

@Execution(ExecutionMode.CONCURRENT)
class LibraryUpdateModeTest {

    private val store = FakePreferenceStore()
    private val preferences = LibraryPreferences(store)

    @Test
    fun `defaults are incremental and fallback to full`() {
        preferences.incrementalUpdateMode().get() shouldBe LibraryUpdateMode.INCREMENTAL
        preferences.komgaUnavailablePolicy().get() shouldBe KomgaUnavailablePolicy.FALLBACK_FULL
        preferences.komgaSkippedCount().get() shouldBe 0
        preferences.komgaWatermarks().get() shouldBe ""
    }

    @Test
    fun `values round trip through the store`() {
        preferences.incrementalUpdateMode().set(LibraryUpdateMode.FULL)
        preferences.komgaUnavailablePolicy().set(KomgaUnavailablePolicy.SKIP)
        preferences.komgaSkippedCount().set(7)
        preferences.komgaWatermarks().set(WATERMARKS_JSON)

        preferences.incrementalUpdateMode().get() shouldBe LibraryUpdateMode.FULL
        preferences.komgaUnavailablePolicy().get() shouldBe KomgaUnavailablePolicy.SKIP
        preferences.komgaSkippedCount().get() shouldBe 7
        preferences.komgaWatermarks().get() shouldBe WATERMARKS_JSON
    }

    @Test
    fun `unknown enum value falls back to default`() {
        store.values["pref_library_update_mode"] = "WHATEVER"

        preferences.incrementalUpdateMode().get() shouldBe LibraryUpdateMode.INCREMENTAL
    }

    @Test
    fun `watermarks and skip count are app state keys`() {
        preferences.komgaWatermarks().key() shouldBe "__APP_STATE_library_update_komga_watermarks"
        preferences.komgaSkippedCount().key() shouldBe "__APP_STATE_library_update_komga_skipped_count"
        preferences.komgaChangedCount().key() shouldBe "__APP_STATE_library_update_komga_changed_count"
        preferences.komgaRetryIds().key() shouldBe "__APP_STATE_library_update_komga_retry_ids"
    }

    @Test
    fun `retry ids default to empty and round trip`() {
        preferences.komgaRetryIds().get() shouldBe ""

        preferences.komgaRetryIds().set("12,34")

        preferences.komgaRetryIds().get() shouldBe "12,34"
    }

    private companion object {
        const val WATERMARKS_JSON = """{"https://h":"2026-10-12T02:11:21Z"}"""
    }
}

/** 测试用内存版偏好存储：只实现本项目实际用到的行为。 */
private class FakePreferenceStore : PreferenceStore {

    val values = mutableMapOf<String, Any?>()

    override fun getString(key: String, defaultValue: String): Preference<String> =
        FakePreference(values, key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        FakePreference(values, key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        FakePreference(values, key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        FakePreference(values, key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        FakePreference(values, key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        FakePreference(values, key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = FakePreference(
        values = values,
        key = key,
        defaultValue = defaultValue,
        toStored = { serializer(it) },
        fromStored = { deserializer(it as String) },
    )

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = FakePreference(
        values = values,
        key = key,
        defaultValue = defaultValue,
        toStored = { serializer(it) },
        fromStored = { deserializer(it as Int) },
    )

    override fun getAll(): Map<String, *> = values.toMap()
}

private class FakePreference<T>(
    private val values: MutableMap<String, Any?>,
    private val key: String,
    private val defaultValue: T,
    private val toStored: (T) -> Any? = { it },
    private val fromStored: (Any?) -> T = {
        @Suppress("UNCHECKED_CAST")
        it as T
    },
) : Preference<T> {

    override fun key(): String = key

    override fun get(): T = if (values.containsKey(key)) fromStored(values[key]) else defaultValue

    override fun set(value: T) {
        values[key] = toStored(value)
    }

    override fun isSet(): Boolean = values.containsKey(key)

    override fun delete() {
        values.remove(key)
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = MutableStateFlow(get())

    override fun stateIn(scope: CoroutineScope): StateFlow<T> = MutableStateFlow(get())
}
