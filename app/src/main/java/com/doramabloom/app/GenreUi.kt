package com.doramabloom.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged

/** Ícones que você pode escolher ao criar um gênero. */
val genreIconChoices: List<String> = listOf(
    "heart", "smile", "bolt", "ghost", "sparkle", "moon", "star", "drop",
    "pagoda", "search", "school", "eye", "skull", "cross", "music", "trophy",
    "crown", "rocket", "coffee", "leaf", "blossom", "home", "book", "tv",
    // mais símbolos
    "flame", "butterfly", "wand", "gem", "castle", "planet", "ufo", "sun",
    "cloud", "balloon", "ring", "cake", "gift", "mic", "headphones", "medal",
    "ball", "shield", "dagger", "key", "lock", "bat", "tomb", "lantern",
    "fan", "heartbreak", "hourglass", "pill", "syringe",
    // ainda mais símbolos
    "scale", "flag", "replay", "globe", "chart", "person", "rainbow", "clock", "tag"
)

/** Cores prontas para escolher (rosas, laranjas, verdes, azuis, roxos e neutros, inclusive preto). */
val genreColorChoices: List<Int> = listOf(
    // rosas e vermelhos
    "#FF6B9D", "#FF8FB7", "#FF5A8A", "#E8505B", "#FF7A6B", "#D6536D", "#B03A5B", "#9E1F4D", "#8E1F3A", "#C2185B",
    // laranjas e amarelos
    "#FF7A3D", "#F29B5C", "#FFB84D", "#F2A93B", "#E0A93B", "#C9B037", "#F5D547", "#D98B5F", "#B58B6A", "#8A6D5A",
    // verdes
    "#A3D45A", "#7FD1AE", "#6FBF4A", "#5DB56E", "#7A9A5A", "#6B7A3A", "#3E8E5A", "#226B35", "#2BA6A0", "#5FB3A8",
    // azuis
    "#5FD0E8", "#3FB6C9", "#4A8FA8", "#6FA8FF", "#5B7FD9", "#3E6FD8", "#4F6D9A", "#3B5BDB", "#1F4F9C", "#24395A",
    // roxos
    "#6C63FF", "#7B6CF0", "#9B7EDE", "#8E5BD9", "#9B5DE5", "#A068E0", "#C38BD8", "#E36BC4", "#D45FA0", "#B14AED",
    // neutros
    "#C9BFC4", "#9AA0A8", "#6E6E78", "#4B4B55", "#2A2A31", "#111114"
).map { Color.parseColor(it) }

/** Cor a partir de matiz (0 a 360) e tom (0 = bem escura, 50 = viva, 100 = bem clara). */
fun tonedColor(hue: Float, tone: Int): Int {
    val t = tone.coerceIn(0, 100) / 100f
    val v: Float
    val sat: Float
    if (t <= 0.5f) {
        v = 0.28f + t / 0.5f * 0.72f
        sat = 0.75f
    } else {
        v = 1f
        sat = 0.75f - (t - 0.5f) / 0.5f * 0.5f
    }
    return Color.HSVToColor(floatArrayOf(hue, sat, v))
}

/**
 * Escolha de cor: bolinhas prontas + duas barrinhas para misturar a sua própria cor
 * (matiz e tom). Chama [onChange] a cada mudança.
 */
class ColorPicker(ctx: Context, initial: Int, private val onChange: (Int) -> Unit) : LinearLayout(ctx) {
    var color: Int = initial
        private set

    // se a cor atual não está na lista (de fábrica ou misturada), ela entra como primeira bolinha
    private val choices: List<Int> =
        if (genreColorChoices.contains(initial)) genreColorChoices else listOf(initial) + genreColorChoices
    private val cells = ArrayList<FrameLayout>()
    private val hueBar = SeekBar(ctx)
    private val toneBar = SeekBar(ctx)
    private var silent = false

    init {
        orientation = VERTICAL

        val flow = FlowLayout(ctx)
        flow.hGap = dp(8)
        flow.vGap = dp(8)
        for (i in choices.indices) {
            val cell = FrameLayout(ctx)
            cell.addView(View(ctx), FrameLayout.LayoutParams(dp(32), dp(32)))
            cell.setOnClickListener {
                pick(choices[i])
                syncBars()
            }
            cell.pressable(0.88f)
            cells.add(cell)
            flow.addView(cell)
        }
        addView(flow, ctx.lin(MATCH, WRAP))

        addView(ctx.label("Ou misture a sua cor", 12f, Palette.muted, true), ctx.lin(WRAP, WRAP, t = 14, b = 2))
        styleBar(hueBar, 359)
        styleBar(toneBar, 100)
        val rainbow = intArrayOf(0, 60, 120, 180, 240, 300, 359).map { Color.HSVToColor(floatArrayOf(it.toFloat(), 0.75f, 1f)) }
        hueBar.progressDrawable = track(rainbow.toIntArray())
        addView(hueBar, ctx.lin(MATCH, WRAP, t = 4))
        addView(toneBar, ctx.lin(MATCH, WRAP, t = 6))

        hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) fromBars()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        toneBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) fromBars()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        syncBars()
        restyle()
    }

    private fun styleBar(sb: SeekBar, max: Int) {
        sb.max = max
        sb.setPadding(dp(12), 0, dp(12), 0)
        val th = GradientDrawable()
        th.shape = GradientDrawable.OVAL
        th.setColor(Color.WHITE)
        th.setStroke(dp(3), Palette.text)
        th.setSize(dp(24), dp(24))
        sb.thumb = th
    }

    private fun track(colors: IntArray): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        d.cornerRadius = dp(6).toFloat()
        d.setSize(-1, dp(12))
        return d
    }

    private fun hueNow(): Float = hueBar.progress.toFloat()

    private fun retrackTone() {
        val h = hueNow()
        toneBar.progressDrawable = track(intArrayOf(tonedColor(h, 0), tonedColor(h, 50), tonedColor(h, 100)))
    }

    /** Posiciona as barrinhas de acordo com a cor atual. */
    private fun syncBars() {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val raw: Float = if (hsv[2] < 0.995f) {
            (hsv[2] - 0.28f) / 0.72f * 50f
        } else {
            50f + (0.75f - hsv[1]) / 0.5f * 50f
        }
        val tone = raw.toInt().coerceIn(0, 100)
        silent = true
        hueBar.progress = hsv[0].toInt().coerceIn(0, 359)
        toneBar.progress = tone
        silent = false
        retrackTone()
    }

    private fun fromBars() {
        if (silent) return
        retrackTone()
        pick(tonedColor(hueNow(), toneBar.progress))
    }

    private fun pick(c: Int) {
        color = c
        restyle()
        onChange(c)
    }

    private fun restyle() {
        for (i in cells.indices) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(choices[i])
            if (choices[i] == color) d.setStroke(dp(3), Palette.text) else d.setStroke(dp(2), Palette.card)
            cells[i].background = d
        }
    }
}

/**
 * Selo do gênero: um adesivo de bordas onduladas com o ícone do gênero no meio.
 * Com label = true aparece uma fitinha com o nome embaixo (para tamanhos grandes).
 */
class SealView(ctx: Context, private val withLabel: Boolean = false) : View(ctx) {
    private var icon = "heart"
    private var color = Palette.pink
    private var dark = Palette.pinkDark
    private var text = ""
    private val scallops = 12
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val ic = IconDrawable("heart", Color.WHITE)

    fun set(g: Genre) {
        icon = g.icon
        color = g.primary
        dark = g.deep
        text = g.label
        ic.name = icon
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(46), widthMeasureSpec),
            resolveSize(dp(46), heightMeasureSpec)
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        val big = if (withLabel) r * 0.78f else r
        val cx = w / 2f
        val cy = if (withLabel) big + r * 0.04f else h / 2f

        p.style = Paint.Style.FILL
        p.color = color
        for (i in 0 until scallops) {
            val a = 2.0 * Math.PI * i / scallops
            c.drawCircle(
                cx + (Math.cos(a) * big * 0.82).toFloat(),
                cy + (Math.sin(a) * big * 0.82).toFloat(),
                big * 0.19f, p
            )
        }
        c.drawCircle(cx, cy, big * 0.84f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, big * 0.05f)
        p.color = Color.argb(190, 255, 255, 255)
        c.drawCircle(cx, cy, big * 0.66f, p)

        val s = (big * 0.92f).toInt()
        val l = (cx - s / 2f).toInt()
        val t = (cy - s / 2f).toInt()
        ic.color = Color.WHITE
        ic.setBounds(l, t, l + s, t + s)
        ic.draw(c)

        if (withLabel && text.isNotEmpty()) {
            val rw = minOf(w, r * 1.9f)
            val rh = r * 0.38f
            val top = cy + big - r * 0.16f
            p.style = Paint.Style.FILL
            p.color = dark
            rect.set(cx - rw / 2f, top, cx + rw / 2f, top + rh)
            c.drawRoundRect(rect, rh / 2f, rh / 2f, p)
            val label = text.uppercase()
            tp.color = Color.WHITE
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.textAlign = Paint.Align.CENTER
            var ts = rh * 0.56f
            tp.textSize = ts
            while (tp.measureText(label) > rw - rh * 0.6f && ts > 6f) {
                ts -= 1f
                tp.textSize = ts
            }
            c.drawText(label, cx, top + rh / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
        }
    }
}

/** Janela para criar um gênero novo: nome, frase, cor e ícone, com prévia ao vivo. */
fun Activity.showGenreCreator(onDone: (Genre) -> Unit) = showGenreEditor(null, onDone)

/**
 * Janela para criar (existing = null) ou editar um gênero, seja um que você criou ou um de fábrica.
 * Nome, frase, cor e ícone, com prévia ao vivo.
 */
fun Activity.showGenreEditor(existing: Genre?, onDone: (Genre) -> Unit) {
    if (existing == null && Genres.custom().size >= 30) {
        softToast("Você já criou muitos gêneros!", Palette.pink, "tag")
        return
    }
    var color = existing?.base ?: genreColorChoices[0]
    var icon = existing?.icon ?: genreIconChoices[0]
    val autoTag = if (existing != null) "Seu gênero " + existing.label else ""
    val startTag = if (existing == null || existing.tagline == autoTag) "" else existing.tagline

    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(8), dp(20), dp(6))
    sv.addView(col)

    // prévia
    val prevBox = LinearLayout(this)
    prevBox.orientation = LinearLayout.HORIZONTAL
    prevBox.gravity = Gravity.CENTER_VERTICAL
    prevBox.setPadding(dp(12), dp(10), dp(14), dp(10))
    val seal = SealView(this)
    prevBox.addView(seal, lin(dp(54), dp(54), r = 12))
    val ptx = LinearLayout(this)
    ptx.orientation = LinearLayout.VERTICAL
    val prevLabel = label("Seu gênero", 18f, Palette.text, true, true)
    val prevTag = label("", 12f, Palette.muted)
    ptx.addView(prevLabel)
    ptx.addView(prevTag, lin(MATCH, WRAP, t = 2))
    prevBox.addView(ptx, lin(0, WRAP, 1f))
    col.addView(prevBox, lin(MATCH, WRAP))

    val nameIn = input("Nome do gênero (ex.: Sobrenatural)", existing?.label ?: "", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    nameIn.filters = arrayOf(InputFilter.LengthFilter(18))
    val tagIn = input("Frase fofa (opcional)", startTag)
    tagIn.filters = arrayOf(InputFilter.LengthFilter(60))
    val midIn = input("Frase para as notas 5 a 7", existing?.midLine ?: DEFAULT_MID_LINE)
    midIn.filters = arrayOf(InputFilter.LengthFilter(60))
    val lowIn = input("Frase para as notas 1 a 4", existing?.lowLine ?: DEFAULT_LOW_LINE)
    lowIn.filters = arrayOf(InputFilter.LengthFilter(60))
    col.addView(label("Nome", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 6))
    col.addView(nameIn, lin(MATCH, WRAP))
    col.addView(label("Frase das notas 8 a 10 (a do gênero)", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 12, b = 6))
    col.addView(tagIn, lin(MATCH, WRAP))
    col.addView(label("Frase das notas 5 a 7", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 12, b = 6))
    col.addView(midIn, lin(MATCH, WRAP))
    col.addView(label("Frase das notas 1 a 4", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 12, b = 6))
    col.addView(lowIn, lin(MATCH, WRAP))

    // cores
    col.addView(label("Cor", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 8))
    val iconCells = ArrayList<FrameLayout>()
    val iconViews = ArrayList<IconView>()

    fun previewGenre(): Genre {
        val nm = nameIn.text.toString().trim()
        val label = if (nm.isEmpty()) "Seu gênero" else nm
        val tag = tagIn.text.toString().trim()
        val mid = midIn.text.toString().trim()
        val low = lowIn.text.toString().trim()
        if (existing != null && !existing.custom) return Genres.editBuiltin(existing, label, icon, color, tag, low, mid)
        return Genres.makeCustom("preview", label, icon, color, tag, low, mid)
    }

    fun restyle() {
        val g = previewGenre()
        seal.set(g)
        prevLabel.text = g.label
        prevLabel.setTextColor(g.dark)
        prevTag.text = g.tagline
        prevBox.background = roundRect(g.soft, dp(20).toFloat(), mixColor(g.primary, Color.WHITE, 0.5f), dp(1))
        for (i in iconCells.indices) {
            val sel = genreIconChoices[i] == icon
            iconCells[i].background = roundRect(if (sel) color else Palette.pinkSoft, dp(16).toFloat())
            iconViews[i].tint = if (sel) Color.WHITE else color
        }
    }

    val picker = ColorPicker(this, color) {
        color = it
        restyle()
        seal.pop(1.3f)
    }
    col.addView(picker, lin(MATCH, WRAP))

    // ícones
    col.addView(label("Ícone", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 8))
    val icFlow = FlowLayout(this)
    icFlow.hGap = dp(8)
    icFlow.vGap = dp(8)
    for (i in genreIconChoices.indices) {
        val cell = FrameLayout(this)
        cell.setPadding(dp(10), dp(10), dp(10), dp(10))
        val iv = IconView(this, genreIconChoices[i], Palette.pink, 22)
        cell.addView(iv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        cell.setOnClickListener {
            icon = genreIconChoices[i]
            restyle()
            seal.pop(1.3f)
        }
        cell.pressable(0.88f)
        iconCells.add(cell)
        iconViews.add(iv)
        icFlow.addView(cell)
    }
    col.addView(icFlow, lin(MATCH, WRAP))

    nameIn.doAfterTextChanged { restyle() }
    tagIn.doAfterTextChanged { restyle() }
    restyle()

    val builder = AlertDialog.Builder(this)
        .setTitle(if (existing == null) "Novo gênero" else "Editar gênero")
        .setView(sv)
        .setPositiveButton(if (existing == null) "Criar" else "Salvar", null)
        .setNegativeButton("Cancelar", null)
    if (existing != null && !existing.custom && Genres.isEdited(existing.key)) {
        builder.setNeutralButton("Restaurar original", null)
    }
    val dlg = builder.create()
    dlg.show()
    if (existing != null && !existing.custom && Genres.isEdited(existing.key)) {
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            Store.resetGenre(existing.key)
            dlg.dismiss()
            onDone(Genres.byKey(existing.key))
        }
    }
    dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val nm = nameIn.text.toString().trim()
        if (nm.isEmpty()) {
            nameIn.error = "Dê um nome ao gênero"
            return@setOnClickListener
        }
        if (Genres.all.any { it.label.equals(nm, true) && it.key != existing?.key }) {
            nameIn.error = "Esse gênero já existe"
            return@setOnClickListener
        }
        val tag = tagIn.text.toString().trim()
        val mid = midIn.text.toString().trim()
        val low = lowIn.text.toString().trim()
        if (existing == null) {
            val g = Genres.makeCustom("c" + System.currentTimeMillis(), nm, icon, color, tag, low, mid)
            Store.addGenre(g)
            dlg.dismiss()
            onDone(g)
        } else {
            val g = if (existing.custom) {
                Genres.makeCustom(existing.key, nm, icon, color, tag, low, mid)
            } else {
                Genres.editBuiltin(existing, nm, icon, color, tag, low, mid)
            }
            Store.updateGenre(g)
            dlg.dismiss()
            onDone(g)
        }
    }
}

/**
 * Janela para criar (existing = null) ou editar um "outro gênero" (subgênero), seja um que você criou
 * ou um que veio com o app. Nome, cor e símbolo, com prévia ao vivo. Eles só classificam e filtram.
 */
fun Activity.showOtherGenreEditor(existing: OtherGenre?, onDone: (OtherGenre) -> Unit) {
    if (existing == null && OtherGenres.custom().size >= 60) {
        softToast("Você já criou muitos outros gêneros!", Palette.pink, "tag")
        return
    }
    // quantas cores quiser (até 12) para mesclar; a mesma cor repetida não conta (não mescla)
    val cols = ArrayList<Int>(existing?.colors ?: listOf(genreColorChoices[0]))
    if (cols.isEmpty()) cols.add(genreColorChoices[0])
    var activeSlot = 0
    fun effective(): List<Int> = cols.distinct()
    var color = cols[0]
    var icon = existing?.icon ?: "tag"
    val icons: List<String> = if (genreIconChoices.contains(icon)) genreIconChoices else listOf(icon) + genreIconChoices

    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(8), dp(20), dp(6))
    sv.addView(col)

    // prévia: do jeitinho que aparece nas listas
    val prevBox = LinearLayout(this)
    prevBox.orientation = LinearLayout.HORIZONTAL
    prevBox.gravity = Gravity.CENTER
    prevBox.setPadding(dp(12), dp(16), dp(12), dp(16))
    col.addView(prevBox, lin(MATCH, WRAP))

    val nameIn = input("Nome (ex.: Sobrenatural)", existing?.label ?: "", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    nameIn.filters = arrayOf(InputFilter.LengthFilter(24))
    col.addView(label("Nome", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 6))
    col.addView(nameIn, lin(MATCH, WRAP))

    val iconCells = ArrayList<FrameLayout>()
    val iconViews = ArrayList<IconView>()

    fun restyle() {
        val nm = nameIn.text.toString().trim()
        val eff = effective()
        color = eff[0]
        val g = OtherGenre(existing?.key ?: "preview", if (nm.isEmpty()) "Seu gênero" else nm, icon, eff[0], false, eff.drop(1))
        prevBox.removeAllViews()
        prevBox.addView(otherPill(g, 14f))
        prevBox.background = roundRect(Palette.card, dp(20).toFloat(), Palette.line, dp(1))
        for (i in iconCells.indices) {
            val sel = icons[i] == icon
            iconCells[i].background = roundRect(if (sel) color else Palette.pinkSoft, dp(16).toFloat())
            iconViews[i].tint = if (sel) Color.WHITE else color
        }
    }

    col.addView(label("Cores (quantas quiser)", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 2))
    col.addView(
        label("Toque no + para adicionar mais uma cor: todas se mesclam num degradê. Se repetir a mesma cor, ela não mescla.", 11.5f, Palette.muted),
        lin(MATCH, WRAP, b = 8)
    )
    val slotFlow = FlowLayout(this)
    slotFlow.hGap = dp(8)
    slotFlow.vGap = dp(8)
    col.addView(slotFlow, lin(MATCH, WRAP))
    val pickerBox = FrameLayout(this)
    col.addView(pickerBox, lin(MATCH, WRAP, t = 10))
    val removeBtn = label("Tirar esta cor", 12.5f, Palette.pinkDark, true)
    removeBtn.setPadding(dp(4), dp(8), dp(4), dp(8))
    col.addView(removeBtn, lin(WRAP, WRAP, t = 4))

    lateinit var rebuildSlots: () -> Unit
    fun rebuildPicker() {
        pickerBox.removeAllViews()
        val idx = activeSlot
        val pk = ColorPicker(this, cols[idx]) {
            cols[idx] = it
            rebuildSlots()
            restyle()
            prevBox.pop(1.1f)
        }
        pickerBox.addView(pk, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    rebuildSlots = {
        slotFlow.removeAllViews()
        for (i in cols.indices) {
            val cell = FrameLayout(this)
            val dot = View(this)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(cols[i])
            bg.setStroke(if (i == activeSlot) dp(3) else dp(2), if (i == activeSlot) Palette.pink else Palette.line)
            dot.background = bg
            cell.addView(dot, FrameLayout.LayoutParams(dp(40), dp(40)))
            cell.setOnClickListener {
                activeSlot = i
                rebuildSlots()
                rebuildPicker()
            }
            cell.pressable(0.9f)
            slotFlow.addView(cell)
        }
        if (cols.size < 12) {
            val cell = FrameLayout(this)
            val plus = label("+", 20f, Palette.pinkDark, true)
            plus.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Palette.card)
            bg.setStroke(dp(2), Palette.pinkDark)
            plus.background = bg
            cell.addView(plus, FrameLayout.LayoutParams(dp(40), dp(40)))
            cell.setOnClickListener {
                cols.add(genreColorChoices[(cols.size * 9 + 4) % genreColorChoices.size])
                activeSlot = cols.size - 1
                rebuildSlots()
                rebuildPicker()
                restyle()
                prevBox.pop(1.1f)
            }
            cell.pressable(0.9f)
            slotFlow.addView(cell)
        }
        removeBtn.visibility = if (cols.size > 1) View.VISIBLE else View.GONE
    }
    removeBtn.setOnClickListener {
        if (cols.size > 1) {
            cols.removeAt(activeSlot)
            activeSlot = 0
            rebuildSlots()
            rebuildPicker()
            restyle()
            prevBox.pop(1.1f)
        }
    }
    rebuildSlots()
    rebuildPicker()

    col.addView(label("Símbolo", 12.5f, Palette.muted, true), lin(WRAP, WRAP, t = 14, b = 8))
    val icFlow = FlowLayout(this)
    icFlow.hGap = dp(8)
    icFlow.vGap = dp(8)
    for (i in icons.indices) {
        val cell = FrameLayout(this)
        cell.setPadding(dp(10), dp(10), dp(10), dp(10))
        val iv = IconView(this, icons[i], Palette.pink, 22)
        cell.addView(iv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        cell.setOnClickListener {
            icon = icons[i]
            restyle()
            prevBox.pop(1.1f)
        }
        cell.pressable(0.88f)
        iconCells.add(cell)
        iconViews.add(iv)
        icFlow.addView(cell)
    }
    col.addView(icFlow, lin(MATCH, WRAP))

    nameIn.doAfterTextChanged { restyle() }
    restyle()

    val canReset = existing != null && !existing.custom && OtherGenres.isEdited(existing.key)
    val builder = AlertDialog.Builder(this)
        .setTitle(if (existing == null) "Novo outro gênero" else "Editar outro gênero")
        .setView(sv)
        .setPositiveButton(if (existing == null) "Criar" else "Salvar", null)
        .setNegativeButton("Cancelar", null)
    if (canReset) builder.setNeutralButton("Restaurar original", null)
    val dlg = builder.create()
    dlg.show()
    if (canReset && existing != null) {
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            Store.resetOtherGenre(existing.key)
            dlg.dismiss()
            onDone(OtherGenres.byKey(existing.key))
        }
    }
    dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val nm = nameIn.text.toString().trim()
        if (nm.isEmpty()) {
            nameIn.error = "Dê um nome ao gênero"
            return@setOnClickListener
        }
        val dupOther = OtherGenres.all.any { it.label.equals(nm, true) && it.key != existing?.key }
        val dupMain = Genres.all.any { it.label.equals(nm, true) }
        if (dupOther || dupMain) {
            nameIn.error = "Esse gênero já existe"
            return@setOnClickListener
        }
        if (existing == null) {
            val eff = effective()
            val g = OtherGenres.makeCustom("x_c" + System.currentTimeMillis(), nm, icon, eff[0], eff.drop(1))
            Store.addOtherGenre(g)
            dlg.dismiss()
            onDone(g)
        } else {
            val eff = effective()
            val g = if (existing.custom) {
                OtherGenres.makeCustom(existing.key, nm, icon, eff[0], eff.drop(1))
            } else {
                OtherGenres.editBuiltin(existing, nm, icon, eff[0], eff.drop(1))
            }
            Store.updateOtherGenre(g)
            dlg.dismiss()
            onDone(g)
        }
    }
}
