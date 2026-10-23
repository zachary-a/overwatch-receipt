package dev.owreceipt.core

data class PrinterDevice(val id: String, val name: String, val bonded: Boolean = true) {
    // This model's Bluetooth name prefix is fixed, never a user preference.
    val isWp9516: Boolean get() = name.startsWith("9516")
}
enum class ConnectionStrategy(val label: String) {
    AUTO("自动尝试"), SPP("公开 SPP"), CHANNEL_ONE("RFCOMM 通道 1"),
}
enum class PrintDensity(val label: String, val wireValue: Int) {
    LOW("浅", 0x13), MEDIUM("中", 0x1b), HIGH("深", 0x22),
}
data class PrintSettings(
    val density: PrintDensity = PrintDensity.MEDIUM,
    val copies: Int = 1,
    // App printing uses one copy and 140 trailing rows; protocol tests may override these values.
    val trailingFeedRows: Int = 140,
) {
    init { require(copies in 1..5 && trailingFeedRows in 0..600) }
}
data class PrinterInfo(
    val widthDots: Int,
    val busy: Boolean,
    val lowPower: Boolean,
    val overheated: Boolean,
    val lidOpen: Boolean,
    val paperOut: Boolean,
    val transportType: Int?,
    val firmware: Int?,
) {
    val printable: Boolean get() = !busy && !lowPower && !overheated && !lidOpen && !paperOut
    val profile: PrinterProfile get() = PrinterProfile(widthDots)
}
val PrintStatus.active: Boolean get() = this in setOf(PrintStatus.PREPARING, PrintStatus.SENDING,
    PrintStatus.WAITING_BUFFER, PrintStatus.WAITING_IDLE, PrintStatus.PAPER_OUT, PrintStatus.STOPPING)
