package app.futbolio.desktop

import app.futbolio.application.api.PosterModel
import app.futbolio.application.api.PosterRenderer
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage

/** Offline square match artwork in the brand palette. Rendering is separately bounded/cached by the poster store. */
class DesktopPosters : PosterRenderer {
    private val background by lazy { javaClass.getResourceAsStream("/artwork/stadium.jpg")?.use { decode(it.readBytes()) } }
    private val white = Color(255, 255, 255)
    private val muted = Color(181, 181, 181)
    private val lavender = Color(165, 160, 203)
    private val green = Color(58, 134, 56)
    private val panel = Color(38, 38, 38)
    private val edge = Color(59, 59, 59)
    private val backdrop = Color(28, 28, 28)
    private val light by lazy { bundledFont("manrope_light.ttf") }
    private val bold by lazy { bundledFont("manrope_bold.ttf") }

    private fun bundledFont(name: String): Font = runCatching {
        javaClass.getResourceAsStream("/fonts/$name")?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }.getOrNull() ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)

    override fun render(title: String, source: String) = renderMatch(PosterModel(title, source))

    override fun renderMatch(model: PosterModel): ByteArray {
        val image = BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.color = backdrop; g.fillRect(0, 0, 640, 640)
            background?.let { bg ->
                val w = (bg.width * (640.0 / bg.height)).toInt()
                g.drawImage(bg, (640 - w) / 2, 0, w, 640, null)
            }
            // Flat dimming only: the bundled photo stays visible as a quiet texture, with no gradient sweep.
            g.paint = Color(28, 28, 28, 232); g.fillRect(0, 0, 640, 640)

            fun text(value: String, x: Int, y: Int, size: Int, color: Color = white, bold: Boolean = false, center: Boolean = false) {
                g.font = (if (bold) this.bold else light).deriveFont(size.toFloat())
                g.color = color
                g.drawString(value, if (center) x - g.fontMetrics.stringWidth(value) / 2 else x, y)
            }
            fun fitted(value: String, x: Int, y: Int, width: Int, size: Int, center: Boolean = false, color: Color = white) {
                g.font = bold.deriveFont(size.toFloat())
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
            text(model.kickoffDate.ifBlank { if (model.home.isBlank()) "SPORTS CHANNEL" else "KICKOFF UNCONFIRMED" }, 50, 122, 15, lavender)
            val tag = if (model.live) { if (model.statusVerified) "LIVE" else "LIVE · ESTIMATED" } else when {
                model.status.startsWith("Ended") -> "ENDED"
                model.status.startsWith("Cancelled") -> "CANCELLED"
                model.status.startsWith("Postponed") -> "POSTPONED"
                model.home.isBlank() -> "FUTBOLIO"
                else -> "UPCOMING"
            }
            if (model.live && model.statusVerified) {
                // Verified live: the brand's pointed pill, white on green like the app's primary actions.
                g.font = bold.deriveFont(13f)
                val textWidth = g.fontMetrics.stringWidth(tag)
                val half = maxOf(64, textWidth + 24) / 2
                val right = 592; val left = right - half * 2
                g.color = green
                g.fillPolygon(intArrayOf(left, left + 9, right - 9, right, right - 9, left + 9),
                    intArrayOf(67, 54, 54, 67, 80, 80), 6)
                g.color = white; g.drawString(tag, right - half - textWidth / 2, 72)
            } else if (model.live) {
                // Estimated live: outlined pill with lavender text, because ESPN did not confirm it.
                g.font = bold.deriveFont(13f)
                val textWidth = g.fontMetrics.stringWidth(tag)
                val half = maxOf(64, textWidth + 24) / 2
                val right = 592; val left = right - half * 2
                g.color = edge; g.drawPolygon(intArrayOf(left, left + 9, right - 9, right, right - 9, left + 9),
                    intArrayOf(67, 54, 54, 67, 80, 80), 6)
                g.color = lavender; g.drawString(tag, right - half - textWidth / 2, 72)
            } else {
                g.color = edge; g.fillRect(442, 54, 148, 2)
                text(tag, 500, 76, 13, if (tag == "UPCOMING" || tag == "FUTBOLIO") lavender else muted, bold = true)
            }

            if (model.home.isNotBlank() && model.away.isNotBlank()) {
                fun team(name: String, data: ByteArray?, x: Int) {
                    if (!logo(data, x - 80, 224, 160, 160)) {
                        g.color = panel; g.fillRoundRect(x - 68, 236, 136, 136, 30, 30)
                        g.color = edge; g.drawRoundRect(x - 68, 236, 136, 136, 30, 30)
                        text(name.split(' ').filter(String::isNotBlank).take(3).joinToString("") { it.take(1) }.uppercase(Locale.ROOT), x, 318, 35, lavender, bold = true, center = true)
                    }
                    fitted(name, x, 426, 236, 28, center = true)
                }
                team(model.home, model.homeLogo, 178); team(model.away, model.awayLogo, 462)
                val scoreSize = (38 downTo 22).firstOrNull { size ->
                    g.font = bold.deriveFont(size.toFloat())
                    g.fontMetrics.stringWidth(model.score) <= 116
                } ?: 22
                text(model.score.ifBlank { "VS" }, 320, 313, if (model.score.isBlank()) 17 else scoreSize,
                    if (model.score.isBlank()) muted else white, bold = model.score.isNotBlank(), center = true)
            } else {
                val hasLogo = logo(model.channelLogo, 154, 228, 332, 120)
                fitted(model.title, 320, if (hasLogo) 424 else 292, 520, if (hasLogo) 32 else 44, center = true)
                text("LIVE SPORTS", 320, if (hasLogo) 480 else 403, 15, lavender, center = true)
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
                    g.font = bold.deriveFont(size.toFloat())
                    g.fontMetrics.stringWidth(model.minute) <= 112
                } ?: 20
                text(model.minute, 554, 578, minuteSize, green, bold = true, center = true)
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
        mark.subtract(Area(f)); g.color = green; g.fill(mark)
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
