package dev.owreceipt.core

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(BEIJING)
fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))

@Serializable
enum class Period(val label: String) { TODAY("当天"), WEEK("当周"), CAREER("生涯") }

@Serializable
data class QueryRange(
    val period: Period,
    val startInclusive: Long?,
    val endExclusive: Long,
    val zoneId: String = "Asia/Shanghai",
) {
    val label: String get() = if (startInclusive == null) "生涯 · 截至 ${formatTime(endExclusive)}"
        else "${formatTime(startInclusive)} — ${formatTime(endExclusive)}"
    companion object {
        fun at(period: Period, clock: Clock = Clock.systemUTC()): QueryRange {
            val now = clock.instant()
            val local = now.atZone(BEIJING)
            val start = when (period) {
                Period.TODAY -> local.toLocalDate().atStartOfDay(BEIJING).toInstant()
                Period.WEEK -> local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(BEIJING).toInstant()
                Period.CAREER -> null
            }
            return QueryRange(period, start?.toEpochMilli(), now.toEpochMilli())
        }
    }
}

@Serializable
data class Player(val id: String, val battleTag: String, val roleName: String, val realm: String = "国服")

@Serializable
enum class DemoScenario(val label: String) {
    NORMAL("完整战绩"), CAREER_ONLY("仅支持生涯"), EMPTY("暂无战绩"), MISSING("部分字段缺失"),
    PRIVATE("资料不可见"), FAILURE("查询失败"), EXPIRED("登录已过期"),
}

enum class SessionStatus { SIGNED_OUT, AUTHENTICATING, SELECTING_ROLE, FETCHING_CREDENTIAL, REFRESHING, READY, EXPIRED, FAILED }
data class SessionState(
    val status: SessionStatus = SessionStatus.SIGNED_OUT,
    val roles: List<Player> = emptyList(),
    val activePlayer: Player? = null,
    val detail: String? = null,
)

data class QueryCapabilities(val periods: Set<Period>, val unavailableReason: String? = null)

@Serializable
data class Metric(val label: String, val value: String?, val unit: String = "") {
    val displayValue: String get() = value?.let { if (unit.isBlank()) it else "$it $unit" } ?: "暂不可用"
}

@Serializable
data class StatsBlock(val id: String, val title: String, val metrics: List<Metric>, val scopeLabel: String? = null) {
    val available: Boolean get() = metrics.any { it.value != null }
}

@Serializable
data class StatsSnapshot(
    val player: Player,
    val range: QueryRange,
    val source: String,
    val fetchedAt: Long,
    val coverage: String,
    val mode: String,
    val complete: Boolean,
    val blocks: List<StatsBlock>,
    val isDemo: Boolean = true,
    val matches: List<MatchRecord> = emptyList(),
)

@Serializable
enum class ReceiptFont(val label: String, val dots: Int) {
    // SMALL is retained only to decode and reproduce previously saved receipts.
    SMALL("小", 28), MEDIUM("中", 32), LARGE("大", 36);
    fun forNewReceipt(): ReceiptFont = if (this == SMALL) MEDIUM else this
    companion object { val choices = listOf(MEDIUM, LARGE) }
}
enum class AppTheme(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class UserPreferences(
    val font: ReceiptFont = ReceiptFont.MEDIUM,
    val theme: AppTheme = AppTheme.SYSTEM,
    val scenario: DemoScenario = DemoScenario.NORMAL,
    val period: Period = Period.CAREER,
)

@Serializable
data class PrinterProfile(val widthDots: Int = 576, val nominalPpi: Int = 300)

@Serializable
data class ReceiptDocument(
    val id: String,
    val createdAt: Long,
    val snapshot: StatsSnapshot,
    val selectedBlockIds: List<String>,
    val font: ReceiptFont,
    val templateVersion: Int = 4,
    val profile: PrinterProfile = PrinterProfile(),
    val kind: ReceiptKind = ReceiptKind.OVERVIEW,
) {
    init {
        require(selectedBlockIds.isNotEmpty()) { "至少选择一个战绩模块" }
        require(selectedBlockIds.distinct().size == selectedBlockIds.size)
        require(selectedBlockIds.all { id -> snapshot.blocks.any { it.id == id && it.available } })
        require(profile.widthDots > 0 && profile.widthDots % 8 == 0)
    }
    val blocks: List<StatsBlock> get() = selectedBlockIds.map { id -> snapshot.blocks.first { it.id == id } }
}

data class RenderedReceipt(
    val width: Int, val height: Int, val png: ByteArray, val packedRows: ByteArray,
) {
    init {
        require(width > 0 && width % 8 == 0 && height > 0)
        require(packedRows.size == width / 8 * height)
    }
}
data class SavedReceipt(val document: ReceiptDocument, val width: Int, val height: Int)
data class ReceiptArtifact(val document: ReceiptDocument, val raster: RenderedReceipt)
enum class PrintStatus {
    DISCONNECTED, CONNECTING, READING_INFO, READY, PREPARING, SENDING, WAITING_BUFFER, WAITING_IDLE,
    PAPER_OUT, STOPPING, STOPPED, COMPLETED, ERROR,
}
data class PrintState(
    val status: PrintStatus = PrintStatus.DISCONNECTED,
    val progress: Float = 0f,
    val receiptId: String? = null,
    val device: PrinterDevice? = null,
    val info: PrinterInfo? = null,
    val detail: String? = null,
    val strategy: ConnectionStrategy? = null,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
)
enum class StatsProblem { EMPTY, PRIVATE, NETWORK, AUTH_REQUIRED, UNSUPPORTED, RATE_LIMIT, UPSTREAM }
class StatsException(val problem: StatsProblem, message: String) : Exception(message)
