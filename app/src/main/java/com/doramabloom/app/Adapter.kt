package com.doramabloom.app

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/** Coração de favorito: só o coração (sem círculo), na cor do tema, com um contorno branco fininho para aparecer sobre a capa. */
fun Context.favBadge(heartDp: Int = 28): FrameLayout {
    val f = FrameLayout(this)
    f.elevation = 0f
    val halo = IconView(this, "heart", Color.WHITE, heartDp + 6)
    f.addView(halo, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    val ic = IconView(this, "heart", Palette.pink, heartDp)
    f.addView(ic, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    return f
}

/** Bolinha de informação: ícone em cima e texto curto embaixo, num cartão translúcido. */
fun Context.statBubble(icon: String, text: String, g: Genre, marks: List<Streaming> = emptyList()): View {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.VERTICAL
    b.gravity = Gravity.CENTER_HORIZONTAL
    b.setPadding(dp(3), dp(7), dp(3), dp(7))
    b.background = roundRect(Palette.card, dp(18).toFloat(), mixColor(g.primary, Palette.card, 0.45f), dp(2))
    b.elevation = dp(2).toFloat()
    if (marks.isNotEmpty()) {
        // só o símbolo da marca, centralizado na caixinha
        b.gravity = Gravity.CENTER
        b.minimumHeight = dp(50)
        // sem a caixinha em volta: só a logo
        b.background = null
        b.elevation = 0f
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val sz = if (marks.size == 1) 34 else 23
        for ((i, m) in marks.take(2).withIndex()) {
            val mv = View(this)
            mv.background = StreamMarkDrawable(m)
            row.addView(mv, lin(dp(sz), dp(sz), l = if (i > 0) 4 else 0))
        }
        b.addView(row, LinearLayout.LayoutParams(WRAP, WRAP))
        return b
    } else {
        b.addView(IconView(this, icon, g.primary, 16), LinearLayout.LayoutParams(WRAP, WRAP))
    }
    val t = fitLabel(text, 10.5f, g.dark, true, false, 7f)
    t.gravity = Gravity.CENTER
    b.addView(t, lin(MATCH, WRAP, t = 2))
    return b
}

/** Pinta o coração de favorito (solto ou com contorno) com a cor do tema. */
private fun tintFav(v: View?, color: Int) {
    if (v is IconView) v.tint = color
    else {
        val g = v as? ViewGroup ?: return
        (g.getChildAt(g.childCount - 1) as? IconView)?.tint = color
    }
}

private fun setPillIcon(t: TextView, name: String, color: Int) {
    val dr = t.compoundDrawables[0]
    if (dr is IconDrawable) {
        dr.name = name
        dr.color = color
    }
}

/**
 * mode 0 = linha detalhada da lista, 1 = card do carrossel, 2 = mini pôster horizontal,
 * 3 = pôster da grade, 4 = destaque grande (ocupa toda a área, usado no Início).
 */
class DramaAdapter(
    private val mode: Int,
    private val onClick: (Drama) -> Unit,
    private val onPlus: ((Drama, View) -> Unit)? = null
) : RecyclerView.Adapter<DramaAdapter.VH>() {

    var items: List<Drama> = emptyList()
    private var sigs: List<String> = emptyList()
    private var lastAnimated = -1

    init {
        setHasStableIds(true)
    }

    private fun sig(d: Drama): String =
        d.title + "|" + d.status + "|" + d.score + "|" + d.favorite + "|" + d.watched + "|" + d.seasonEps +
            "|" + d.genre + "|" + d.cover + "|" + d.country + "|" + d.year + "|" + d.tags + "|" +
            d.platform + "|" + d.watchSeason + "|" + d.shelfTags + "|" +
            shelfPick(d).joinToString(",") { k ->
                if (OtherGenres.exists(k)) OtherGenres.byKey(k).let { it.label + it.colors + it.icon } else k
            }

    override fun getItemId(position: Int): Long = items[position].id

    /** Atualiza a lista com animação (entram, saem e se mexem suavemente). */
    fun submit(l: List<Drama>) {
        val oldItems = items
        val oldSigs = sigs
        val newSigs = l.map { sig(it) }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = l.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean = oldItems[o].id == l[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean = oldSigs[o] == newSigs[n]
        })
        items = l
        sigs = newSigs
        diff.dispatchUpdatesTo(this)
    }

    /** Troca de lugar durante o arrastar (a lista e as assinaturas andam juntas). */
    fun move(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size || from == to) return
        val m = items.toMutableList()
        val x = m.removeAt(from)
        m.add(to, x)
        items = m
        val s = sigs.toMutableList()
        val sx = s.removeAt(from)
        s.add(to, sx)
        sigs = s
        notifyItemMoved(from, to)
    }

    /** Atualiza só o item (sem piscar). */
    fun refresh(d: Drama) {
        val i = items.indexOfFirst { it.id == d.id }
        if (i >= 0) {
            val m = sigs.toMutableList()
            m[i] = sig(d)
            sigs = m
            notifyItemChanged(i, "progress")
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        super.onViewRecycled(holder)
    }

    class VH(
        v: View,
        val cover: CoverView,
        val title: TextView,
        val sub: TextView? = null,
        val prog: TextView? = null,
        val bar: SoftBar? = null,
        val rating: RatingView? = null,
        val chip: TextView? = null,
        val genreChip: TextView? = null,
        val badge: TextView? = null,
        val plus: TextView? = null,
        val fav: View? = null,
        val scrim: View? = null,
        val seal: SealView? = null,
        val dot: FrameLayout? = null,
        val percent: TextView? = null,
        val flow: FlowLayout? = null,
        val plat: FlowLayout? = null,
        val note: TextView? = null,
        val frame: View? = null,
        val stats: LinearLayout? = null,
        val tagline: TextView? = null
    ) : RecyclerView.ViewHolder(v)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        return when (mode) {
            0 -> buildRow(c)
            1 -> buildCard(c)
            2 -> buildPoster(c, c.dp(112), c.dp(160), false)
            4 -> buildFeatured(c)
            else -> buildGridCard(c)
        }
    }

    /** Parte que muda com o progresso (também usada na atualização rápida). */
    private fun bindDynamic(h: VH, d: Drama, g: Genre, st: Status, animate: Boolean) {
        h.prog?.text = progressText(d)
        if (animate) h.prog?.pop(1.05f)
        val bar = h.bar
        if (bar != null) {
            bar.barColor = g.primary
            if (animate) bar.animateTo(progressOf(d)) else bar.progress = progressOf(d)
        }
        val pc = h.percent
        if (pc != null) {
            pc.text = (progressOf(d) * 100).toInt().toString() + "%"
            pc.setTextColor(g.dark)
        }
        val chip = h.chip
        if (chip != null) {
            chip.text = st.label
            chip.background = roundRect(st.color, chip.dp(14).toFloat())
            setPillIcon(chip, st.icon, Color.WHITE)
        }
        val badge = h.badge
        if (badge != null) {
            styleBadge(badge, d.score, g.primary)
            if (mode == 4) badge.visibility = if (d.score > 0) View.VISIBLE else View.GONE
        }
        val tg = h.tagline
        if (tg != null) {
            val line = g.lineFor(d.score)
            val auto = line.startsWith("Seu gênero")
            tg.visibility = if (auto || line.isBlank()) View.GONE else View.VISIBLE
            tg.text = line
            tg.setTextColor(g.dark)
            tg.setCompoundDrawables(tg.context.iconDrawable("sparkle", g.primary, tg.context.dp(13)), null, null, null)
        }
        val statsBox = h.stats
        if (statsBox != null) {
            statsBox.removeAllViews()
            val c = statsBox.context
            val total = totalEps(d)
            val items = ArrayList<Triple<String, String, List<Streaming>>>()
            if (d.year.isNotBlank()) items.add(Triple("calendar", d.year, emptyList()))
            if (total > 0) items.add(Triple("tv", total.toString() + " eps", emptyList()))
            val sl = Streamings.parse(d.platform)
            if (sl.isNotEmpty()) items.add(Triple("play", sl[0].label, sl))
            else if (d.country.isNotBlank()) items.add(Triple("flag", d.country.substringBefore(" "), emptyList()))
            for (it2 in items.take(3)) statsBox.addView(c.statBubble(it2.first, it2.second, g, it2.third), c.lin(c.dp(54), WRAP, t = 6))
        }
        h.fav?.visibility = if (d.favorite) View.VISIBLE else View.GONE
        tintFav(h.fav, g.primary)
        val dot = h.dot
        if (dot != null) {
            dot.background = ovalGradient(st.color, st.color)
            (dot.getChildAt(0) as? IconView)?.setIcon(st.icon)
        }
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val d = items[position]
            bindDynamic(h, d, Genres.byKey(d.genre), Statuses.byKey(d.status), true)
            return
        }
        super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        val g = Genres.byKey(d.genre)
        val st = Statuses.byKey(d.status)
        val reqW = when (mode) {
            1 -> 500
            0 -> 300
            2 -> 320
            3 -> 440
            4 -> 800
            else -> 360
        }
        h.cover.bind(d, reqW)
        h.title.text = d.title
        if (mode == 4) h.title.setTextColor(g.dark)
        h.sub?.text = subtitle(d)
        bindDynamic(h, d, g, st, false)
        h.seal?.set(g)

        val rating = h.rating
        if (rating != null) {
            rating.score = d.score
            rating.color = g.primary
        }
        val note = h.note
        if (note != null) {
            note.text = if (d.score > 0) d.score.toString() + "/10" else "Sem nota"
            note.setTextColor(if (d.score > 0) g.dark else Palette.muted)
        }
        val gc = h.genreChip
        if (gc != null) {
            gc.text = g.label
            gc.background = roundRect(g.primary, gc.dp(14).toFloat(), Color.WHITE, gc.dp(1))
            gc.setTextColor(Color.WHITE)
            gc.setShadowLayer(gc.dp(2).toFloat(), 0f, gc.dp(1).toFloat(), Color.argb(130, 0, 0, 0))
            setPillIcon(gc, g.icon, Color.WHITE)
        }
        val fl = h.flow
        if (fl != null) {
            val keep = if (mode == 3) 1 else 2
            while (fl.childCount > keep) fl.removeViewAt(keep)
            // gênero principal + os (até 2) escolhidos na edição, sempre com as cores vivas deles
            for (tk in shelfPick(d)) {
                val tp = if (OtherGenres.exists(tk)) {
                    fl.context.otherPill(OtherGenres.byKey(tk), 10.5f)
                } else {
                    val tg = Genres.byKey(tk)
                    fl.context.vividPill(tg.label, tg.icon, listOf(tg.primary), 10.5f)
                }
                tp.setPadding(fl.dp(9), fl.dp(4), fl.dp(9), fl.dp(4))
                fl.addView(tp)
            }
        }
        val plat = h.plat
        if (plat != null) {
            if (d.platform.isBlank()) {
                plat.visibility = View.GONE
            } else {
                plat.visibility = View.VISIBLE
                plat.removeAllViews()
                for (sm in Streamings.parse(d.platform)) plat.addView(plat.context.streamChip(sm, 11f))
            }
        }
        val fr = h.frame
        if (fr != null) {
            if (mode == 0 || mode == 3) {
                fr.background = roundRect(Palette.card, fr.dp(24).toFloat(), mixColor(g.primary, Color.WHITE, 0.6f), fr.dp(1))
            } else {
                fr.background = roundRect(g.soft, fr.dp(22).toFloat(), mixColor(g.primary, Color.WHITE, 0.55f), fr.dp(1))
            }
        }
        val scrim = h.scrim
        if (scrim != null) {
            val dk = g.deep
            val deep = Color.argb(215, Color.red(dk), Color.green(dk), Color.blue(dk))
            scrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, deep))
        }
        val plus = h.plus
        if (plus != null) {
            plus.background = roundRect(g.primary, plus.dp(if (mode == 4) 26 else 22).toFloat())
            plus.setOnClickListener { onPlus?.invoke(d, plus) }
        }
        h.itemView.setOnClickListener { onClick(d) }

        // entrada suave em cascata (só na primeira vez que o item aparece)
        if ((mode == 0 || mode == 3) && position > lastAnimated) {
            lastAnimated = position
            h.itemView.riseIn(minOf(position, 8) * 45L, 18, 380L)
        } else {
            h.itemView.alpha = 1f
            h.itemView.translationY = 0f
        }
    }

    private fun smallPill(c: Context, icon: String): TextView {
        val t = c.pill("", Palette.pink, Color.WHITE, 10.5f, icon)
        t.setPadding(c.dp(9), c.dp(4), c.dp(9), c.dp(4))
        return t
    }

    /** Lista: cartão detalhado, com selo do gênero, pílulas, nota, plataforma e progresso. */
    private fun buildRow(c: Context): VH {
        val root = LinearLayout(c)
        root.orientation = LinearLayout.HORIZONTAL
        root.setPadding(c.dp(10), c.dp(10), c.dp(12), c.dp(10))
        root.background = roundRect(Palette.card, c.dp(24).toFloat(), Palette.line, c.dp(1))
        root.elevation = 0f
        val rlp = RecyclerView.LayoutParams(MATCH, WRAP)
        rlp.setMargins(c.dp(4), c.dp(6), c.dp(4), c.dp(6))
        root.layoutParams = rlp
        root.pressable(0.97f)

        val coverFrame = TallFrame(c, c.dp(158))
        val cover = CoverView(c, 18)
        coverFrame.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        val seal = SealView(c)
        val slp = FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.TOP or Gravity.START)
        slp.setMargins(c.dp(5), c.dp(5), 0, 0)
        coverFrame.addView(seal, slp)
        root.addView(coverFrame, c.lin(c.dp(112), MATCH, r = 14))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL

        val trow = LinearLayout(c)
        trow.orientation = LinearLayout.HORIZONTAL
        trow.gravity = Gravity.CENTER_VERTICAL
        val title = c.label("", 16f, Palette.text, true, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val fav = IconView(c, "heart", Palette.pink, 24)
        trow.addView(title, c.lin(0, WRAP, 1f))
        trow.addView(fav, c.lin(WRAP, WRAP, l = 6))
        col.addView(trow, c.lin(MATCH, WRAP))

        val sub = c.label("", 11.5f, Palette.muted)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(sub, c.lin(MATCH, WRAP, t = 2))

        val flow = FlowLayout(c)
        flow.hGap = c.dp(5)
        flow.vGap = c.dp(5)
        val gc = smallPill(c, "heart")
        val chip = smallPill(c, "play")
        flow.addView(gc)
        flow.addView(chip)
        col.addView(flow, c.lin(MATCH, WRAP, t = 7))

        val rrow = LinearLayout(c)
        rrow.orientation = LinearLayout.HORIZONTAL
        rrow.gravity = Gravity.CENTER_VERTICAL
        val rating = RatingView(c, 13, false)
        val note = c.label("", 11.5f, Palette.muted, true)
        rrow.addView(rating, c.lin(WRAP, WRAP))
        rrow.addView(note, c.lin(WRAP, WRAP, l = 8))
        col.addView(rrow, c.lin(MATCH, WRAP, t = 7))

        val plat = FlowLayout(c)
        plat.hGap = c.dp(6)
        plat.vGap = c.dp(6)
        col.addView(plat, c.lin(MATCH, WRAP, t = 6))

        val prow = LinearLayout(c)
        prow.orientation = LinearLayout.HORIZONTAL
        prow.gravity = Gravity.CENTER_VERTICAL
        val prog = c.label("", 11.5f, Palette.text, true)
        prog.maxLines = 1
        prog.ellipsize = TextUtils.TruncateAt.END
        val percent = c.label("", 11.5f, Palette.pinkDark, true)
        prow.addView(prog, c.lin(0, WRAP, 1f, r = 6))
        prow.addView(percent, c.lin(WRAP, WRAP))
        col.addView(prow, c.lin(MATCH, WRAP, t = 8))
        val bar = SoftBar(c)
        col.addView(bar, c.lin(MATCH, c.dp(8), t = 4))

        root.addView(col, c.lin(0, WRAP, 1f))
        return VH(
            root, cover, title, sub = sub, prog = prog, bar = bar, rating = rating,
            chip = chip, genreChip = gc, fav = fav, seal = seal, percent = percent,
            flow = flow, plat = plat, note = note, frame = root
        )
    }

    private fun buildCard(c: Context): VH {
        val root = LinearLayout(c)
        root.orientation = LinearLayout.HORIZONTAL
        root.setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
        root.background = roundRect(Palette.card, c.dp(28).toFloat(), Palette.line, c.dp(1))
        root.elevation = 0f
        val rlp = RecyclerView.LayoutParams(MATCH, WRAP)
        rlp.setMargins(c.dp(6), c.dp(6), c.dp(6), c.dp(10))
        root.layoutParams = rlp
        root.pressable(0.97f)

        val cover = CoverView(c, 20)
        root.addView(cover, c.lin(c.dp(116), c.dp(172), r = 14))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL

        val trow = LinearLayout(c)
        trow.orientation = LinearLayout.HORIZONTAL
        trow.gravity = Gravity.CENTER_VERTICAL
        val title = c.label("", 19f, Palette.text, true, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val fav = IconView(c, "heart", Palette.pink, 26)
        trow.addView(title, c.lin(0, WRAP, 1f))
        trow.addView(fav, c.lin(WRAP, WRAP, l = 6))
        col.addView(trow, c.lin(MATCH, WRAP))

        val gc = smallPill(c, "heart")
        col.addView(gc, c.lin(WRAP, WRAP, t = 6))

        val sub = c.label("", 11.5f, Palette.muted)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(sub, c.lin(MATCH, WRAP, t = 6))

        val rating = RatingView(c, 15, false)
        col.addView(rating, c.lin(WRAP, WRAP, t = 6))

        val prog = c.label("", 12.5f, Palette.text, true)
        col.addView(prog, c.lin(WRAP, WRAP, t = 8))
        val bar = SoftBar(c)
        col.addView(bar, c.lin(MATCH, c.dp(9), t = 4))

        val plus = c.pill("Continuar assistindo", Palette.pink, Color.WHITE, 13f, "play")
        plus.setPadding(c.dp(14), c.dp(9), c.dp(14), c.dp(9))
        col.addView(plus, c.lin(MATCH, WRAP, t = 10))

        root.addView(col, c.lin(0, WRAP, 1f))
        return VH(
            root, cover, title, sub = sub, prog = prog, bar = bar,
            rating = rating, genreChip = gc, plus = plus, fav = fav
        )
    }

    /** Início: capa grande com selo do gênero e o botão "Continuar assistindo". */
    private fun buildFeatured(c: Context): VH {
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.background = roundRect(Palette.card, c.dp(34).toFloat(), Palette.line, c.dp(1))
        root.elevation = 0f
        root.clipToOutline = true
        val rlp = RecyclerView.LayoutParams(MATCH, MATCH)
        rlp.setMargins(c.dp(2), c.dp(4), c.dp(2), c.dp(8))
        root.layoutParams = rlp
        root.pressable(0.985f)

        // ---- capa grande
        val hero = FrameLayout(c)
        val cover = CoverView(c, 0, true)
        hero.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))

        val badge = c.scoreBadge(40, 17f)
        val blp = FrameLayout.LayoutParams(c.dp(40), c.dp(40))
        blp.gravity = Gravity.TOP or Gravity.START
        blp.setMargins(c.dp(14), c.dp(14), 0, 0)
        hero.addView(badge, blp)

        val fav = c.favBadge(40)
        val flp = FrameLayout.LayoutParams(c.dp(52), c.dp(52))
        flp.gravity = Gravity.TOP or Gravity.END
        flp.setMargins(0, c.dp(12), c.dp(12), 0)
        hero.addView(fav, flp)

        // selo do gênero: adesivo ao lado do título, na parte de baixo do cartão
        val seal = SealView(c, true)
        seal.rotation = -8f
        // bolinhas de informação (ano, episódios, plataforma) no espaço ao lado da capa
        val stats = LinearLayout(c)
        stats.orientation = LinearLayout.VERTICAL
        stats.gravity = Gravity.CENTER_HORIZONTAL
        val stlp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        stlp.setMargins(c.dp(7), 0, 0, c.dp(46))
        hero.addView(stats, stlp)
        hero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val ok = cover.posterLeft() >= c.dp(66)
            val want = if (ok) View.VISIBLE else View.INVISIBLE
            if (stats.visibility != want) stats.visibility = want
        }
        root.addView(hero, c.lin(MATCH, 0, 1f))

        // ---- informações e botão
        val info = LinearLayout(c)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(c.dp(18), c.dp(14), c.dp(18), c.dp(16))

        // título e subtítulo ficam aqui embaixo, para não taparem a capa
        val title = c.label("", 23f, Palette.text, true, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val textCol = LinearLayout(c)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.addView(title, c.lin(MATCH, WRAP))
        val sub = c.label("", 12.5f, Palette.muted)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        textCol.addView(sub, c.lin(MATCH, WRAP, t = 2, b = 4))
        // frase fofa do gênero
        val tag = c.fitLabel("", 12f, Palette.muted, false, false, 8f)
        tag.setTypeface(tag.typeface, android.graphics.Typeface.ITALIC)
        tag.compoundDrawablePadding = c.dp(6)
        textCol.addView(tag, c.lin(MATCH, WRAP))
        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(textCol, c.lin(0, WRAP, 1f))
        head.addView(seal, c.lin(c.dp(64), c.dp(64), l = 10, r = 2))
        info.addView(head, c.lin(MATCH, WRAP, b = 10))

        val chips = LinearLayout(c)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        val gc = smallPill(c, "heart")
        val chip = smallPill(c, "play")
        chips.addView(gc, c.lin(WRAP, WRAP, r = 6))
        chips.addView(chip, c.lin(WRAP, WRAP))
        info.addView(chips, c.lin(MATCH, WRAP))

        val prow = LinearLayout(c)
        prow.orientation = LinearLayout.HORIZONTAL
        prow.gravity = Gravity.CENTER_VERTICAL
        val prog = c.label("", 14f, Palette.text, true)
        prog.maxLines = 1
        prog.ellipsize = TextUtils.TruncateAt.END
        prow.addView(prog, c.lin(0, WRAP, 1f, r = 8))
        val rating = RatingView(c, 17, false)
        prow.addView(rating, c.lin(WRAP, WRAP))
        info.addView(prow, c.lin(MATCH, WRAP, t = 12))
        val bar = SoftBar(c)
        info.addView(bar, c.lin(MATCH, c.dp(11), t = 6))

        val plus = c.pill("Continuar assistindo", Palette.pink, Color.WHITE, 15f, "play")
        plus.setPadding(c.dp(18), c.dp(13), c.dp(18), c.dp(13))
        info.addView(plus, c.lin(MATCH, WRAP, t = 12))
        root.addView(info, c.lin(MATCH, WRAP))

        return VH(
            root, cover, title, sub = sub, prog = prog, bar = bar, rating = rating,
            chip = chip, genreChip = gc, badge = badge, plus = plus, fav = fav,
            stats = stats, tagline = tag, seal = seal
        )
    }

    /** Grade: cartão completo (igual ao da lista, em duas colunas): capa grande com selo, favorito e status; embaixo título, gêneros, nota, plataforma e progresso. */
    private fun buildGridCard(c: Context): VH {
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(10))
        root.background = roundRect(Palette.card, c.dp(24).toFloat(), Palette.line, c.dp(1))
        root.elevation = 0f
        val rlp = RecyclerView.LayoutParams(MATCH, WRAP)
        rlp.setMargins(c.dp(4), c.dp(6), c.dp(4), c.dp(6))
        root.layoutParams = rlp
        root.pressable(0.97f)

        val cellW = (c.resources.displayMetrics.widthPixels - c.dp(24)) / 2 - c.dp(8) - c.dp(16)
        val frame = FrameLayout(c)
        val cover = CoverView(c, 18)
        frame.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(c)
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, c.dp(64), Gravity.BOTTOM))

        val seal = SealView(c)
        val slp = FrameLayout.LayoutParams(c.dp(34), c.dp(34), Gravity.TOP or Gravity.START)
        slp.setMargins(c.dp(5), c.dp(5), 0, 0)
        frame.addView(seal, slp)

        val fav = c.favBadge(28)
        val flp = FrameLayout.LayoutParams(c.dp(36), c.dp(36))
        flp.gravity = Gravity.TOP or Gravity.END
        flp.setMargins(0, c.dp(4), c.dp(4), 0)
        frame.addView(fav, flp)

        val chip = smallPill(c, "play")
        val clp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        clp.setMargins(c.dp(6), 0, 0, c.dp(6))
        frame.addView(chip, clp)
        root.addView(frame, c.lin(MATCH, cellW * 4 / 3))

        val title = c.label("", 15f, Palette.text, true, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        root.addView(title, c.lin(MATCH, WRAP, t = 9, l = 3, r = 3))

        val sub = c.label("", 11f, Palette.muted)
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        root.addView(sub, c.lin(MATCH, WRAP, t = 2, l = 3, r = 3))

        val flow = FlowLayout(c)
        flow.hGap = c.dp(5)
        flow.vGap = c.dp(5)
        val gc = smallPill(c, "heart")
        flow.addView(gc)
        root.addView(flow, c.lin(MATCH, WRAP, t = 7, l = 3, r = 3))

        val rrow = LinearLayout(c)
        rrow.orientation = LinearLayout.HORIZONTAL
        rrow.gravity = Gravity.CENTER_VERTICAL
        val rating = RatingView(c, 12, false)
        val note = c.label("", 11f, Palette.muted, true)
        rrow.addView(rating, c.lin(WRAP, WRAP))
        rrow.addView(note, c.lin(WRAP, WRAP, l = 6))
        root.addView(rrow, c.lin(MATCH, WRAP, t = 7, l = 3, r = 3))

        val plat = FlowLayout(c)
        plat.hGap = c.dp(6)
        plat.vGap = c.dp(6)
        root.addView(plat, c.lin(MATCH, WRAP, t = 6, l = 3, r = 3))

        val prow = LinearLayout(c)
        prow.orientation = LinearLayout.HORIZONTAL
        prow.gravity = Gravity.CENTER_VERTICAL
        val prog = c.label("", 11f, Palette.text, true)
        prog.maxLines = 1
        prog.ellipsize = TextUtils.TruncateAt.END
        val percent = c.label("", 11f, Palette.pinkDark, true)
        prow.addView(prog, c.lin(0, WRAP, 1f, r = 6))
        prow.addView(percent, c.lin(WRAP, WRAP))
        root.addView(prow, c.lin(MATCH, WRAP, t = 8, l = 3, r = 3))
        val bar = SoftBar(c)
        root.addView(bar, c.lin(MATCH, c.dp(8), t = 4, l = 3, r = 3))

        return VH(
            root, cover, title, sub = sub, prog = prog, bar = bar, rating = rating,
            chip = chip, genreChip = gc, fav = fav, scrim = scrim, seal = seal, percent = percent,
            flow = flow, plat = plat, note = note, frame = root
        )
    }

    /** Grade: pôster com moldura na cor do gênero, selo, nota, status e progresso. */
    private fun buildPoster(c: Context, wParam: Int, hPx: Int, withBar: Boolean): VH {
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        val rlp = RecyclerView.LayoutParams(wParam, WRAP)
        if (withBar) rlp.setMargins(c.dp(4), c.dp(4), c.dp(4), c.dp(10)) else rlp.setMargins(0, c.dp(4), c.dp(10), c.dp(8))
        root.layoutParams = rlp
        root.pressable(0.95f)

        val outer = FrameLayout(c)
        outer.setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        val frame = FrameLayout(c)
        val cover = CoverView(c, 18)
        cover.elevation = 0f
        frame.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))

        val seal = SealView(c)
        val slp = FrameLayout.LayoutParams(c.dp(30), c.dp(30), Gravity.TOP or Gravity.START)
        slp.setMargins(c.dp(4), c.dp(4), 0, 0)
        frame.addView(seal, slp)

        val fav = c.favBadge(28)
        val flp = FrameLayout.LayoutParams(c.dp(36), c.dp(36))
        flp.gravity = Gravity.TOP or Gravity.END
        flp.setMargins(0, c.dp(4), c.dp(4), 0)
        frame.addView(fav, flp)

        val badge = c.scoreBadge(28, 12f)
        val blp = FrameLayout.LayoutParams(WRAP, WRAP)
        blp.gravity = Gravity.BOTTOM or Gravity.START
        blp.setMargins(c.dp(6), 0, 0, c.dp(6))
        frame.addView(badge, blp)

        val dot = FrameLayout(c)
        dot.addView(IconView(c, "play", Color.WHITE, 12), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val dlp = FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.BOTTOM or Gravity.END)
        dlp.setMargins(0, 0, c.dp(6), c.dp(6))
        frame.addView(dot, dlp)

        outer.addView(frame, FrameLayout.LayoutParams(MATCH, hPx))
        root.addView(outer, c.lin(MATCH, WRAP))

        val title = c.label("", 12.5f, Palette.text, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        root.addView(title, c.lin(MATCH, WRAP, t = 6, l = 2))

        var bar: SoftBar? = null
        var prog: TextView? = null
        if (withBar) {
            val p = c.label("", 10.5f, Palette.muted, true)
            p.maxLines = 1
            p.ellipsize = TextUtils.TruncateAt.END
            root.addView(p, c.lin(MATCH, WRAP, t = 1, l = 2))
            prog = p
            val b = SoftBar(c)
            root.addView(b, c.lin(MATCH, c.dp(6), t = 4, l = 2, r = 2))
            bar = b
        }
        return VH(
            root, cover, title, prog = prog, bar = bar, badge = badge, fav = fav,
            seal = seal, dot = dot, frame = outer
        )
    }
}
