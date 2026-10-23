package dev.owreceipt.infra.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.room.*
import dev.owreceipt.core.*
import dev.owreceipt.infra.receipt.BinaryRaster
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val documentJson: String,
    val width: Int,
    val height: Int,
)
@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY createdAt DESC") fun observe(): Flow<List<ReceiptEntity>>
    @Query("SELECT * FROM receipts WHERE id = :id") suspend fun find(id: String): ReceiptEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(receipt: ReceiptEntity)
    @Query("DELETE FROM receipts WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [ReceiptEntity::class], version = 1, exportSchema = true)
abstract class ReceiptDatabase : RoomDatabase() { abstract fun receipts(): ReceiptDao }

class LocalReceiptRepository(
    context: Context,
    databaseName: String = "souvenirs.db",
    storageDirectory: String = "receipts",
) : ReceiptRepository {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(appContext, ReceiptDatabase::class.java, databaseName).build()
    private val dao = db.receipts()
    private val folder = File(appContext.filesDir, storageDirectory).apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    override val receipts = dao.observe().map { rows ->
        rows.map { SavedReceipt(json.decodeFromString<ReceiptDocument>(it.documentJson), it.width, it.height) }
    }
    private fun file(id: String): File {
        UUID.fromString(id)
        return File(folder, "$id.png")
    }
    override suspend fun save(artifact: ReceiptArtifact) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = artifact.document
            // Existing souvenirs are immutable; repeated Save is idempotent.
            if (dao.find(doc.id) != null) return@withLock
            val atomic = AtomicFile(file(doc.id))
            val output = atomic.startWrite()
            try {
                output.write(artifact.raster.png)
                atomic.finishWrite(output)
                dao.upsert(ReceiptEntity(doc.id, doc.createdAt, json.encodeToString(doc), artifact.raster.width, artifact.raster.height))
            } catch (e: Exception) {
                atomic.failWrite(output)
                atomic.delete()
                throw e
            }
        }
    }
    override suspend fun open(id: String): ReceiptArtifact = withContext(Dispatchers.IO) {
        mutex.withLock {
            val row = dao.find(id) ?: error("这张小票已被删除")
            val doc = json.decodeFromString<ReceiptDocument>(row.documentJson)
            val png = file(id).readBytes()
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("小票图片无法读取")
            val bitmap = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            try {
                require(bitmap.width == row.width && bitmap.height == row.height)
                ReceiptArtifact(doc, RenderedReceipt(row.width, row.height, png, BinaryRaster.thresholdAndPack(bitmap)))
            } finally { bitmap.recycle() }
        }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.delete(id)
            file(id).delete()
            Unit
        }
    }
    fun close() { db.close() }
}
