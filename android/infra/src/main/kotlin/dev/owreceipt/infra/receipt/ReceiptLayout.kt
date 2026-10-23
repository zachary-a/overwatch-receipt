package dev.owreceipt.infra.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import dev.owreceipt.core.*
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Bundled fonts keep print typography independent of the phone vendor and system theme. */
internal class ReceiptTypefaces(context: Context) {
    private val assets = context.applicationContext.assets
    private fun sans(weight: Int): Typeface = Typeface.Builder(assets, "receipt/NotoSansSC.ttf")
        .setFontVariationSettings("'wght' $weight").build()
    val regular: Typeface = sans(400)
    val medium: Typeface = sans(500)
    val bold: Typeface = sans(700)
    val display: Typeface = Typeface.createFromAsset(assets, "receipt/BarlowCondensed-SemiBold.ttf")
    val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)
}

/** Vector-first layout. All geometry is expressed in printer dots, never screen pixels. */
internal class ReceiptLayout(private val document: ReceiptDocument, private val fonts: ReceiptTypefaces) {
    private val snapshot = document.snapshot
    private val width = document.profile.widthDots
    private val scale = width / 576f
    private val fontScale = document.font.dots / 32f
    private val left = 34f * scale
    private val right = width - left
    private val contentWidth = right - left
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.BLACK }
    private val marks = mutableListOf<(Canvas) -> Unit>()
    private val formatter = DecimalFormat("#,##0.#", DecimalFormatSymbols(Locale.US))
    private var y = 28f * scale

    private fun size(value: Float) = value * scale * fontScale
    private fun gap(value: Float) { y += value * scale }
    private fun measure(value: String, textSize: Float, face: Typeface, tracking: Float = 0f): Float {
        paint.textSize = textSize
        paint.typeface = face
        return paint.measureText(value) + (value.length - 1).coerceAtLeast(0) * tracking
    }
    private fun lineHeight(textSize: Float, face: Typeface): Float {
        paint.textSize = textSize
        paint.typeface = face
        val metrics = paint.fontMetrics
        return metrics.descent - metrics.ascent
    }
    private fun fitted(value: String, requested: Float, face: Typeface, available: Float): Float =
        min(requested, requested * available / measure(value, requested, face).coerceAtLeast(1f))

    private fun label(value: String, x: Float, top: Float, textSize: Float, face: Typeface = fonts.regular,
        align: Paint.Align = Paint.Align.LEFT, tracking: Float = 0f) {
        paint.textSize = textSize
        paint.typeface = face
        val baseline = top - paint.fontMetrics.ascent
        val textWidth = measure(value, textSize, face, tracking)
        val start = when (align) {
            Paint.Align.CENTER -> x - textWidth / 2
            Paint.Align.RIGHT -> x - textWidth
            else -> x
        }
        marks += { canvas ->
            paint.color = Color.BLACK
            paint.style = Paint.Style.FILL
            paint.textSize = textSize
            paint.typeface = face
            if (tracking == 0f) canvas.drawText(value, start, baseline, paint) else {
                var cursor = start
                value.forEach { character ->
                    val letter = character.toString()
                    canvas.drawText(letter, cursor, baseline, paint)
                    cursor += paint.measureText(letter) + tracking
                }
            }
        }
    }

    private fun lines(value: String, textSize: Float, face: Typeface, available: Float): List<String> {
        paint.textSize = textSize
        paint.typeface = face
        return value.split('\n').flatMap { paragraph ->
            buildList {
                var rest = paragraph
                if (rest.isEmpty()) add("")
                while (rest.isNotEmpty()) {
                    var count = paint.breakText(rest, true, available, null).coerceAtLeast(1)
                    if (count < rest.length && Character.isHighSurrogate(rest[count - 1])) count--
                    count = count.coerceAtLeast(if (Character.isHighSurrogate(rest[0])) 2 else 1)
                    add(rest.take(count))
                    rest = rest.drop(count)
                }
            }
        }
    }

    private fun center(value: String, points: Float, face: Typeface = fonts.regular,
        tracking: Float = 0f) {
        val textSize = size(points)
        lines(value, textSize, face, contentWidth).forEach { part ->
            label(part, width / 2f, y, textSize, face, Paint.Align.CENTER, tracking * scale)
            y += lineHeight(textSize, face) + 3 * scale
        }
    }
    private fun rule(dashed: Boolean = false, before: Float = 18f, after: Float = 18f) {
        gap(before)
        val at = y
        marks += { canvas ->
            paint.strokeWidth = (1.25f * scale).coerceAtLeast(1f)
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(5 * scale, 4 * scale), 0f) else null
            canvas.drawLine(left, at, right, at, paint)
            paint.pathEffect = null
        }
        gap(after)
    }
    private fun section(value: String) {
        gap(3f)
        center("[ $value ]", 23f, fonts.bold)
        gap(15f)
    }
    private fun pretty(value: String): String = if (Regex("[0-9]+(?:\\.[0-9]+)?").matches(value))
        formatter.format(BigDecimal(value)) else value

    private fun pair(name: String, value: String, metadata: Boolean = false, emphasized: Boolean = false) {
        val keySize = size(if (metadata) 16.5f else 21.5f)
        val valueSize = size(if (metadata) 21f else 23f)
        val keyFace = if (metadata) fonts.mono else fonts.regular
        val valueFace = if (emphasized) fonts.bold else fonts.medium
        val keyWidth = measure(name, keySize, keyFace)
        val valueWidth = measure(value, valueSize, valueFace)
        if (keyWidth + valueWidth + 20 * scale <= contentWidth) {
            val rowHeight = maxOf(lineHeight(keySize, keyFace), lineHeight(valueSize, valueFace))
            label(name, left, y + (rowHeight - lineHeight(keySize, keyFace)) / 2, keySize, keyFace)
            label(value, right, y, valueSize, valueFace, Paint.Align.RIGHT)
            y += rowHeight + 7 * scale
        } else if (metadata) {
            val available = (contentWidth - keyWidth - 22 * scale).coerceAtLeast(contentWidth / 2)
            val parts = lines(value, valueSize, valueFace, available)
            label(name, left, y + 2 * scale, keySize, keyFace)
            parts.forEach { part ->
                label(part, right, y, valueSize, valueFace, Paint.Align.RIGHT)
                y += lineHeight(valueSize, valueFace) + 2 * scale
            }
            gap(7f)
        } else {
            lines(name, keySize, keyFace, contentWidth).forEach { part ->
                label(part, left, y, keySize, keyFace)
                y += lineHeight(keySize, keyFace) + 3 * scale
            }
            lines(value, valueSize, valueFace, contentWidth).forEach { part ->
                label(part, right, y, valueSize, valueFace, Paint.Align.RIGHT)
                y += lineHeight(valueSize, valueFace) + 3 * scale
            }
            gap(8f)
        }
    }

    private fun date(value: Long, pattern: String): String = DateTimeFormatter.ofPattern(pattern, Locale.US)
        .withZone(BEIJING).format(Instant.ofEpochMilli(value))
    private val receiptCode: String get() = date(snapshot.range.endExclusive, "yyMMdd") +
        (document.id.hashCode().toLong() and 0x7fffffff).rem(1_000_000).toString().padStart(6, '0')
    private val periodWord: String get() = when (snapshot.range.period) {
        Period.TODAY -> "今日"; Period.WEEK -> "本周"; Period.CAREER -> "生涯"
    }
    private fun header() {
        center("OVERWATCH 2", 24f, fonts.bold, tracking = 3.1f)
        gap(4f)
        center(when (document.kind) {
            ReceiptKind.SUMMARY -> "${periodWord}战绩结算单"
            ReceiptKind.MATCH -> "对局纪念单"
            ReceiptKind.OVERVIEW -> "守望战绩收藏单"
        }, 37f, fonts.bold)
        gap(8f)
        center("THANK YOU FOR PLAYING", 14.5f, fonts.medium, tracking = 1.25f)
        if (snapshot.isDemo) { gap(8f); center("SAMPLE · 示例数据", 15.5f, fonts.medium) }
        rule(dashed = true, before = 21f, after = 19f)
        pair("PLAYER", snapshot.player.battleTag, metadata = true, emphasized = true)
        val selectedMatch = snapshot.matches.singleOrNull()?.takeIf { document.kind == ReceiptKind.MATCH }
        pair("DATE", selectedMatch?.let { date(it.startedAt, "yyyy.MM.dd / HH:mm") } ?: when (snapshot.range.period) {
            Period.TODAY -> date(snapshot.range.endExclusive, "yyyy.MM.dd")
            Period.WEEK -> snapshot.range.startInclusive?.let {
                val endPattern = if (date(it, "yyyy") == date(snapshot.range.endExclusive, "yyyy")) "MM.dd" else "yyyy.MM.dd"
                "${date(it, "yyyy.MM.dd")} - ${date(snapshot.range.endExclusive, endPattern)}" }
                ?: date(snapshot.range.endExclusive, "yyyy.MM.dd")
            Period.CAREER -> date(snapshot.range.endExclusive, "yyyy.MM.dd")
        }, metadata = true)
        pair("MODE", if (document.kind == ReceiptKind.OVERVIEW) "${snapshot.player.realm} / 生涯资料"
            else "${snapshot.player.realm} / ${snapshot.mode}", metadata = true)
        pair("ORDER NO.", "OW-${receiptCode.take(6)}-${receiptCode.takeLast(6)}", metadata = true)
        rule(dashed = true, before = 12f, after = 19f)
    }

    private fun numberStrip(items: List<Pair<String, String>>, points: Float = 61f) {
        if (items.isEmpty()) return
        val cellWidth = contentWidth / items.size
        var maxHeight = 0f
        items.forEachIndexed { index, (name, value) ->
            val x = left + cellWidth * (index + .5f)
            val textSize = fitted(value, size(points), fonts.display, cellWidth - 18 * scale)
            val numberHeight = lineHeight(textSize, fonts.display)
            label(value, x, y + size(points) - textSize, textSize, fonts.display, Paint.Align.CENTER)
            val captionTop = y + lineHeight(size(points), fonts.display) + 4 * scale
            val captionSize = fitted(name, size(18.5f), fonts.medium, cellWidth - 8 * scale)
            label(name, x, captionTop, captionSize, fonts.medium, Paint.Align.CENTER)
            maxHeight = maxOf(maxHeight, maxOf(numberHeight, captionTop - y) + lineHeight(captionSize, fonts.medium))
        }
        y += maxHeight + 13 * scale
    }

    private fun summary(block: StatsBlock) {
        section("${periodWord}账单")
        val matches = snapshot.matches
        if (matches.isEmpty()) {
            val count = block.metrics.firstOrNull { it.label in setOf("记录对局", "已取得对局") }?.value
            numberStrip(listOf("记录对局" to (count ?: "0").padStart(2, '0')))
            return
        }
        val wins = matches.count { it.result == MatchResult.WIN }
        val losses = matches.count { it.result == MatchResult.LOSS }
        val draws = matches.count { it.result == MatchResult.DRAW }
        val unknown = matches.count { it.result == MatchResult.UNKNOWN }
        val winRate = block.metrics.firstOrNull { it.label in setOf("胜率", "已知胜率", "已知结果胜率") }
        numberStrip(buildList {
            add((if (snapshot.complete) "总场次" else "记录场次") to matches.size.toString().padStart(2, '0'))
            add("胜利" to wins.toString().padStart(2, '0'))
            winRate?.value?.let { add((if (unknown == 0) "胜率" else "已知胜率") to "$it%") }
        })
        gap(6f)
        center(buildList {
            add("${wins.toString().padStart(2, '0')} 胜")
            add("${losses.toString().padStart(2, '0')} 负")
            add("${draws.toString().padStart(2, '0')} 平")
            if (unknown > 0) add("$unknown 待定")
        }.joinToString("   /   "), 20f, fonts.medium)
        gap(11f)
        block.metrics.firstOrNull { it.label == "总对局时长" && it.value != null }?.let {
            pair("游戏时长", it.value!!)
        }
    }

    private fun clipped(value: String, available: Float, textSize: Float, face: Typeface): String {
        if (measure(value, textSize, face) <= available) return value
        val candidates = lines(value, textSize, face, available - measure("…", textSize, face))
        return candidates.first() + "…"
    }
    private fun tableRow(values: List<String>, heading: Boolean = false) {
        val anchors = floatArrayOf(left, left + contentWidth * .105f, left + contentWidth * .51f, right)
        val rowSize = size(if (heading) 17f else 20.5f)
        val face = if (heading) fonts.medium else fonts.regular
        val widths = floatArrayOf(contentWidth * .10f, contentWidth * .37f, contentWidth * .16f, contentWidth * .30f)
        values.forEachIndexed { index, value ->
            val currentFace = if (!heading && index == 2) fonts.bold else face
            val adjustedSize = if (index == 0) rowSize * .83f else rowSize
            label(clipped(value, widths[index], adjustedSize, currentFace), anchors[index], y,
                adjustedSize, currentFace, if (index == 3) Paint.Align.RIGHT else Paint.Align.LEFT)
        }
        y += lineHeight(rowSize, face) + 8 * scale
    }
    private fun matchTable(matches: List<MatchRecord>) {
        section("对局消费明细")
        tableRow(listOf("NO.", "英雄", "结果", "消灭 / 阵亡"), heading = true)
        rule(before = 1f, after = 10f)
        matches.sortedBy { it.startedAt }.forEachIndexed { index, match ->
            tableRow(listOf((index + 1).toString().padStart(2, '0'), match.hero ?: "—",
                when (match.result) {
                    MatchResult.WIN -> "WIN"; MatchResult.LOSS -> "LOSS"
                    MatchResult.DRAW -> "DRAW"; MatchResult.UNKNOWN -> "—"
                }, "${number(match.kills) ?: "—"} / ${number(match.deaths) ?: "—"}"))
        }
    }
    private fun dailyTable(matches: List<MatchRecord>) {
        section("每日结算明细")
        tableRow(listOf("NO.", "日期", "胜 / 负", "胜率"), heading = true)
        rule(before = 1f, after = 10f)
        matches.groupBy { date(it.startedAt, "MM.dd") }.toSortedMap().entries.forEachIndexed { index, (day, rows) ->
            val known = rows.count { it.result != MatchResult.UNKNOWN }
            val wins = rows.count { it.result == MatchResult.WIN }
            val losses = rows.count { it.result == MatchResult.LOSS }
            tableRow(listOf((index + 1).toString().padStart(2, '0'), day, "$wins / $losses",
                if (known == 0) "—" else "${number(wins * 100.0 / known)}%"))
        }
    }
    private fun highlights(block: StatsBlock) {
        section("${periodWord}高光奖励")
        val metrics = block.metrics.filter { !it.value.isNullOrBlank() }
        val cellWidth = (contentWidth - 28 * scale) / 2
        metrics.chunked(2).forEach { row ->
            var rowHeight = 0f
            row.forEachIndexed { index, metric ->
                val x = left + index * (cellWidth + 28 * scale)
                val labelSize = size(20f)
                label(metric.label, x, y, labelSize, fonts.medium)
                val value = pretty(metric.value!!)
                val valueSize = fitted(value, size(48f), fonts.display, cellWidth)
                val valueTop = y + lineHeight(labelSize, fonts.medium) + 3 * scale
                label(value, x, valueTop, valueSize, fonts.display)
                rowHeight = maxOf(rowHeight, valueTop - y + lineHeight(valueSize, fonts.display))
            }
            y += rowHeight + 19 * scale
        }
    }
    private fun pace(block: StatsBlock) {
        section("每 10 分钟表现")
        numberStrip(block.metrics.filter { !it.value.isNullOrBlank() }.map {
            it.label.substringBefore(" /") to pretty(it.value!!)
        }, points = 42f)
    }
    private fun combat(block: StatsBlock) {
        section("个人数据合计")
        val metrics = block.metrics.filter { !it.value.isNullOrBlank() }
        val cellWidth = (contentWidth - 26 * scale) / 2
        metrics.chunked(2).forEach { row ->
            var rowHeight = 0f
            row.forEachIndexed { index, metric ->
                val x = left + index * (cellWidth + 26 * scale)
                val keySize = size(20f)
                val value = pretty(metric.value!!)
                val available = cellWidth - measure(metric.label, keySize, fonts.regular) - 10 * scale
                val valueSize = fitted(value, size(26f), fonts.display, available)
                label(metric.label, x, y + 3 * scale, keySize)
                label(value, x + cellWidth, y, valueSize, fonts.display, Paint.Align.RIGHT)
                rowHeight = maxOf(rowHeight, lineHeight(keySize, fonts.regular) + 7 * scale,
                    lineHeight(valueSize, fonts.display) + 7 * scale)
            }
            y += rowHeight
        }
    }
    private fun singleMatch(block: StatsBlock) {
        val match = snapshot.matches.singleOrNull()
        if (match == null) { generic(block); return }
        center(when (match.result) {
            MatchResult.WIN -> "VICTORY"; MatchResult.LOSS -> "DEFEAT"
            MatchResult.DRAW -> "DRAW"; MatchResult.UNKNOWN -> "MATCH"
        }, 58f, fonts.display, tracking = 2f)
        gap(6f)
        match.hero?.let { center(it, 29f, fonts.bold) }
        gap(15f)
        pair("开始时间", date(match.startedAt, "MM.dd HH:mm"))
        match.map?.let { pair("战场", it) }
        match.durationSeconds?.let { pair("对局时长", formatDuration(it)) }
        val score = listOfNotNull(
            number(match.kills)?.let { "消灭" to it }, number(match.assists)?.let { "助攻" to it },
            number(match.deaths)?.let { "阵亡" to it },
        )
        if (score.isNotEmpty()) {
            rule()
            section("战斗结算")
            numberStrip(score, points = 63f)
        }
        val combat = listOf("伤害" to number(match.damage), "治疗" to number(match.healing), "减伤" to number(match.mitigation))
            .mapNotNull { (name, value) -> value?.let { name to it } }
        if (combat.isNotEmpty()) {
            rule()
            section("个人表现")
            combat.forEach { (name, value) -> pair(name, pretty(value), emphasized = true) }
        }
    }
    private fun generic(block: StatsBlock) {
        section(block.title)
        block.metrics.filter { !it.value.isNullOrBlank() }.forEach {
            pair(it.label, pretty(it.value!!) + if (it.unit.isBlank()) "" else " ${it.unit}")
        }
    }
    private fun netWins() {
        if (document.kind != ReceiptKind.SUMMARY || document.blocks.none { it.id == "summary" } || snapshot.matches.isEmpty()) return
        val net = snapshot.matches.count { it.result == MatchResult.WIN } - snapshot.matches.count { it.result == MatchResult.LOSS }
        gap(4f)
        val value = if (net > 0) "+$net" else net.toString()
        label("TOTAL · ${if (snapshot.complete) "${periodWord}净胜" else "记录净胜"}",
            left, y + 17 * scale, size(23f), fonts.bold)
        label(value, right, y, size(55f), fonts.display, Paint.Align.RIGHT)
        y += lineHeight(size(55f), fonts.display)
        rule(before = 13f, after = 17f)
    }

    /** Numeric Code 39, including start/stop guards and quiet zones, represents the receipt number. */
    private fun barcode() {
        // Standard Code 39 digit encodings; wide:narrow = 2:1, nine alternating bars/spaces per glyph.
        val encodings = intArrayOf(0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064)
        val code = "*$receiptCode*"
        val modules = code.length * 13 - 1
        val narrow = floor(contentWidth / (modules + 20)).coerceAtLeast(1f)
        var x = ((width - modules * narrow) / 2).roundToInt().toFloat()
        val top = y
        val height = 39 * scale
        code.forEachIndexed { characterIndex, character ->
            val pattern = if (character == '*') 0x094 else encodings[character.digitToInt()]
            for (bit in 0..8) {
                val barWidth = narrow * if (pattern and (1 shl (8 - bit)) != 0) 2 else 1
                if (bit % 2 == 0) {
                    val at = x
                    marks += { canvas ->
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(at, top, at + barWidth, top + height, paint)
                    }
                }
                x += barWidth
            }
            if (characterIndex < code.lastIndex) x += narrow
        }
        y += height
        gap(9f)
        center("OW-${receiptCode.take(6)}-${receiptCode.takeLast(6)}", 15f, fonts.mono, tracking = .8f)
        gap(13f)
        center("GG WP · THANK YOU", 16f, fonts.medium, tracking = 1.4f)
    }
    fun bitmap(): Bitmap {
        header()
        val blocks = document.blocks.filter { block -> block.metrics.any { !it.value.isNullOrBlank() } }
        blocks.forEach { block ->
            when {
                document.kind == ReceiptKind.MATCH -> singleMatch(block)
                block.id == "summary" -> summary(block)
                block.id == "details" -> if (snapshot.range.period == Period.TODAY) matchTable(snapshot.matches)
                    else dailyTable(snapshot.matches)
                block.id in setOf("highlights", "best") -> highlights(block)
                block.id == "pace" -> pace(block)
                block.id == "combat" -> combat(block)
                else -> generic(block)
            }
            rule(before = 15f, after = 17f)
        }
        netWins()
        gap(9f)
        barcode()
        val height = ceil(y + 32 * scale).toInt()
        require(height <= 16_000) { "小票过长，请减少内容模块或调小字号" }
        val samples = if (width.toLong() * height <= 1_500_000) 3 else if (width.toLong() * height <= 5_000_000) 2 else 1
        val source = Bitmap.createBitmap(width * samples, height * samples, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(source)
            canvas.drawColor(Color.WHITE)
            canvas.scale(samples.toFloat(), samples.toFloat())
            marks.forEach { it(canvas) }
            return if (samples == 1) source else Bitmap.createScaledBitmap(source, width, height, true)
        } finally { if (samples != 1) source.recycle() }
    }
}
