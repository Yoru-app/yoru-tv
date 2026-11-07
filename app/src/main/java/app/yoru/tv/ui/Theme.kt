package app.yoru.tv.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yoru.tv.R

/** Палитра веб-Yoru: ночь и фонарь. */
object Yc {
    val Night = Color(0xFF141020)
    val Night2 = Color(0xFF1B1630)
    val Night3 = Color(0xFF251F3D)
    val Line = Color(0xFF322A52)
    val Text = Color(0xFFECE7F7)
    val TextSoft = Color(0xFFD6CFE8)
    val Muted = Color(0xFFA59CC2)
    val Faint = Color(0xFF6F6690)
    val Lantern = Color(0xFFF2B544)
    val LanternHi = Color(0xFFFFC75E)
    val LanternInk = Color(0xFF2A1A00)
    val Rose = Color(0xFFEC7F9D)
    val Ok = Color(0xFF8FD9A8)
    val Scrim = Color(0xCC0A0812)
}

// Вариативные шрифты: вес задаётся осью wght автоматически по FontWeight
val Onest = FontFamily(
    Font(R.font.onest, FontWeight.Normal),
    Font(R.font.onest, FontWeight.Medium),
    Font(R.font.onest, FontWeight.SemiBold),
    Font(R.font.onest, FontWeight.Bold),
)
val Unbounded = FontFamily(
    Font(R.font.unbounded, FontWeight.Medium),
    Font(R.font.unbounded, FontWeight.SemiBold),
    Font(R.font.unbounded, FontWeight.Bold),
    Font(R.font.unbounded, FontWeight.ExtraBold),
)

/**
 * Размеры для «10-футового» интерфейса: экран 1080p на ТВ — это ~960×540 dp,
 * смотрят с дивана, поэтому шрифт крупнее, чем в вебе.
 */
object Ty {
    val hero = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.6).sp)
    val page = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp)
    val section = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)
    val h3 = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
    val body = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyBig = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp)
    val label = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val cardTitle = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val button = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp)
    val badge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp)
}

/**
 * «Для слабых ТВ». Обычное чтение, а не состояние Compose: при переключении экран собирается заново (см. Root).
 */
val liteMode: Boolean get() = app.yoru.tv.YoruApp.instance.prefs.s.lite

/** Переход между частями страницы: плавный, а на слабых ТВ — сразу. */
fun <T> pageSpec(ms: Int): androidx.compose.animation.core.AnimationSpec<T> =
    if (liteMode) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/** Безопасные поля телевизора (overscan). */
object Safe {
    val h = 48.dp
    val v = 27.dp
}

@Composable
fun T(
    text: String,
    style: TextStyle = Ty.body,
    color: Color = Yc.Text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    // цвет отдельным параметром: стиль не копируется на каждую перерисовку, а смена цвета не пересчитывает текст
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        color = { color },
    )
}
