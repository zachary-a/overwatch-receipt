package dev.owreceipt.infra.storage

import dev.owreceipt.core.ReceiptDocument
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small draft payload for Android saved-state; no raster or credentials in the Bundle. */
object ReceiptDocumentCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(document: ReceiptDocument): String = json.encodeToString(document)
    fun decode(value: String): ReceiptDocument = json.decodeFromString(value)
}
