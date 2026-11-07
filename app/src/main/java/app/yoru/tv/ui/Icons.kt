package app.yoru.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Иконки из веб-Yoru (SVG 24×24), без тяжёлой библиотеки material-icons. */
object Ic {
    private class P(val d: String, val fill: Boolean = false)

    private fun s(d: String) = P(d)
    private fun f(d: String) = P(d, fill = true)

    private fun make(name: String, stroke: Float, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (p in paths) {
            val nodes = PathParser().parsePathString(p.d).toNodes()
            if (p.fill) {
                b.addPath(pathData = nodes, fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    pathData = nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val home by lazy { make("home", 1.8f, s("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")) }
    val catalog by lazy { make("catalog", 1.8f, s("M3 3h7v9H3zM14 3h7v9h-7zM3 15h7v6H3zM14 15h7v6h-7z")) }
    val bookmark by lazy { make("bookmark", 1.8f, s("M6 3h12a1 1 0 0 1 1 1v17l-7-4.5L5 21V4a1 1 0 0 1 1-1z")) }
    val search by lazy { make("search", 2f, s("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0z"), s("M20 20l-3.5-3.5")) }
    val gear by lazy {
        make(
            "gear", 1.8f,
            s("M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z"),
            s("M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"),
        )
    }
    val sliders by lazy {
        make(
            "sliders", 2f,
            s("M4 7h10M18 7h2M4 17h4M12 17h8"),
            s("M18.2 7a2.2 2.2 0 1 1-4.4 0a2.2 2.2 0 1 1 4.4 0z"),
            s("M12.2 17a2.2 2.2 0 1 1-4.4 0a2.2 2.2 0 1 1 4.4 0z"),
        )
    }
    val play by lazy { make("play", 0f, f("M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z")) }
    val pause by lazy {
        make(
            "pause", 0f,
            f("M6.8 4h1.9a1.3 1.3 0 0 1 1.3 1.3v13.4a1.3 1.3 0 0 1-1.3 1.3H6.8a1.3 1.3 0 0 1-1.3-1.3V5.3A1.3 1.3 0 0 1 6.8 4z"),
            f("M15.3 4h1.9a1.3 1.3 0 0 1 1.3 1.3v13.4a1.3 1.3 0 0 1-1.3 1.3h-1.9a1.3 1.3 0 0 1-1.3-1.3V5.3A1.3 1.3 0 0 1 15.3 4z"),
        )
    }
    val back10 by lazy { make("back10", 1.9f, s("M3.5 12a8.5 8.5 0 1 0 2.6-6.1L3.5 8.5"), s("M3.5 3.5v5h5")) }
    val fwd10 by lazy { make("fwd10", 1.9f, s("M20.5 12a8.5 8.5 0 1 1-2.6-6.1l2.6 2.6"), s("M20.5 3.5v5h-5")) }
    val next by lazy {
        make("next", 0f, f("M5 5.2v13.6a.8.8 0 0 0 1.2.7l10.3-6.8a.8.8 0 0 0 0-1.4L6.2 4.5a.8.8 0 0 0-1.2.7z"), f("M17.2 4.5h2.6v15h-2.6z"))
    }
    val prev by lazy {
        make("prev", 0f, f("M19 5.2v13.6a.8.8 0 0 1-1.2.7L7.5 12.7a.8.8 0 0 1 0-1.4l10.3-6.8a.8.8 0 0 1 1.2.7z"), f("M4.2 4.5h2.6v15H4.2z"))
    }
    val skip by lazy { make("skip", 0f, f("M3.5 6v12l8.5-6zM12 6v12l8.5-6z")) }
    val cc by lazy {
        make(
            "cc", 2f,
            s("M6 5h12a3.5 3.5 0 0 1 3.5 3.5v7A3.5 3.5 0 0 1 18 19H6a3.5 3.5 0 0 1-3.5-3.5v-7A3.5 3.5 0 0 1 6 5z"),
            s("M10.6 10.3a2.4 2.4 0 1 0 0 3.4M17.1 10.3a2.4 2.4 0 1 0 0 3.4"),
        )
    }
    val voice by lazy { make("voice", 2f, s("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z"), s("M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")) }
    val check by lazy { make("check", 2.6f, s("M5 12.5l4.5 4.5L19 7.5")) }
    val heart by lazy {
        make("heart", 2f, s("M12 20s-7.5-4.6-9.2-9.4C1.6 7.2 3.8 4 7.2 4c2 0 3.6 1.1 4.8 2.8C13.2 5.1 14.8 4 16.8 4c3.4 0 5.6 3.2 4.4 6.6C19.5 15.4 12 20 12 20z"))
    }
    val heartFill by lazy {
        make("heartFill", 0f, f("M12 20s-7.5-4.6-9.2-9.4C1.6 7.2 3.8 4 7.2 4c2 0 3.6 1.1 4.8 2.8C13.2 5.1 14.8 4 16.8 4c3.4 0 5.6 3.2 4.4 6.6C19.5 15.4 12 20 12 20z"))
    }
    val bell by lazy { make("bell", 1.9f, s("M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"), s("M10.3 21a1.94 1.94 0 0 0 3.4 0")) }
    val calendar by lazy {
        make("calendar", 1.9f, s("M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"), s("M16 2v4M8 2v4M3 10h18"))
    }
    val refresh by lazy { make("refresh", 2f, s("M21 12a9 9 0 1 1-2.64-6.36L21 8"), s("M21 3v5h-5")) }
    val plus by lazy { make("plus", 2.2f, s("M12 5v14M5 12h14")) }
    val minus by lazy { make("minus", 2.2f, s("M5 12h14")) }
    val star by lazy { make("star", 0f, f("M12 2.5l2.9 6.1 6.6.8-4.9 4.6 1.3 6.6L12 17.3l-5.9 3.3 1.3-6.6-4.9-4.6 6.6-.8z")) }
    val tv by lazy { make("tv", 1.9f, s("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z"), s("M8 21h8M9 2.5l3 3 3-3")) }
    val phone by lazy {
        make("phone", 1.9f, s("M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5z"), s("M11 18.5h2"))
    }
    val list by lazy { make("list", 2f, s("M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01")) }
    val quality by lazy {
        make(
            "quality", 1.8f,
            s("M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"),
            s("M7 9v6M7 12h3.5M10.5 9v6M14 9h2.5a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5H14z"),
        )
    }
    val server by lazy { make("server", 1.9f, s("M4 4h16v6H4zM4 14h16v6H4z"), s("M7.5 7h.01M7.5 17h.01")) }
    val close by lazy { make("close", 2.2f, s("M6 6l12 12M18 6 6 18")) }
    val chevron by lazy { make("chevron", 2.2f, s("M9 6l6 6-6 6")) }
    val user by lazy { make("user", 1.9f, s("M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z"), s("M4 21a8 8 0 0 1 16 0")) }
    val info by lazy { make("info", 2f, s("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z"), s("M12 11v5M12 7.5h.01")) }
    val speed by lazy { make("speed", 2f, s("M12 14l4-4"), s("M3.5 17a9 9 0 1 1 17 0")) }
    val trash by lazy { make("trash", 1.9f, s("M4 7h16M10 11v6M14 11v6M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13M9 7V4h6v3")) }
}

@Composable
fun Icon(v: ImageVector, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Image(imageVector = v, contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = modifier.size(size))
}
