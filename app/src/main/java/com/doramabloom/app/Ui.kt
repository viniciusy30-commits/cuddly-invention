package com.doramabloom.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Palette {
    /** Modo escuro ligado? (definido ao abrir cada tela, veja ThemeMode). */
    var dark = false

    private fun p(light: String, night: String): Int = Color.parseColor(if (dark) night else light)

    val bgTop: Int get() = p("#FFF8FB", "#1A1219")
    val bgBottom: Int get() = p("#FFE8F1", "#120D14")
    val pink: Int get() = Color.parseColor("#FF6B9D")
    val pinkDark: Int get() = p("#E0487F", "#FF8FB8")
    val pinkSoft: Int get() = p("#FFE4EE", "#3B2433")
    val text: Int get() = p("#6B2E48", "#F5DDE8")
    val muted: Int get() = p("#8F5F76", "#CDB3C3")
    val line: Int get() = p("#F8D9E6", "#3A2B35")

    /** Fundo dos cartões, caixas e botões claros (branco no modo claro). */
    val card: Int get() = p("#FFFFFF", "#251B24")

    /** Trilho das barras de progresso. */
    val track: Int get() = p("#F6DCE6", "#3A2B35")
}

/** Deixa os ícones da barra de status e de navegação claros ou escuros conforme o modo. */
fun android.app.Activity.applyBarStyle() {
    val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    c.isAppearanceLightStatusBars = !Palette.dark
    c.isAppearanceLightNavigationBars = !Palette.dark
    window.statusBarColor = Palette.bgTop
    window.navigationBarColor = Palette.bgBottom
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun roundRect(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeW: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.cornerRadius = radius
    d.setColor(color)
    if (strokeW > 0) d.setStroke(strokeW, stroke)
    return d
}

fun gradient(
    top: Int,
    bottom: Int,
    radius: Float = 0f,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM
): GradientDrawable {
    val d = GradientDrawable(orientation, intArrayOf(top, bottom))
    d.cornerRadius = radius
    return d
}

fun ovalGradient(c1: Int, c2: Int): GradientDrawable {
    val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
    d.shape = GradientDrawable.OVAL
    return d
}

/** LayoutParams de LinearLayout; tamanhos em px, margens em dp. */
fun Context.lin(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    return p
}

fun Context.label(s: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false, cute: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    if (cute) {
        t.typeface = Typeface.create("casual", if (bold) Typeface.BOLD else Typeface.NORMAL)
    } else if (bold) {
        t.typeface = Typeface.DEFAULT_BOLD
    }
    return t
}

/**
 * Texto de uma linha que diminui sozinho até caber, em vez de ser cortado com "...".
 * Se mesmo no tamanho mínimo não couber e wrapAtMin estiver ligado, passa para duas linhas.
 */
class FitText(ctx: Context) : TextView(ctx) {
    var minSp: Float = 9f
    var wrapAtMin: Boolean = false
    private var baseSize = 0f

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        baseSize = textSize
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) fit()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        fit()
    }

    private fun fit() {
        if (baseSize <= 0f || width <= 0) return
        val avail = width - compoundPaddingLeft - compoundPaddingRight
        val t = text?.toString() ?: return
        if (avail <= 0 || t.isEmpty()) return
        val dm = resources.displayMetrics
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, dm)
        val step = dm.density * 0.5f
        val p = TextPaint(paint)
        var size = baseSize
        p.textSize = size
        while (size > minPx && p.measureText(t) > avail) {
            size -= step
            p.textSize = size
        }
        if (size < minPx) size = minPx
        val tooWide = p.measureText(t) > avail
        val wantLines = if (wrapAtMin && tooWide) 2 else 1
        if (maxLines != wantLines) maxLines = wantLines
        if (Math.abs(size - textSize) > 0.4f) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }
}

/** Como o label(), mas o texto diminui para caber numa linha. */
fun Context.fitLabel(
    s: String, size: Float = 14f, color: Int = Palette.text,
    bold: Boolean = false, cute: Boolean = false, minSp: Float = 9f, wrapAtMin: Boolean = false
): FitText {
    val t = FitText(this)
    t.minSp = minSp
    t.wrapAtMin = wrapAtMin
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    if (cute) {
        t.typeface = Typeface.create("casual", if (bold) Typeface.BOLD else Typeface.NORMAL)
    } else if (bold) {
        t.typeface = Typeface.DEFAULT_BOLD
    }
    t.maxLines = 1
    t.ellipsize = TextUtils.TruncateAt.END
    return t
}

/** Pílula com texto e (opcionalmente) um ícone próprio à esquerda. */
fun Context.pill(s: String, bg: Int, fg: Int, size: Float = 12f, icon: String? = null): TextView {
    val t = label(s, size, fg, true)
    t.setPadding(dp(12), dp(6), dp(12), dp(6))
    t.background = roundRect(bg, dp(20).toFloat())
    t.gravity = Gravity.CENTER
    if (icon != null) {
        val px = dp(size.toInt() + 4)
        t.setCompoundDrawables(iconDrawable(icon, fg, px), null, null, null)
        t.compoundDrawablePadding = dp(6)
    }
    t.pressable()
    return t
}

fun Context.card(pad: Int = 14, radius: Int = 22, bg: Int = Palette.card): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    c.background = roundRect(bg, dp(radius).toFloat(), Palette.line, dp(1))
    c.elevation = 0f
    return c
}

/** Título de seção: ícone + texto fofo. */
fun Context.sectionTitle(text: String, icon: String, color: Int = Palette.pink): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    val bubble = FrameLayout(this)
    bubble.background = ovalGradient((color and 0x00FFFFFF) or 0x33000000, (color and 0x00FFFFFF) or 0x14000000)
    bubble.addView(IconView(this, icon, color, 16), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    r.addView(bubble, lin(dp(32), dp(32)))
    r.addView(label(text, 17f, Palette.text, true, true), lin(WRAP, WRAP, l = 10))
    return r
}

fun Context.input(hint: String, text: String = "", type: Int = InputType.TYPE_CLASS_TEXT, multi: Boolean = false): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setText(text)
    e.textSize = 15f
    e.setTextColor(Palette.text)
    e.setHintTextColor(Palette.muted)
    e.background = roundRect(Palette.card, dp(18).toFloat(), Palette.line, dp(1))
    e.setPadding(dp(16), dp(12), dp(16), dp(12))
    if (multi) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.minLines = 3
        e.gravity = Gravity.TOP or Gravity.START
    } else {
        e.setSingleLine(true)
        e.inputType = type
    }
    return e
}

/** Bolinha com a nota (0 a 10). */
fun styleBadge(t: TextView, score: Int, color: Int) {
    t.text = if (score <= 0) "-" else score.toString()
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(if (score <= 0) Color.parseColor("#D9C4CE") else color)
    t.background = d
}

fun Context.scoreBadge(sizeDp: Int, textSp: Float): TextView {
    val t = label("", textSp, Color.WHITE, true, true)
    t.gravity = Gravity.CENTER
    t.minWidth = dp(sizeDp)
    t.minHeight = dp(sizeDp)
    t.elevation = 0f
    return t
}

class Opt(val key: String, val label: String, val color: Int, val icon: String? = null, val colors: List<Int> = emptyList())

private fun restyleChip(tv: TextView, col: Int, sel: Boolean, dpPx: Int, colors: List<Int> = emptyList()) {
    val multi = colors.distinct().size > 1
    if (multi) {
        val cs = colors.distinct()
        val arr: IntArray
        val stroke: Int
        if (sel) {
            arr = IntArray(cs.size) { cs[it] }
            stroke = Color.WHITE
        } else {
            arr = IntArray(cs.size) { mixColor(cs[it], Palette.card, 0.66f) }
            stroke = cs[0]
        }
        val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (arr.size == 1) intArrayOf(arr[0], arr[0]) else arr)
        bg.cornerRadius = dpPx * 20f
        bg.setStroke(dpPx, stroke)
        tv.background = bg
        val fg = if (sel) Color.WHITE else Palette.text
        tv.setTextColor(fg)
        if (sel) tv.setShadowLayer(dpPx * 2f, 0f, dpPx.toFloat(), Color.argb(150, 0, 0, 0)) else tv.setShadowLayer(0f, 0f, 0f, 0)
        val dr = tv.compoundDrawables[0]
        if (dr is IconDrawable) dr.color = fg
    } else {
        tv.background = roundRect(if (sel) col else mixColor(col, Palette.card, 0.84f), dpPx * 20f, col, dpPx)
        // sem seleção: tom mais forte da cor (mais escuro no claro, mais claro no escuro) para ler bem
        val fg = if (sel) Color.WHITE else if (Palette.dark) mixColor(col, Color.WHITE, 0.45f) else mixColor(col, Color.BLACK, 0.30f)
        tv.setTextColor(fg)
        if (sel) tv.setShadowLayer(dpPx * 2f, 0f, dpPx.toFloat(), Color.argb(120, 0, 0, 0)) else tv.setShadowLayer(0f, 0f, 0f, 0)
        val dr = tv.compoundDrawables[0]
        if (dr is IconDrawable) dr.color = fg
    }
    if (sel && tv.getTag(TAG_SEL) != true) tv.pop()
    tv.setTag(TAG_SEL, sel)
}

/** Faixa de chips com seleção única. */
fun Context.chipScroller(options: List<Opt>, initial: String, onSelect: (String) -> Unit): HorizontalScrollView {
    val sv = HorizontalScrollView(this)
    sv.isHorizontalScrollBarEnabled = false
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(0, dp(2), 0, dp(2))
    sv.addView(row)
    val views = ArrayList<TextView>()
    var current = initial
    val one = dp(1)

    fun restyle() {
        for (i in options.indices) {
            restyleChip(views[i], options[i].color, options[i].key == current, one, options[i].colors)
        }
    }

    val setter: (String) -> Unit = { k ->
        current = k
        restyle()
    }
    sv.setTag(TAG_SELECT, setter)

    for (i in options.indices) {
        val o = options[i]
        val tv = pill(o.label, Palette.card, o.color, 13f, o.icon)
        tv.setOnClickListener {
            if (current != o.key) {
                current = o.key
                restyle()
                onSelect(current)
            } else {
                tv.pop(1.2f)
            }
        }
        views.add(tv)
        row.addView(tv, lin(WRAP, WRAP, r = 8))
    }
    restyle()
    return sv
}

/** Faixa de chips com seleção múltipla. */
fun Context.multiChips(options: List<Opt>, initial: Set<String>, onChange: (Set<String>) -> Unit): HorizontalScrollView {
    val sv = HorizontalScrollView(this)
    sv.isHorizontalScrollBarEnabled = false
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(0, dp(2), 0, dp(2))
    sv.addView(row)
    val views = ArrayList<TextView>()
    val chosen = HashSet<String>(initial)
    val one = dp(1)

    fun restyle() {
        for (i in options.indices) {
            restyleChip(views[i], options[i].color, chosen.contains(options[i].key), one, options[i].colors)
        }
    }

    for (i in options.indices) {
        val o = options[i]
        val tv = pill(o.label, Palette.card, o.color, 13f, o.icon)
        tv.setOnClickListener {
            if (chosen.contains(o.key)) chosen.remove(o.key) else chosen.add(o.key)
            restyle()
            onChange(HashSet<String>(chosen))
        }
        views.add(tv)
        row.addView(tv, lin(WRAP, WRAP, r = 8))
    }
    restyle()
    return sv
}

/** Chips que quebram de linha (nada fica cortado na borda), seleção única. */
fun Context.chipFlow(options: List<Opt>, initial: String, onSelect: (String) -> Unit): FlowLayout {
    val fl = FlowLayout(this)
    fl.hGap = dp(8)
    fl.vGap = dp(8)
    fl.setPadding(0, dp(2), 0, dp(2))
    val views = ArrayList<TextView>()
    var current = initial
    val one = dp(1)

    fun restyle() {
        for (i in options.indices) {
            restyleChip(views[i], options[i].color, options[i].key == current, one, options[i].colors)
        }
    }

    val setter: (String) -> Unit = { k ->
        current = k
        restyle()
    }
    fl.setTag(TAG_SELECT, setter)

    for (i in options.indices) {
        val o = options[i]
        val tv = pill(o.label, Palette.card, o.color, 13f, o.icon)
        tv.setOnClickListener {
            if (current != o.key) {
                current = o.key
                restyle()
                onSelect(current)
            } else {
                tv.pop(1.2f)
            }
        }
        views.add(tv)
        fl.addView(tv)
    }
    restyle()
    return fl
}

/** Chips que quebram de linha, seleção múltipla. */
fun Context.multiFlow(options: List<Opt>, initial: Set<String>, max: Int = 0, onChange: (Set<String>) -> Unit): FlowLayout {
    val fl = FlowLayout(this)
    fl.hGap = dp(8)
    fl.vGap = dp(8)
    fl.setPadding(0, dp(2), 0, dp(2))
    val views = ArrayList<TextView>()
    val chosen = HashSet<String>(initial)
    val one = dp(1)

    fun restyle() {
        for (i in options.indices) {
            restyleChip(views[i], options[i].color, chosen.contains(options[i].key), one, options[i].colors)
        }
    }

    for (i in options.indices) {
        val o = options[i]
        val tv = pill(o.label, Palette.card, o.color, 13f, o.icon)
        tv.setOnClickListener {
            if (chosen.contains(o.key)) {
                chosen.remove(o.key)
            } else {
                if (max > 0 && chosen.size >= max) {
                    tv.pop(1.2f)
                    return@setOnClickListener
                }
                chosen.add(o.key)
            }
            restyle()
            onChange(HashSet<String>(chosen))
        }
        views.add(tv)
        fl.addView(tv)
    }
    restyle()
    return fl
}

/** Texto com ícone à esquerda; os dois ficam centralizados juntos (o ícone não gruda na borda). */
class CenterPill(ctx: Context) : TextView(ctx) {
    override fun onDraw(canvas: Canvas) {
        val dr = compoundDrawables[0]
        val lay = layout
        if (dr != null && lay != null && lay.lineCount > 0) {
            var tw = 0f
            for (i in 0 until lay.lineCount) tw = maxOf(tw, lay.getLineWidth(i))
            val content = dr.bounds.width() + compoundDrawablePadding + tw
            val avail = (width - paddingLeft - paddingRight).toFloat()
            canvas.translate(maxOf(0f, (avail - content) / 2f), 0f)
        }
        super.onDraw(canvas)
    }
}

/** Botão largo (ocupa a linha toda) com ícone + texto no meio. */
fun Context.bigPill(s: String, bg: Int, fg: Int, size: Float = 14f, icon: String? = null): TextView {
    val t = CenterPill(this)
    t.text = s
    t.textSize = size
    t.setTextColor(fg)
    t.typeface = Typeface.DEFAULT_BOLD
    t.setPadding(dp(18), dp(13), dp(18), dp(13))
    t.background = roundRect(bg, dp(24).toFloat())
    t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    if (icon != null) {
        val px = dp(size.toInt() + 4)
        t.setCompoundDrawables(iconDrawable(icon, fg, px), null, null, null)
        t.compoundDrawablePadding = dp(8)
    }
    t.pressable()
    return t
}

/** Barrinha de progresso arredondada, com animação suave. */
class SoftBar(ctx: Context) : View(ctx) {
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var barColor: Int = Palette.pink
        set(v) {
            field = v
            invalidate()
        }
    var trackColor: Int = Palette.track
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    /** Vai até o valor com uma animação; pode começar de outro ponto. */
    fun animateTo(target: Float, from: Float = progress, delay: Long = 0L, dur: Long = 600L) {
        anim?.cancel()
        val t = target.coerceIn(0f, 1f)
        progress = from
        val a = ValueAnimator.ofFloat(from.coerceIn(0f, 1f), t)
        a.duration = dur
        a.startDelay = delay
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { progress = it.animatedValue as Float }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        p.color = trackColor
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, r, r, p)
        if (progress > 0f) {
            p.color = barColor
            rect.set(0f, 0f, maxOf(h, width * progress), h)
            c.drawRoundRect(rect, r, r, p)
        }
    }
}

/** Cache simples de capas. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(path: String, reqW: Int): Bitmap? {
        val key = "$path@$reqW"
        val hit = cache.get(key)
        if (hit != null) return hit
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= reqW) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b = BitmapFactory.decodeFile(path, o2)
            if (b != null) cache.put(key, b)
            b
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
    }
}

/**
 * Capa arredondada: foto ou, se não houver, um fundo fofo com o símbolo do gênero.
 * Com fit = true a imagem aparece INTEIRA (sem cortar), centralizada sobre um fundo pastel
 * da cor do gênero, com uma moldurinha branca e sombra suave (como uma foto/polaroid).
 */
class CoverView(ctx: Context, radiusDp: Int = 16, private val fit: Boolean = false) : FrameLayout(ctx) {
    private class Placeholder(ctx: Context) : View(ctx) {
        val d = IconDrawable("heart", Color.parseColor("#E6FFFFFF"))
        val blossom = IconDrawable("blossom", Color.parseColor("#55FFFFFF"))

        override fun onDraw(c: Canvas) {
            val s = (minOf(width, height) * 0.42f).toInt()
            val l = (width - s) / 2
            val t = (height - s) / 2
            d.setBounds(l, t, l + s, t + s)
            d.draw(c)
            val bs = (minOf(width, height) * 0.30f).toInt()
            blossom.setBounds(width - bs - bs / 4, bs / 5, width - bs / 4, bs / 5 + bs)
            blossom.draw(c)
        }
    }

    private val img = ImageView(ctx)
    private val ph = Placeholder(ctx)
    private var bw = 0
    private var bh = 0
    private val border = ctx.dp(3)

    // decoração do fundo (só no modo fit): bolhas, símbolos do gênero e "cartas" atrás da capa
    private var deco: Genre? = null
    private val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decoIcon = IconDrawable("heart", Color.WHITE)
    private val decoRect = RectF()

    private fun a(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun drawIcon(c: Canvas, name: String, color: Int, cx: Float, cy: Float, size: Float, rot: Float) {
        decoIcon.name = name
        decoIcon.color = color
        val h = (size / 2f).toInt()
        decoIcon.setBounds(-h, -h, h, h)
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        decoIcon.draw(c)
        c.restore()
    }

    /** Onde começa (da esquerda) a capa; serve para saber se sobra espaço nas laterais. */
    fun posterLeft(): Int = if (fit && img.visibility == View.VISIBLE && img.width > 0) img.left else Int.MAX_VALUE

    init {
        if (fit) {
            setWillNotDraw(false)
            img.scaleType = ImageView.ScaleType.FIT_XY
            img.setPadding(border, border, border, border)
            val pr = ctx.dp(20).toFloat()
            img.background = roundRect(Color.WHITE, pr)
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, pr)
                }
            }
            img.clipToOutline = true
            img.elevation = ctx.dp(6).toFloat()
            addView(img, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        } else {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addView(ph, FrameLayout.LayoutParams(MATCH, MATCH))
        val r = ctx.dp(radiusDp).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    private val wave = android.graphics.Path()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val g = deco ?: return
        if (!fit || width == 0 || height == 0) return
        val u = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val p = decoPaint
        p.style = Paint.Style.FILL
        val soft = mixColor(g.primary, Palette.card, 0.55f)
        val paper = mixColor(g.primary, Palette.card, 0.82f)

        // luzes suaves na cor do gênero (sem cinza)
        p.color = a(g.primary, 44)
        c.drawCircle(w * 0.96f, h * 0.04f, 100f * u, p)
        p.color = a(g.primary, 30)
        c.drawCircle(w * 0.02f, h * 0.98f, 120f * u, p)

        // marcas d'água grandes do símbolo do gênero
        drawIcon(c, g.icon, a(g.primary, 46), w * 0.84f, h * 0.24f, 130f * u, 14f)
        drawIcon(c, g.icon, a(g.primary, 36), w * 0.12f, h * 0.76f, 92f * u, -16f)

        // símbolos pequenos espalhados (cores cheias, nítidas)
        val set = (g.petals + listOf("sparkle", "heart")).distinct()
        val pos = arrayOf(
            floatArrayOf(0.09f, 0.09f, 20f, -12f), floatArrayOf(0.93f, 0.50f, 18f, 10f),
            floatArrayOf(0.05f, 0.47f, 15f, 18f), floatArrayOf(0.95f, 0.88f, 20f, -8f),
            floatArrayOf(0.52f, 0.05f, 14f, 0f), floatArrayOf(0.30f, 0.94f, 17f, 12f),
            floatArrayOf(0.72f, 0.95f, 14f, -10f), floatArrayOf(0.20f, 0.30f, 12f, 8f),
            floatArrayOf(0.88f, 0.30f, 13f, -14f)
        )
        for (i in pos.indices) {
            val q = pos[i]
            val col = if (i % 2 == 0) soft else Color.WHITE
            drawIcon(c, set[i % set.size], col, w * q[0], h * q[1], q[2] * u, q[3])
        }

        // duas "folhas" inclinadas atrás da capa (cores sólidas, bordas nítidas)
        if (img.visibility == View.VISIBLE && img.width > 0) {
            val cx = (img.left + img.right) / 2f
            val cy = (img.top + img.bottom) / 2f
            val grow = 3f * u
            decoRect.set(img.left - grow, img.top - grow, img.right + grow, img.bottom + grow)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1.5f * u
            stroke.color = mixColor(g.primary, Palette.card, 0.35f)

            c.save()
            c.rotate(-5.5f, cx, cy)
            p.color = soft
            c.drawRoundRect(decoRect, 22f * u, 22f * u, p)
            c.drawRoundRect(decoRect, 22f * u, 22f * u, stroke)
            c.restore()

            c.save()
            c.rotate(4.5f, cx, cy)
            p.color = paper
            c.drawRoundRect(decoRect, 22f * u, 22f * u, p)
            c.drawRoundRect(decoRect, 22f * u, 22f * u, stroke)
            c.restore()
        }

        // borda de baixo ondulada, na cor do cartão (transição fofa para a parte de baixo)
        val amp = 7f * u
        val n = 7
        val seg = w / n
        wave.reset()
        wave.moveTo(0f, h + 1f)
        wave.lineTo(0f, h - amp)
        for (i in 0 until n) {
            val x0 = i * seg
            val up = if (i % 2 == 0) -amp else amp
            wave.quadTo(x0 + seg / 2f, h - amp + up * 1.6f, x0 + seg, h - amp)
        }
        wave.lineTo(w, h + 1f)
        wave.close()
        p.color = Palette.card
        c.drawPath(wave, p)
    }

    /** Detalhes por cima da capa: uma fitinha adesiva no topo. */
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val g = deco ?: return
        if (!fit || img.visibility != View.VISIBLE || img.width <= 0) return
        val u = resources.displayMetrics.density
        val p = decoPaint
        val cx = (img.left + img.right) / 2f
        val cy = img.top + 2f * u
        decoRect.set(cx - 30f * u, cy - 10f * u, cx + 30f * u, cy + 10f * u)
        c.save()
        c.rotate(-6f, cx, cy)
        p.style = Paint.Style.FILL
        p.color = mixColor(g.primary, Color.WHITE, 0.45f)
        c.drawRoundRect(decoRect, 4f * u, 4f * u, p)
        p.color = a(Color.WHITE, 150)
        var x = decoRect.left + 6f * u
        while (x < decoRect.right - 6f * u) {
            c.drawRect(x, decoRect.top + 3f * u, x + 3f * u, decoRect.bottom - 3f * u, p)
            x += 9f * u
        }
        c.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fit && bw > 0 && bh > 0 && img.visibility == View.VISIBLE &&
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY &&
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        ) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val pad = dp(14)
            val availW = w - 2 * pad - 2 * border
            val availH = h - 2 * pad - 2 * border
            if (availW > 0 && availH > 0) {
                val sc = minOf(availW.toFloat() / bw, availH.toFloat() / bh)
                val lp = img.layoutParams as FrameLayout.LayoutParams
                lp.width = maxOf(1, (bw * sc).toInt()) + 2 * border
                lp.height = maxOf(1, (bh * sc).toInt()) + 2 * border
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(path: String, genreKey: String, reqW: Int) {
        val g = Genres.byKey(genreKey)
        val bmp = if (path.isNotEmpty()) Covers.load(path, reqW) else null
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.visibility = View.VISIBLE
            ph.visibility = View.GONE
            if (fit) {
                bw = bmp.width
                bh = bmp.height
                deco = g
                background = gradient(g.soft, mixColor(g.primary, Palette.card, 0.55f))
                requestLayout()
                invalidate()
            } else {
                setBackgroundColor(g.soft)
            }
        } else {
            img.visibility = View.GONE
            bw = 0
            bh = 0
            if (fit) deco = g
            ph.visibility = View.VISIBLE
            ph.d.name = g.icon
            ph.invalidate()
            background = gradient(g.soft, g.primary, 0f, GradientDrawable.Orientation.TL_BR)
        }
    }

    fun bind(d: Drama, reqW: Int) {
        bind(d.cover, d.genre, reqW)
    }
}

/** Fundo enfeitado e NÍTIDO do topo da tela do dorama: formas e símbolos em cores sólidas (sem imagem borrada). */
class HeroDecor(ctx: Context, private val g: Genre) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ic = IconDrawable("heart", Color.WHITE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // não influencia a altura do cartão: só acompanha o tamanho que ele ganhar
        val w = getDefaultSize(0, widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    private fun icon(c: Canvas, name: String, color: Int, cx: Float, cy: Float, size: Float, rot: Float) {
        ic.name = name
        ic.color = color
        val h = (size / 2f).toInt()
        ic.setBounds(-h, -h, h, h)
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        ic.draw(c)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = resources.displayMetrics.density
        val lite = mixColor(g.primary, Color.WHITE, 0.14f)
        val lite2 = mixColor(g.primary, Color.WHITE, 0.24f)
        val mark = mixColor(g.primary, Color.WHITE, 0.20f)
        val soft = mixColor(g.primary, Color.WHITE, 0.55f)

        // formas grandes e lisinhas
        p.style = Paint.Style.FILL
        p.color = lite
        c.drawCircle(w * 0.02f, h * 0.02f, 120f * u, p)
        c.drawCircle(w * 1.0f, h * 0.62f, 150f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * u
        p.color = lite2
        c.drawCircle(w * 0.98f, h * 0.06f, 70f * u, p)
        c.drawCircle(w * 0.0f, h * 0.55f, 90f * u, p)
        c.drawCircle(w * 0.5f, h * 1.02f, 110f * u, p)

        // marcas d'água do símbolo do gênero
        icon(c, g.icon, mark, w * 0.88f, h * 0.60f, 150f * u, 12f)
        icon(c, g.icon, mark, w * 0.10f, h * 0.20f, 96f * u, -14f)

        // símbolos pequenos: brilhos brancos e os símbolos do gênero
        val set = (g.petals + listOf("heart")).distinct()
        val pos = arrayOf(
            floatArrayOf(0.12f, 0.06f, 20f, -10f), floatArrayOf(0.90f, 0.08f, 24f, 8f),
            floatArrayOf(0.06f, 0.36f, 16f, 14f), floatArrayOf(0.95f, 0.38f, 15f, -12f),
            floatArrayOf(0.07f, 0.70f, 18f, 10f), floatArrayOf(0.93f, 0.80f, 20f, -8f),
            floatArrayOf(0.20f, 0.95f, 15f, 12f), floatArrayOf(0.80f, 0.97f, 17f, -14f),
            floatArrayOf(0.50f, 0.03f, 13f, 0f)
        )
        for (i in pos.indices) {
            val q = pos[i]
            if (i % 2 == 0) icon(c, "sparkle", Color.WHITE, w * q[0], h * q[1], q[2] * u, q[3])
            else icon(c, set[i % set.size], soft, w * q[0], h * q[1], q[2] * u, q[3])
        }
    }
}

/** Fitinha adesiva listrada (washi tape). */
class TapeView(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val u = resources.displayMetrics.density
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(r, 4f * u, 4f * u, p)
        p.color = Color.argb(150, 255, 255, 255)
        var x = 6f * u
        while (x < width - 6f * u) {
            c.drawRect(x, 3f * u, x + 3f * u, height - 3f * u, p)
            x += 9f * u
        }
    }
}

/**
 * Moldura que acompanha a altura do vizinho (MATCH_PARENT dentro de um pai WRAP_CONTENT)
 * sem que a imagem de dentro empurre o tamanho do cartão.
 */
class TallFrame(ctx: Context, private val minHeightPx: Int) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minHeightPx, MeasureSpec.EXACTLY))
        }
    }
}

/** Botão redondo com ícone (usado em passos de contagem, + e -). */
fun Context.roundBtn(icon: String, color: Int, filled: Boolean, sizeDp: Int = 16, onClick: () -> Unit): FrameLayout {
    val f = FrameLayout(this)
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(if (filled) color else Palette.card)
    d.setStroke(dp(2), color)
    f.background = d
    f.addView(
        IconView(this, icon, if (filled) Color.WHITE else color, sizeDp),
        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
    )
    f.setOnClickListener { onClick() }
    f.pressable(0.86f)
    return f
}


/** Troca a cor de um botão criado com roundBtn (para seguir o tema do gênero). */
fun FrameLayout.recolorRoundBtn(color: Int, filled: Boolean) {
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(if (filled) color else Palette.card)
    d.setStroke(dp(2), color)
    background = d
    (getChildAt(0) as? IconView)?.tint = if (filled) Color.WHITE else color
}

// ================================================================ ANIMAÇÕES

const val TAG_SEL = 0x7f0a0001
const val TAG_SELECT = 0x7f0a0002

/** Seleciona um chip de fora (usado quando o status muda sozinho). */
@Suppress("UNCHECKED_CAST")
fun View.selectChip(key: String) {
    (getTag(TAG_SELECT) as? ((String) -> Unit))?.invoke(key)
}

/** Encolhe um pouquinho ao tocar e volta com mola ao soltar. Não atrapalha o clique. */
fun View.pressable(scale: Float = 0.95f) {
    setOnTouchListener { v, e ->
        if (!v.isClickable) return@setOnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
        }
        false
    }
}

/** Pulinho de alegria (usado ao selecionar algo). */
fun View.pop(peak: Float = 1.14f) {
    animate().cancel()
    scaleX = 0.9f
    scaleY = 0.9f
    animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(peak * 3f)).start()
}

/** Entra deslizando de baixo com fade. */
fun View.riseIn(delay: Long = 0L, distDp: Int = 22, dur: Long = 420L) {
    animate().cancel()
    alpha = 0f
    translationY = dp(distDp).toFloat()
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur)
        .setInterpolator(DecelerateInterpolator(1.8f)).start()
}

/** Aparece com fade e leve escala. */
fun View.fadeScaleIn(delay: Long = 0L, dur: Long = 380L) {
    animate().cancel()
    alpha = 0f
    scaleX = 0.92f
    scaleY = 0.92f
    animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(dur)
        .setInterpolator(DecelerateInterpolator(1.6f)).start()
}

/** Entrada em cascata dos filhos de um LinearLayout. */
fun LinearLayout.staggerIn(step: Long = 55L, maxItems: Int = 10) {
    for (i in 0 until childCount) {
        getChildAt(i).riseIn(minOf(i, maxItems) * step)
    }
}

/** Troca de tela: entra deslizando de lado. dir = 1 (veio da direita) ou -1. */
fun View.slideIn(dir: Int) {
    animate().cancel()
    alpha = 0f
    translationX = dir * dp(36).toFloat()
    animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.8f)).start()
}

/** Conta de um número até outro. */
fun TextView.countTo(target: Int, from: Int = 0, delay: Long = 0L, dur: Long = 700L, fmt: (Int) -> String = { it.toString() }) {
    val a = ValueAnimator.ofInt(from, target)
    a.duration = dur
    a.startDelay = delay
    a.interpolator = DecelerateInterpolator(1.5f)
    text = fmt(from)
    a.addUpdateListener { text = fmt(it.animatedValue as Int) }
    a.start()
}

/** Muda a cor de um fundo/ícone com transição suave. */
fun animateColor(from: Int, to: Int, dur: Long = 260L, onUpdate: (Int) -> Unit) {
    val a = ValueAnimator.ofObject(ArgbEvaluator(), from, to)
    a.duration = dur
    a.addUpdateListener { onUpdate(it.animatedValue as Int) }
    a.start()
}

/** Abre uma área com altura animada. */
fun View.expand(dur: Long = 320L) {
    if (visibility == View.VISIBLE && layoutParams.height == WRAP && alpha == 1f) return
    animate().cancel()
    measure(
        View.MeasureSpec.makeMeasureSpec((parent as View).width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val target = measuredHeight
    layoutParams.height = 0
    alpha = 0f
    visibility = View.VISIBLE
    val a = ValueAnimator.ofInt(0, target)
    a.duration = dur
    a.interpolator = DecelerateInterpolator(1.6f)
    a.addUpdateListener {
        layoutParams.height = it.animatedValue as Int
        alpha = it.animatedFraction
        requestLayout()
    }
    a.addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            layoutParams.height = WRAP
            alpha = 1f
            requestLayout()
        }
    })
    a.start()
}

/** Fecha uma área com altura animada. */
fun View.collapse(dur: Long = 260L) {
    if (visibility == View.GONE) return
    val start = height
    val a = ValueAnimator.ofInt(start, 0)
    a.duration = dur
    a.interpolator = AccelerateInterpolator(1.2f)
    a.addUpdateListener {
        layoutParams.height = it.animatedValue as Int
        alpha = 1f - it.animatedFraction
        requestLayout()
    }
    a.addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            visibility = View.GONE
            layoutParams.height = WRAP
            alpha = 1f
        }
    })
    a.start()
}

/** Aviso fofo que desce do topo e some sozinho. */
fun Activity.softToast(msg: String, color: Int = Palette.pink, icon: String = "sparkle") {
    val host = findViewById<ViewGroup>(android.R.id.content) ?: return
    val t = pill(msg, color, Color.WHITE, 14f, icon)
    t.setPadding(dp(18), dp(12), dp(20), dp(12))
    t.elevation = dp(12).toFloat()
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
    lp.topMargin = dp(36)
    host.addView(t, lp)
    t.alpha = 0f
    t.translationY = -dp(60).toFloat()
    t.scaleX = 0.8f
    t.scaleY = 0.8f
    t.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
        .setInterpolator(OvershootInterpolator(1.6f)).start()
    t.postDelayed({
        t.animate().alpha(0f).translationY(-dp(40).toFloat()).setDuration(300).withEndAction { host.removeView(t) }.start()
    }, 2600)
}

/** Cor de status/gênero/país para os "efeitos" dos filtros. */
class Atmos(val icons: List<String>, val tints: List<Int>)

object Atmosphere {
    private fun c(s: String) = Color.parseColor(s)

    fun country(name: String): Pair<String, Int> = when (name) {
        "Coreia do Sul" -> Pair("blossom", c("#FF8FB7"))
        "Japão" -> Pair("blossom", c("#E8505B"))
        "China" -> Pair("star", c("#E5A93B"))
        "Tailândia" -> Pair("leaf", c("#4FA8C7"))
        "Taiwan" -> Pair("sparkle", c("#5B7FD9"))
        else -> Pair("sparkle", c("#B98AA0"))
    }

    /** Símbolos de cada status (nada de repetir os dos gêneros quando possível). */
    private fun statusIcons(key: String): List<String> = when (key) {
        "assistindo" -> listOf("play", "tv")
        "quero" -> listOf("bookmark", "calendar")
        "concluido" -> listOf("check", "replay")
        "pausado" -> listOf("pause", "clock")
        else -> listOf("close", "minus")
    }

    fun status(key: String): Atmos = when (key) {
        "fav" -> Atmos(listOf("heart", "star"), listOf(Palette.pink, c("#FFB84D")))
        else -> {
            val s = Statuses.byKey(key)
            Atmos(statusIcons(key), listOf(s.color, mixColor(s.color, Color.WHITE, 0.3f)))
        }
    }

    /** Monta a lista sem símbolos repetidos (o primeiro vence). */
    private class Mix {
        val icons = ArrayList<String>()
        val tints = ArrayList<Int>()
        fun add(icon: String, tint: Int) {
            if (icons.contains(icon)) return
            icons.add(icon)
            tints.add(tint)
        }
        fun addGenre(g: Genre) {
            val gt = genreTints(g)
            for (i in g.petals.indices) add(g.petals[i], gt[i % gt.size])
        }
        fun done(): Atmos {
            if (icons.isEmpty()) {
                val pk = Palette.pink
                return Atmos(
                    listOf("petal", "blossom", "sparkle"),
                    listOf(pk, mixColor(pk, Color.WHITE, 0.25f), mixColor(pk, Color.parseColor("#FFB84D"), 0.5f))
                )
            }
            return Atmos(icons, tints)
        }
    }

    /** Mistura o que está ativo: gênero manda; status e país entram como acento. */
    fun of(genre: String, status: String, country: String): Atmos {
        val m = Mix()
        if (genre != "all") m.addGenre(Genres.byKey(genre))
        if (status != "all") {
            val a = status(status)
            for (i in a.icons.indices) m.add(a.icons[i], a.tints[i % a.tints.size])
        }
        if (country != "all") {
            val (ic, col) = country(country)
            m.add(ic, col)
            m.add("petal", col)
        }
        return m.done()
    }

    /** Símbolos misturados das categorias (gênero principal e secundários) de vários doramas. "Outros gêneros" não têm símbolos. */
    fun ofCategories(ds: List<Drama>): Atmos {
        val m = Mix()
        for (d in ds) m.addGenre(Genres.byKey(d.genre))
        for (d in ds) for (tk in d.tags) {
            if (tk != d.genre && Genres.exists(tk)) m.addGenre(Genres.byKey(tk))
        }
        return m.done()
    }

    /** Tela de um dorama: símbolos do gênero + um acento do status. */
    fun ofDrama(d: Drama): Atmos {
        val g = Genres.byKey(d.genre)
        val s = Statuses.byKey(d.status)
        val m = Mix()
        m.addGenre(g)
        m.add(statusIcons(d.status)[0], s.color)
        if (d.favorite) m.add("heart", g.primary)
        return m.done()
    }
}

/**
 * Etiqueta de gênero com as cores vivas dele (igual à tela do dorama): fundo na cor (ou degradê,
 * se tiver várias), texto branco com sombrinha e borda branca fininha.
 */
fun Context.vividPill(label: String, icon: String?, colors: List<Int>, size: Float = 12f): TextView {
    val cs = colors.distinct().ifEmpty { listOf(Palette.pink) }
    val t = pill(label, cs[0], Color.WHITE, size, icon)
    if (cs.size > 1) {
        val bg = multiColorBg(cs, true, dp(20).toFloat(), 0) as GradientDrawable
        bg.setStroke(dp(1), Color.WHITE)
        t.background = bg
    } else {
        t.background = roundRect(cs[0], dp(20).toFloat(), Color.WHITE, dp(1))
    }
    t.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(130, 0, 0, 0))
    return t
}

/** Etiqueta de "outro gênero": cores vivas, com degradê quando tem mais de uma cor. */
fun Context.otherPill(g: OtherGenre, size: Float = 12f): TextView = vividPill(g.label, g.icon, g.colors, size)

/**
 * Fundo em degradê para gêneros com 2 ou mais cores (ex.: arco-íris do LGBTQ+).
 * vivid = true usa as cores puras; false usa tons suaves. strokePx > 0 põe uma borda fininha.
 */
fun multiColorBg(colors: List<Int>, vivid: Boolean, radiusPx: Float, strokePx: Int = 0): android.graphics.drawable.Drawable {
    val cs = if (colors.isEmpty()) listOf(Color.GRAY) else colors.take(16)
    val arr = IntArray(maxOf(2, cs.size)) { i ->
        val c = cs[minOf(i, cs.size - 1)]
        if (vivid) c else mixColor(c, Color.WHITE, 0.80f)
    }
    val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, arr)
    bg.cornerRadius = radiusPx
    if (strokePx > 0) bg.setStroke(strokePx, Color.WHITE)
    return bg
}
