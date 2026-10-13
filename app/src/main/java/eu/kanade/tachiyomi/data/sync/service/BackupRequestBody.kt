package eu.kanade.tachiyomi.data.sync.service

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException

/**
 * Streams a backup instead of encoding the whole library into one byte array first, which is what
 * made syncing a large library run out of memory on Android.
 *
 * Every manga is written as its own `backup_manga` field record and the rest of the backup after
 * them; protobuf merges the repeated records back into one message, so the bytes are identical to
 * a single encode (see [BackupRequestBodyTest]).
 *
 * [metaBytes] is the backup without its manga, which is also what tells an empty upload apart from
 * a delta that only carries categories; see [isEmpty].
 */
internal class BackupRequestBody(
    private val backup: Backup,
    private val protoBuf: ProtoBuf,
    private val gzip: Boolean = false,
) : RequestBody() {

    val metaBytes: ByteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup.copy(backupManga = emptyList()))

    /** Nothing to upload: no manga and nothing in the other sections either. */
    fun isEmpty(): Boolean = backup.backupManga.isEmpty() && metaBytes.isEmpty()

    override fun contentType(): MediaType? = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        try {
            if (gzip) {
                GzipSink(sink).buffer().use { writeRecords(it) }
            } else {
                writeRecords(sink)
            }
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory to sync this library", e)
        }
    }

    private fun writeRecords(sink: BufferedSink) {
        val serializer = MangaChunk.serializer()
        for (manga in backup.backupManga) {
            sink.write(protoBuf.encodeToByteArray(serializer, MangaChunk(manga)))
        }
        sink.write(metaBytes)
    }

    @Serializable
    private class MangaChunk(
        @ProtoNumber(1) val manga: BackupManga,
    )
}
