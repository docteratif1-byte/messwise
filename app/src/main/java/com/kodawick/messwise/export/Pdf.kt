package com.kodawick.messwise.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Simple A4 page writer: headings, label/value rows and tables, with automatic new pages. */
class PdfWriter(private val title: String, private val subtitle: String, private val placeName: String) {
    val doc = PdfDocument()
    private val W = 595
    private val H = 842
    private val M = 40f
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    var y = 0f
    private var pageNo = 0
    var generated = ""

    private val navy = Color.rgb(74, 37, 69)
    private val green = Color.rgb(242, 169, 0)
    private val gray = Color.rgb(90, 102, 115)
    private val line = Color.rgb(215, 223, 232)

    fun text(size: Float, bold: Boolean = false, color: Int = Color.rgb(23, 33, 43)) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun newPage() {
        finishPage()
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        page = p
        val c = p.canvas
        canvas = c
        val band = Paint().apply { color = navy }
        c.drawRect(0f, 0f, W.toFloat(), 64f, band)
        c.drawCircle(M + 14, 32f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
        c.drawText("M", M + 7.5f, 38f, text(16f, true, Color.rgb(42, 29, 20)))
        c.drawText("MessWise", M + 36, 30f, text(16f, true, Color.WHITE))
        c.drawText(placeName, M + 36, 46f, text(9f, false, Color.rgb(241, 220, 236)))
        c.drawText(title, W - M - text(13f, true).measureText(title), 30f, text(13f, true, Color.WHITE))
        c.drawText(subtitle, W - M - text(9f).measureText(subtitle), 46f, text(9f, false, Color.rgb(241, 220, 236)))
        val foot = "MessWise by KodaWick · $generated · Page $pageNo"
        c.drawText(foot, M, H - 22f, text(8f, false, gray))
        y = 92f
    }

    private fun finishPage() { page?.let { doc.finishPage(it) }; page = null }

    fun ensure(h: Float) { if (page == null || y + h > H - 48) newPage() }

    fun heading(s: String) {
        ensure(34f)
        y += 8
        canvas!!.drawText(s, M, y + 12, text(13f, true, navy))
        y += 22
        canvas!!.drawLine(M, y, W - M, y, Paint().apply { color = line })
        y += 8
    }

    fun para(s: String, size: Float = 10f, color: Int = gray) {
        val tp = text(size, false, color)
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, tp, (W - 2 * M).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        ensure(layout.height + 6f)
        canvas!!.save(); canvas!!.translate(M, y); layout.draw(canvas!!); canvas!!.restore()
        y += layout.height + 6
    }

    fun row(label: String, value: String, strong: Boolean = false) {
        ensure(20f)
        canvas!!.drawText(label, M, y + 12, text(10.5f, false, gray))
        val vp = text(if (strong) 12f else 10.5f, strong)
        canvas!!.drawText(value, W - M - vp.measureText(value), y + 12, vp)
        y += if (strong) 22 else 19
    }

    fun box(block: () -> Unit) {
        val top = y
        y += 10
        block()
        y += 6
        canvas!!.drawRoundRect(RectF(M - 10, top, W - M + 10, y), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = line; strokeWidth = 1.2f })
        y += 10
    }

    /** Table with columns given as relative widths; numbers right-aligned when [right] contains the column. */
    fun table(header: List<String>, widths: List<Float>, rows: List<List<String>>, right: Set<Int> = emptySet()) {
        val total = widths.sum()
        val cw = widths.map { it / total * (W - 2 * M) }
        fun drawRow(cells: List<String>, bold: Boolean, bg: Int?) {
            ensure(18f)
            if (bg != null) canvas!!.drawRect(M - 4, y, W - M + 4, y + 18, Paint().apply { color = bg })
            var x = M
            cells.forEachIndexed { i, cell ->
                val p = text(8.6f, bold, if (bold) Color.WHITE.takeIf { bg == navy } ?: Color.BLACK else Color.rgb(23, 33, 43))
                var t = cell
                while (t.isNotEmpty() && p.measureText(t) > cw[i] - 4) t = t.dropLast(1)
                if (t.length < cell.length && t.length > 1) t = t.dropLast(1) + "…"
                val tx = if (i in right) x + cw[i] - 2 - p.measureText(t) else x + 2
                canvas!!.drawText(t, tx, y + 12.5f, p)
                x += cw[i]
            }
            y += 18
        }
        drawRow(header, true, navy)
        rows.forEachIndexed { i, r ->
            if (page == null || y + 18 > H - 48) { newPage(); drawRow(header, true, navy) }
            drawRow(r, false, if (i % 2 == 1) Color.rgb(251, 243, 231) else null)
        }
        y += 8
    }

    fun space(h: Float) { y += h }

    fun bytes(): ByteArray {
        finishPage()
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}

object Share {
    const val PDF = "application/pdf"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun file(ctx: Context, name: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().replace(' ', '_').ifEmpty { "MessWise" }.take(60)

    /** Opens the share sheet (WhatsApp, Gmail, Drive...) with the file attached. */
    fun send(ctx: Context, name: String, bytes: ByteArray, mime: String) {
        val f = file(ctx, name, bytes)
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Saves into the phone's Downloads folder. Returns true when saved. */
    fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray, mime: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MessWise")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MessWise").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            true
        }
    } catch (_: Exception) { false }
}
