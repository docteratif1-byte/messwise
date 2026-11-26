package com.kodawick.messwise.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One worksheet: a header row, then rows of text or numbers. */
data class Sheet(val name: String, val header: List<String>, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

/** Minimal .xlsx writer (Office Open XML) — enough for clean reports that open in Excel, Google Sheets and WPS. */
object XlsxWriter {
    fun write(sheets: List<Sheet>, out: OutputStream) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out).use { zip ->
            fun put(path: String, body: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                val used = mutableSetOf<String>()
                sheets.forEachIndexed { i, s ->
                    var n = safeSheetName(s.name).ifEmpty { "Sheet${i + 1}" }
                    if (!used.add(n)) { n = (n.take(28) + " ${i + 1}"); used.add(n) }
                    append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            })
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF4A2545"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    fun toBytes(sheets: List<Sheet>): ByteArray = ByteArrayOutputStream().also { write(sheets, it) }.toByteArray()

    private fun sheetXml(s: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        val cols = maxOf(s.header.size, s.rows.maxOfOrNull { it.size } ?: 0)
        if (cols > 0) {
            append("<cols>")
            for (c in 0 until cols) {
                val w = s.widths.getOrNull(c) ?: guessWidth(s, c)
                append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        var r = 1
        if (s.header.isNotEmpty()) {
            append("""<row r="$r">""")
            s.header.forEachIndexed { c, h -> append(cell(c, r, h, style = 1)) }
            append("</row>")
            r++
        }
        for (row in s.rows) {
            append("""<row r="$r">""")
            row.forEachIndexed { c, v -> if (v != null) append(cell(c, r, v, style = if (v is Money2) 2 else 0)) }
            append("</row>")
            r++
        }
        append("</sheetData></worksheet>")
    }

    /** Wrap an amount in paisa to show it as rupees with two decimals. */
    data class Money2(val paisa: Long)

    private fun cell(c: Int, r: Int, v: Any, style: Int): String {
        val ref = colName(c) + r
        val s = if (style != 0) """ s="$style"""" else ""
        return when (v) {
            is Money2 -> """<c r="$ref"$s><v>${v.paisa / 100}.${(kotlin.math.abs(v.paisa) % 100).toString().padStart(2, '0')}</v></c>""".let {
                if (v.paisa < 0 && v.paisa / 100 == 0L) it.replace("<v>0.", "<v>-0.") else it
            }
            is Int, is Long -> """<c r="$ref"$s><v>$v</v></c>"""
            is Double -> """<c r="$ref"$s><v>$v</v></c>"""
            is Boolean -> """<c r="$ref" t="b"$s><v>${if (v) 1 else 0}</v></c>"""
            else -> """<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>"""
        }
    }

    private fun guessWidth(s: Sheet, c: Int): Int {
        val lens = (listOfNotNull(s.header.getOrNull(c)) + s.rows.take(200).mapNotNull { it.getOrNull(c)?.let { v -> if (v is Money2) "000,000.00" else v.toString() } }).map { it.length }
        return ((lens.maxOrNull() ?: 8) + 2).coerceIn(6, 50)
    }

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun safeSheetName(n: String) = n.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31)

    private fun esc(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                else -> append(ch)
            }
        }
    }
}

/** Reads the first worksheet of an .xlsx file or a .csv file into rows of text (for importing members). */
object TableReader {
    fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val delim = if (text.lineSequence().firstOrNull()?.let { it.count { c -> c == ';' } > it.count { c -> c == ',' } } == true) ';' else ','
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var i = 0
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val ch = t[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delim -> { row.add(field.toString().trim()); field = StringBuilder() }
                '\r' -> {}
                '\n' -> { row.add(field.toString().trim()); rows.add(row); row = mutableListOf(); field = StringBuilder() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString().trim()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    fun readXlsx(input: InputStream): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name == "xl/workbook.xml" || e.name.startsWith("xl/worksheets/sheet") || e.name == "xl/_rels/workbook.xml.rels") {
                    files[e.name] = zip.readBytes()
                }
                e = zip.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parse(it) }?.let { doc ->
            val list = mutableListOf<String>()
            val sis = doc.getElementsByTagName("si")
            for (k in 0 until sis.length) {
                val si = sis.item(k) as Element
                val ts = si.getElementsByTagName("t")
                val sb = StringBuilder()
                for (j in 0 until ts.length) sb.append(ts.item(j).textContent)
                list.add(sb.toString())
            }
            list
        } ?: emptyList()
        val sheetPath = firstSheetPath(files) ?: return emptyList()
        val doc = parse(files[sheetPath] ?: return emptyList())
        val out = mutableListOf<List<String>>()
        val rowNodes = doc.getElementsByTagName("row")
        for (k in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(k) as Element
            val cells = rowEl.getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val col = colIndex(c.getAttribute("r")).takeIf { it >= 0 } ?: j
                val type = c.getAttribute("t")
                val v = c.getElementsByTagName("v").item(0)?.textContent
                val text = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> { val ts = c.getElementsByTagName("t"); (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.let { cleanNumber(it) } ?: ""
                }
                values[col] = text.trim()
            }
            if (values.isEmpty()) continue
            val max = values.lastKey()
            out.add((0..max).map { values[it] ?: "" })
        }
        return out.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun cleanNumber(v: String): String {
        // Phone numbers saved as numbers lose their leading zero and may come back as 3.001234567E9.
        val d = v.toDoubleOrNull() ?: return v
        return if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else v
    }

    private fun firstSheetPath(files: Map<String, ByteArray>): String? {
        val wb = files["xl/workbook.xml"]?.let { parse(it) }
        val rels = files["xl/_rels/workbook.xml.rels"]?.let { parse(it) }
        if (wb != null && rels != null) {
            val sheet = wb.getElementsByTagName("sheet").item(0) as? Element
            val rid = sheet?.getAttribute("r:id")?.ifEmpty { null }
                ?: sheet?.attributes?.let { a -> (0 until a.length).map { a.item(it) }.firstOrNull { it.nodeName.endsWith(":id") }?.nodeValue }
            val relNodes = rels.getElementsByTagName("Relationship")
            for (k in 0 until relNodes.length) {
                val r = relNodes.item(k) as Element
                if (r.getAttribute("Id") == rid) {
                    val target = r.getAttribute("Target").removePrefix("/xl/").removePrefix("/")
                    val path = if (target.startsWith("xl/")) target else "xl/$target"
                    if (files.containsKey(path)) return path
                }
            }
        }
        return files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        var any = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); any = true } else break
        }
        return if (any) n - 1 else -1
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
    }.newDocumentBuilder().parse(bytes.inputStream())
}
