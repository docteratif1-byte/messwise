package com.kodawick.messwise.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun formats() {
        assertEquals("Rs 5,000", Money.format(500_000))
        assertEquals("Rs 25", Money.format(2_500))
        assertEquals("Rs 1,234.50", Money.format(123_450))
        assertEquals("-Rs 750", Money.format(-75_000))
        assertEquals("Rs 0", Money.format(0))
    }

    @Test fun parses() {
        assertEquals(500_000L, Money.parse("5,000"))
        assertEquals(500_050L, Money.parse("5000.5"))
        assertEquals(120_000L, Money.parse("Rs 1,200"))
        assertEquals(75_000L, Money.parse("۷۵۰"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("12.345"))
        assertNull(Money.parse("abc"))
    }

    @Test fun roundTripsInput() {
        for (p in listOf(0L, 100L, 2_550L, 500_000L, 123_405L)) assertEquals(p, Money.parse(Money.toInput(p)))
    }

    /** The worked example from the blueprint: Rs 5,000 of tea for 200 cups is Rs 25 a cup; 30 cups is Rs 750. */
    @Test fun teaExample() {
        val total = Money.parse("5000")!!
        val rate = total / 200
        assertEquals("Rs 25", Money.format(rate))
        assertEquals("Rs 750", Money.format(rate * 30))
    }
}

class XlsxTest {
    @Test fun writesAWorkbook() {
        val bytes = XlsxWriter.toBytes(listOf(
            Sheet("Tea bills", listOf("Name", "Cups", "Bill"), listOf(listOf("Riaz", 30, 750.0), listOf("Sara & Co", 12, 300.0))),
            Sheet("Second", listOf("A"), listOf(listOf("x"))),
        ))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertTrue(names.contains("xl/workbook.xml"))
        assertTrue(names.contains("xl/worksheets/sheet1.xml"))
        assertTrue(names.contains("xl/worksheets/sheet2.xml"))
        val rows = TableReader.readXlsx(ByteArrayInputStream(bytes))
        assertEquals(listOf("Name", "Cups", "Bill"), rows[0])
        assertEquals("Sara & Co", rows[2][0])
    }
}
