package app.futbolio.android

import android.content.Context
import android.graphics.*
import androidx.core.content.res.ResourcesCompat
import app.futbolio.application.api.PosterModel
import app.futbolio.application.api.PosterRenderer
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Canvas counterpart of DesktopPosters, with identical square layout and offline assets. */
class AndroidPosters : PosterRenderer {
    private var background: Bitmap? = null
    private var light: Typeface? = null
    private var bold: Typeface? = null
    private val white = Color.WHITE
    private val muted = Color.rgb(181, 181, 181)
    private val lavender = Color.rgb(165, 160, 203)
    private val green = Color.rgb(58, 134, 56)
    private val panel = Color.rgb(38, 38, 38)
    private val edge = Color.rgb(59, 59, 59)
    private val backdrop = Color.rgb(28, 28, 28)

    /** Bundled Manrope; posters render on the system typeface when the service has not set this up yet. */
    @Synchronized fun loadFonts(context: Context) {
        light = ResourcesCompat.getFont(context, R.font.manrope_light) ?: light
        bold = ResourcesCompat.getFont(context, R.font.manrope_bold) ?: bold
    }

    override fun render(title: String, source: String) = renderMatch(PosterModel(title, source))

    @Synchronized fun trim() { background?.recycle(); background = null }

    @Synchronized override fun renderMatch(model: PosterModel): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bitmap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            fun typeface(boldface: Boolean) = (if (boldface) bold else light) ?: Typeface.DEFAULT
            c.drawColor(backdrop)
            if (background == null) background = javaClass.getResourceAsStream("/artwork/stadium.jpg")?.use { decode(it.readBytes()) }
            background?.let { bg ->
                val width = bg.width * (640f / bg.height)
                c.drawBitmap(bg, null, RectF((640 - width) / 2, 0f, (640 + width) / 2, 640f), p)
            }
            // Flat dimming only: the bundled photo stays visible as a quiet texture, with no gradient sweep.
            p.color = Color.argb(232, 28, 28, 28)
            c.drawRect(0f, 0f, 640f, 640f, p); p.shader = null

            fun text(value: String, x: Float, y: Float, size: Float, color: Int = white, boldface: Boolean = false, center: Boolean = false) {
                p.color = color; p.textSize = size; p.typeface = typeface(boldface)
                p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
                c.drawText(value, x, y, p)
            }
            fun fitted(value: String, x: Float, y: Float, width: Float, size: Float, center: Boolean = false, color: Int = white) {
                p.typeface = typeface(true)
                var rest = value.take(160)
                repeat(2) { line ->
                    if (rest.isEmpty()) return@repeat
                    var end = p.breakText(rest, true, width, null).coerceAtLeast(1)
                    if (end < rest.length && rest.lastIndexOf(' ', end) > 0) end = rest.lastIndexOf(' ', end)
                    var part = rest.take(end)
                    if (line == 1 && end < rest.length) {
                        while (part.isNotEmpty() && p.measureText("$part…") > width) part = part.dropLast(1)
                        part += "…"
                    }
                    text(part, x, y + line * (size + 9), size, color, boldface = true, center = center)
                    rest = rest.drop(end).trim()
                }
            }
            fun pointPill(left: Float, right: Float, tip: Float, top: Float, bottom: Float): Path = Path().apply {
                val cy = (top + bottom) / 2f
                moveTo(left, cy); lineTo(left + tip, top); lineTo(right - tip, top); lineTo(right, cy)
                lineTo(right - tip, bottom); lineTo(left + tip, bottom); close()
            }
            fun logo(data: ByteArray?, x: Float, y: Float, width: Float, height: Float): Boolean {
                val image = data?.let(::decode) ?: return false
                try {
                    val scale = minOf(width / image.width, height / image.height)
                    val w = image.width * scale; val h = image.height * scale
                    p.color = Color.WHITE
                    c.drawBitmap(image, null, RectF(x + (width - w) / 2, y + (height - h) / 2, x + (width + w) / 2, y + (height + h) / 2), p)
                } finally { image.recycle() }
                return true
            }

            text(model.kickoffTime.ifBlank { if (model.home.isBlank()) "24/7" else "TBC" }, 48f, 93f, 49f, boldface = true)
            text(model.kickoffDate.ifBlank { if (model.home.isBlank()) "SPORTS CHANNEL" else "KICKOFF UNCONFIRMED" }, 50f, 122f, 15f, lavender)
            val tag = if (model.live) { if (model.statusVerified) "LIVE" else "LIVE · ESTIMATED" } else when {
                model.status.startsWith("Ended") -> "ENDED"
                model.status.startsWith("Cancelled") -> "CANCELLED"
                model.status.startsWith("Postponed") -> "POSTPONED"
                model.home.isBlank() -> "FUTBOLIO"
                else -> "UPCOMING"
            }
            if (model.live && model.statusVerified) {
                // Verified live: the brand's pointed pill, white on green like the app's primary actions.
                p.typeface = typeface(true); p.textSize = 13f
                val width = p.measureText(tag)
                val half = maxOf(64f, width + 24f) / 2f
                val right = 592f; val left = right - half * 2
                p.color = green; c.drawPath(pointPill(left, right, 9f, 54f, 80f), p)
                text(tag, right - half - width / 2, 72f, 13f, white, boldface = true)
            } else if (model.live) {
                // Estimated live: outlined pill with lavender text, because ESPN did not confirm it.
                p.typeface = typeface(true); p.textSize = 13f
                val width = p.measureText(tag)
                val half = maxOf(64f, width + 24f) / 2f
                val right = 592f; val left = right - half * 2
                p.color = edge; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                c.drawPath(pointPill(left, right, 9f, 54f, 80f), p); p.style = Paint.Style.FILL
                text(tag, right - half - width / 2, 72f, 13f, lavender, boldface = true)
            } else {
                p.color = edge; c.drawRect(442f, 54f, 590f, 56f, p)
                text(tag, 500f, 76f, 13f, if (tag == "UPCOMING" || tag == "FUTBOLIO") lavender else muted, boldface = true)
            }
            if (model.home.isNotBlank() && model.away.isNotBlank()) {
                fun team(name: String, data: ByteArray?, x: Float) {
                    if (!logo(data, x - 80, 224f, 160f, 160f)) {
                        p.color = panel
                        c.drawRoundRect(x - 68, 236f, x + 68, 372f, 15f, 15f, p)
                        p.style = Paint.Style.STROKE; p.color = edge
                        c.drawRoundRect(x - 68, 236f, x + 68, 372f, 15f, 15f, p); p.style = Paint.Style.FILL
                        text(name.split(' ').filter(String::isNotBlank).take(3).joinToString("") { it.take(1) }.uppercase(Locale.ROOT), x, 318f, 35f, lavender, boldface = true, center = true)
                    }
                    fitted(name, x, 426f, 236f, 28f, center = true)
                }
                team(model.home, model.homeLogo, 178f); team(model.away, model.awayLogo, 462f)
                p.typeface = typeface(true)
                val scoreSize = (38 downTo 22).firstOrNull { size ->
                    p.textSize = size.toFloat()
                    p.measureText(model.score) <= 116f
                }?.toFloat() ?: 22f
                text(model.score.ifBlank { "VS" }, 320f, 313f, if (model.score.isBlank()) 17f else scoreSize,
                    if (model.score.isBlank()) muted else white, boldface = model.score.isNotBlank(), center = true)
            } else {
                val hasLogo = logo(model.channelLogo, 154f, 228f, 332f, 120f)
                fitted(model.title, 320f, if (hasLogo) 424f else 292f, 520f, if (hasLogo) 32f else 44f, center = true)
                text("LIVE SPORTS", 320f, if (hasLogo) 480f else 403f, 15f, lavender, center = true)
            }
            if (model.competitionLightBackground && model.competitionLogo != null) {
                p.color = white; c.drawRoundRect(262f, 490f, 378f, 586f, 8f, 8f, p)
            }
            if (!logo(model.competitionLogo, 270f, 496f, 100f, 82f) && model.home.isNotBlank()) {
                p.color = muted; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
                c.drawArc(298f, 517f, 342f, 555f, 0f, 180f, false, p)
                c.drawLine(298f, 536f, 298f, 518f, p); c.drawLine(342f, 536f, 342f, 518f, p)
                c.drawLine(298f, 518f, 342f, 518f, p); c.drawLine(320f, 555f, 320f, 567f, p)
                c.drawLine(307f, 568f, 333f, 568f, p); p.style = Paint.Style.FILL
            }
            fitted(model.league.uppercase(Locale.ROOT), 320f, 603f, 390f, 14f, center = true, color = muted)
            if (model.minute.isNotBlank()) {
                p.typeface = typeface(true)
                val minuteSize = (32 downTo 20).firstOrNull { size ->
                    p.textSize = size.toFloat()
                    p.measureText(model.minute) <= 112f
                }?.toFloat() ?: 20f
                text(model.minute, 554f, 578f, minuteSize, green, boldface = true, center = true)
            }
            val mark = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                moveTo(65f, 553f); lineTo(86f, 574f); lineTo(65f, 595f); lineTo(44f, 574f); close()
                moveTo(57f, 563f); lineTo(73f, 563f); lineTo(73f, 569f); lineTo(63f, 569f)
                lineTo(63f, 573f); lineTo(71f, 573f); lineTo(71f, 579f); lineTo(63f, 579f)
                lineTo(63f, 585f); lineTo(57f, 585f); close()
            }
            p.color = green; c.drawPath(mark, p)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 84, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        if (bytes.size > 1_048_576) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1024) })
    }.getOrNull()
}
