package dev.owreceipt.core

import org.junit.Assert.*
import org.junit.Test

class PrinterSelectionTest {
    @Test fun onlyTheFixedModelPrefixIsAccepted() {
        listOf("9516", "9516-ABCD", "9516_打印机").forEach { name ->
            assertTrue(name, PrinterDevice("id", name).isWp9516)
        }
        listOf("", "WP9516", "打印机9516", " 9516", "9515-ABCD", "Headphones", "Unknown").forEach { name ->
            assertFalse(name, PrinterDevice("id", name).isWp9516)
        }
    }
}
