package com.doramabloom.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged

/**
 * Um lugar onde assistir: nome, cor de fundo e cor do símbolo.
 * Os de fábrica têm o símbolo desenhado no próprio app; os personalizados mostram só a inicial.
 */
data class Streaming(
    val key: String,
    val label: String,
    val bg: Int,
    val fg: Int,
    /** Letras do símbolo quando não há desenho próprio. */
    val glyph: String,
    val custom: Boolean = false
)

object Streamings {
    private fun c(s: String): Int = Color.parseColor(s)

    val all: List<Streaming> = listOf(
        Streaming("netflix", "Netflix", c("#E50914"), Color.WHITE, "N"),
        Streaming("viki", "Viki", c("#0AA6E8"), Color.WHITE, "v"),
        Streaming("disney", "Disney+", c("#113CCF"), Color.WHITE, "D+"),
        Streaming("prime", "Prime Video", c("#00A8E1"), Color.WHITE, "p"),
        Streaming("hbomax", "HBO Max", c("#5822B4"), Color.WHITE, "max"),
        Streaming("apple", "Apple TV+", c("#000000"), Color.WHITE, "tv"),
        Streaming("paramount", "Paramount+", c("#0064FF"), Color.WHITE, "P+"),
        Streaming("globoplay", "Globoplay", c("#FA3E2C"), Color.WHITE, "g"),
        Streaming("youtube", "YouTube", c("#FF0000"), Color.WHITE, "▶"),
        Streaming("crunchyroll", "Crunchyroll", c("#F47521"), Color.WHITE, "C"),
        Streaming("iqiyi", "iQIYI", c("#00CC36"), Color.WHITE, "iQ"),
        Streaming("wetv", "WeTV", c("#FF6900"), Color.WHITE, "W")
    )

    /** Cor do texto/símbolo que fica legível sobre [bg]. */
    fun contrast(bg: Int): Int {
        val lum = (0.299f * Color.red(bg) + 0.587f * Color.green(bg) + 0.114f * Color.blue(bg)) / 255f
        return if (lum > 0.62f) Color.parseColor("#222222") else Color.WHITE
    }

    fun makeCustom(name: String, color: Int): Streaming {
        val n = clean(name)
        val g = n.take(1).uppercase()
        return Streaming("c_" + n.lowercase(), n, color, contrast(color), g, true)
    }

    private fun clean(s: String): String = s.replace(",", " ").replace("|", " ").trim().take(20)

    /**
     * O texto guardado no dorama: nomes separados por vírgula; os personalizados levam a cor
     * ("Telegram|#FF8800"). Textos antigos (ex.: "Netflix, Viki") continuam funcionando.
     */
    fun parse(raw: String): List<Streaming> {
        val out = ArrayList<Streaming>()
        for (tk in raw.split(",")) {
            val t = tk.trim()
            if (t.isEmpty()) continue
            val s: Streaming = if (t.contains("|")) {
                val name = t.substringBefore("|").trim()
                val col = try {
                    Color.parseColor(t.substringAfter("|").trim())
                } catch (e: Exception) {
                    Palette.pink
                }
                if (name.isEmpty()) continue
                makeCustom(name, col)
            } else {
                all.firstOrNull { it.label.equals(t, true) || it.key.equals(t, true) }
                    ?: makeCustom(t, Palette.pink)
            }
            if (out.none { it.key == s.key }) out.add(s)
        }
        return out
    }

    fun encode(l: List<Streaming>): String = l.joinToString(", ") {
        if (it.custom) it.label + "|" + String.format("#%06X", it.bg and 0xFFFFFF) else it.label
    }

    /** Só os nomes, para busca e para textos simples. */
    fun names(raw: String): String = parse(raw).joinToString(", ") { it.label }
}

/** Símbolo redondo da marca: círculo na cor do símbolo e o desenho na cor da marca. */
class StreamMarkDrawable(private val s: Streaming) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        p.style = Paint.Style.FILL
        p.color = s.fg
        c.drawCircle(cx, cy, r, p)
        p.color = s.bg
        when (s.key) {
            "netflix" -> {
                val a = r * 0.46f
                val bw = r * 0.26f
                val top = cy - r * 0.56f
                val bot = cy + r * 0.56f
                c.drawRect(cx - a, top, cx - a + bw, bot, p)
                c.drawRect(cx + a - bw, top, cx + a, bot, p)
                val path = Path()
                path.moveTo(cx - a, top)
                path.lineTo(cx - a + bw, top)
                path.lineTo(cx + a, bot)
                path.lineTo(cx + a - bw, bot)
                path.close()
                c.drawPath(path, p)
            }
            "youtube" -> {
                val path = Path()
                path.moveTo(cx - r * 0.28f, cy - r * 0.45f)
                path.lineTo(cx - r * 0.28f, cy + r * 0.45f)
                path.lineTo(cx + r * 0.50f, cy)
                path.close()
                c.drawPath(path, p)
            }
            else -> {
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = r * when (s.glyph.length) {
                    1 -> 1.15f
                    2 -> 0.92f
                    else -> 0.70f
                }
                c.drawText(s.glyph, cx, cy - (p.ascent() + p.descent()) / 2f, p)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        p.colorFilter = cf
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Etiqueta do streaming: fundo na cor da marca, símbolo à esquerda e o nome. */
fun Context.streamChip(s: Streaming, size: Float = 12f): TextView {
    val t = label(s.label, size, s.fg, true)
    val compact = size < 11f
    t.setPadding(dp(if (compact) 5 else 7), dp(if (compact) 4 else 5), dp(if (compact) 8 else 12), dp(if (compact) 4 else 5))
    t.background = roundRect(s.bg, dp(20).toFloat(), Color.WHITE, dp(1))
    t.gravity = Gravity.CENTER
    t.maxLines = 1
    val px = dp(size.toInt() + 6)
    val m = StreamMarkDrawable(s)
    m.setBounds(0, 0, px, px)
    t.setCompoundDrawables(m, null, null, null)
    t.compoundDrawablePadding = dp(if (compact) 4 else 6)
    return t
}

/** Todos os streamings do dorama, um do lado do outro (quebra de linha quando falta espaço). */
fun Context.streamRow(raw: String, size: Float = 12f, center: Boolean = false): FlowLayout {
    val f = FlowLayout(this)
    f.hGap = dp(6)
    f.vGap = dp(6)
    f.center = center
    for (s in Streamings.parse(raw)) f.addView(streamChip(s, size))
    return f
}

/** Janela para criar um streaming seu: nome e cor, com prévia ao vivo. */
fun Activity.showStreamingCreator(onDone: (Streaming) -> Unit) {
    var color = genreColorChoices[0]
    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(8), dp(20), dp(6))
    sv.addView(col)

    val prevBox = LinearLayout(this)
    prevBox.gravity = Gravity.CENTER
    col.addView(prevBox, lin(MATCH, WRAP, b = 4))

    val nameIn = input("Nome (ex.: Telegram, DVD, Site)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    nameIn.filters = arrayOf(InputFilter.LengthFilter(20))
    col.addView(label("Nome", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 10, b = 6))
    col.addView(nameIn, lin(MATCH, WRAP))
    col.addView(label("Cor", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 8))

    fun restyle() {
        prevBox.removeAllViews()
        val nm = nameIn.text.toString().trim().ifEmpty { "Seu streaming" }
        prevBox.addView(streamChip(Streamings.makeCustom(nm, color), 14f))
    }

    val picker = ColorPicker(this, color) {
        color = it
        restyle()
    }
    col.addView(picker, lin(MATCH, WRAP))
    nameIn.doAfterTextChanged { restyle() }
    restyle()

    val dlg = AlertDialog.Builder(this)
        .setTitle("Outro lugar para assistir")
        .setView(sv)
        .setPositiveButton("Adicionar", null)
        .setNegativeButton("Cancelar", null)
        .create()
    dlg.show()
    dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val nm = nameIn.text.toString().replace(",", " ").replace("|", " ").trim()
        if (nm.isEmpty()) {
            nameIn.error = "Escreva o nome"
            return@setOnClickListener
        }
        dlg.dismiss()
        onDone(Streamings.makeCustom(nm, color))
    }
}
