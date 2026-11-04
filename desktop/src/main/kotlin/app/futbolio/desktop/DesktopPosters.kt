package app.futbolio.desktop

import app.futbolio.application.api.PosterModel
import app.futbolio.application.api.PosterRenderer
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage

/** Offline square match artwork. Rendering is separately bounded/cached by the poster store. */
class DesktopPosters : PosterRenderer {
    private val background by lazy { javaClass.getResourceAsStream("/artwork/stadium.jpg")?.use { decode(it.readBytes()) } }
    private val white = Color(245, 248, 251)
    private val muted = Color(174, 191, 201)
    private val mint = Color(141, 237, 184)

    override fun render(title: String, source: String) = renderMatch(PosterModel(title, source))

    override fun renderMatch(model: PosterModel): ByteArray {
        val image = BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.color = Color(8, 15, 22); g.fillRect(0, 0, 640, 640)
            background?.let { bg ->
                val w = (bg.width * (640.0 / bg.height)).toInt()
                g.drawImage(bg, (640 - w) / 2, 0, w, 640, null)
            }
            g.paint = GradientPaint(0f, 0f, Color(7, 14, 22, 220), 640f, 640f, Color(3, 9, 16, 245))
            g.fillRect(0, 0, 640, 640)
            g.paint = RadialGradientPaint(610f, 90f, 500f, floatArrayOf(0f, 1f), arrayOf(Color(141, 237, 184, 24), Color(141, 237, 184, 0)))
            g.fillRect(0, 0, 640, 640)

            fun text(value: String, x: Int, y: Int, size: Int, color: Color = white, bold: Boolean = false, center: Boolean = false) {
                g.font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
                g.color = color
                g.drawString(value, if (center) x - g.fontMetrics.stringWidth(value) / 2 else x, y)
            }
            fun fitted(value: String, x: Int, y: Int, width: Int, size: Int, center: Boolean = false, color: Color = white) {
                g.font = Font(Font.SANS_SERIF, Font.BOLD, size)
                var rest = value.take(160)
                repeat(2) { line ->
                    if (rest.isEmpty()) return@repeat
                    var end = rest.length
                    while (end > 1 && g.fontMetrics.stringWidth(rest.take(end)) > width) end--
                    if (end < rest.length && rest.lastIndexOf(' ', end) > 0) end = rest.lastIndexOf(' ', end)
                    var part = rest.take(end)
                    if (line == 1 && end < rest.length) {
                        while (part.isNotEmpty() && g.fontMetrics.stringWidth("$part…") > width) part = part.dropLast(1)
                        part += "…"
                    }
                    text(part, x, y + line * (size + 9), size, color, bold = true, center = center)
                    rest = rest.drop(end).trim()
                }
            }
            fun logo(data: ByteArray?, x: Int, y: Int, width: Int, height: Int): Boolean {
                val icon = data?.let(::decode) ?: return false
                val scale = minOf(width.toDouble() / icon.width, height.toDouble() / icon.height)
                val w = (icon.width * scale).toInt(); val h = (icon.height * scale).toInt()
                g.drawImage(icon, x + (width - w) / 2, y + (height - h) / 2, w, h, null)
                return true
            }

            text(model.kickoffTime.ifBlank { if (model.home.isBlank()) "24/7" else "TBC" }, 48, 93, 49, bold = true)
            text(model.kickoffDate.ifBlank { if (model.home.isBlank()) "SPORTS CHANNEL" else "KICKOFF UNCONFIRMED" }, 50, 122, 15, muted)
            val tag = if (model.live) { if (model.statusVerified) "LIVE" else "LIVE · ESTIMATED" } else when {
                model.status.startsWith("Ended") -> "ENDED"
                model.status.startsWith("Cancelled") -> "CANCELLED"
                model.status.startsWith("Postponed") -> "POSTPONED"
                model.home.isBlank() -> "FUTBOLIO"
                else -> "UPCOMING"
            }
            g.color = mint; g.fillRect(442, 54, 148, 3)
            text(tag, 442, 80, 13, mint, bold = true)

            if (model.home.isNotBlank() && model.away.isNotBlank()) {
                fun team(name: String, data: ByteArray?, x: Int) {
                    if (!logo(data, x - 80, 224, 160, 160)) {
                        g.color = Color(141, 237, 184, 18); g.fillRoundRect(x - 68, 236, 136, 136, 30, 30)
                        g.color = Color(141, 237, 184, 80); g.drawRoundRect(x - 68, 236, 136, 136, 30, 30)
                        text(name.split(' ').filter(String::isNotBlank).take(3).joinToString("") { it.take(1) }.uppercase(Locale.ROOT), x, 318, 35, mint, bold = true, center = true)
                    }
                    fitted(name, x, 426, 236, 28, center = true)
                }
                team(model.home, model.homeLogo, 178); team(model.away, model.awayLogo, 462)
                val scoreSize = (38 downTo 22).firstOrNull { size ->
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, size)
                    g.fontMetrics.stringWidth(model.score) <= 116
                } ?: 22
                text(model.score.ifBlank { "VS" }, 320, 313, if (model.score.isBlank()) 17 else scoreSize,
                    if (model.score.isBlank()) muted else white, bold = model.score.isNotBlank(), center = true)
            } else {
                val hasLogo = logo(model.channelLogo, 154, 228, 332, 120)
                fitted(model.title, 320, if (hasLogo) 424 else 292, 520, if (hasLogo) 32 else 44, center = true)
                text("LIVE SPORTS", 320, if (hasLogo) 480 else 403, 15, muted, center = true)
            }
            if (model.competitionLightBackground && model.competitionLogo != null) {
                g.color = white; g.fillRoundRect(262, 490, 116, 96, 16, 16)
            }
            if (!logo(model.competitionLogo, 270, 496, 100, 82) && model.home.isNotBlank()) {
                // Neutral trophy, never a fabricated official competition mark.
                g.color = muted; g.stroke = BasicStroke(2.5f)
                g.drawArc(298, 517, 44, 38, 180, 180); g.drawLine(298, 536, 298, 518); g.drawLine(342, 536, 342, 518)
                g.drawLine(298, 518, 342, 518); g.drawLine(320, 555, 320, 567); g.drawLine(307, 568, 333, 568)
            }
            fitted(model.league.uppercase(Locale.ROOT), 320, 603, 390, 14, center = true, color = muted)
            if (model.minute.isNotBlank()) {
                val minuteSize = (32 downTo 20).firstOrNull { size ->
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, size)
                    g.fontMetrics.stringWidth(model.minute) <= 112
                } ?: 20
                text(model.minute, 554, 578, minuteSize, mint, bold = true, center = true)
            }
            brand(g)
        } finally { g.dispose() }
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .84f
                }
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally { writer.dispose() }
        return bytes.toByteArray()
    }

    private fun brand(g: Graphics2D) {
        val mark = Area(Path2D.Double().apply {
            moveTo(65.0, 553.0); lineTo(86.0, 574.0); lineTo(65.0, 595.0); lineTo(44.0, 574.0); closePath()
        })
        val f = Path2D.Double().apply {
            moveTo(57.0, 563.0); lineTo(73.0, 563.0); lineTo(73.0, 569.0); lineTo(63.0, 569.0)
            lineTo(63.0, 573.0); lineTo(71.0, 573.0); lineTo(71.0, 579.0); lineTo(63.0, 579.0)
            lineTo(63.0, 585.0); lineTo(57.0, 585.0); closePath()
        }
        mark.subtract(Area(f)); g.color = Color.WHITE; g.fill(mark)
    }

    private fun decode(bytes: ByteArray): BufferedImage? = runCatching {
        if (bytes.size > 1_048_576) return null
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) null else {
                val reader = readers.next()
                try { reader.input = input; if (reader.getWidth(0) in 1..2048 && reader.getHeight(0) in 1..2048) reader.read(0) else null }
                finally { reader.dispose() }
            }
        }
    }.getOrNull()
}
