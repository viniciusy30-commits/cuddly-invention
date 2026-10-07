package com.doramabloom.app

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private class NavItem(val box: LinearLayout, val icon: IconView?, val text: TextView?, val tab: Int)

    private lateinit var contentFrame: FrameLayout
    private lateinit var petals: PetalsView
    private var addCircle: FrameLayout? = null
    private lateinit var navBar: FrameLayout
    private lateinit var navIndicator: View
    private lateinit var navRow: LinearLayout
    private val navItems = ArrayList<NavItem>()
    private var tab = 0
    private var shownTab = -1
    private var seenVersion = -1
    private var accent = Palette.pink

    private var statusFilter = "all"
    private var genreFilter = "all"
    private var otherFilters: Set<String> = emptySet() // "Outros gêneros" (vários): só filtram, não mudam tema nem símbolos
    private var countryFilter = "all"
    private var query = ""
    private var sortMode = 0
    private var gridMode = false
    private var filtersOpen = false

    private var listAdapter: DramaAdapter? = null
    private var listRv: RecyclerView? = null
    private var listEmpty: TextView? = null
    private var listInfo: TextView? = null
    private var searchBox: LinearLayout? = null
    private var filterPill: TextView? = null
    private var clearPill: TextView? = null
    private var effBox: LinearLayout? = null
    private var effIcon: IconView? = null
    private var effSeal: SealView? = null
    private var effTitle: TextView? = null
    private var effSub: TextView? = null
    private var listBtn: FrameLayout? = null
    private var gridBtn: FrameLayout? = null

    private var homeWatching: List<Drama> = emptyList()
    private var homePage = 0
    private var homeAdapter: DramaAdapter? = null

    private fun errorView(titulo: String, e: Throwable): View {
        val tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.textSize = 12f
        tv.setTextColor(Color.parseColor("#B00020"))
        tv.setBackgroundColor(Palette.card)
        tv.setPadding(dp(14), dp(14), dp(14), dp(14))
        tv.text = titulo + "\n\n" + android.util.Log.getStackTraceString(e).take(1800)
        val sv = ScrollView(this)
        sv.addView(tv)
        return sv
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("doramabloom_crash", MODE_PRIVATE).edit()
                    .putString("last", android.util.Log.getStackTraceString(e)).commit()
            } catch (_: Throwable) {
            }
            oldHandler?.uncaughtException(t, e)
        }
        Store.init(this)
        ThemeMode.refresh(this)

        val root = FrameLayout(this)
        root.background = gradient(Palette.bgTop, Palette.bgBottom)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        contentFrame = FrameLayout(this)
        col.addView(contentFrame, lin(MATCH, 0, 1f))
        col.addView(
            try { buildNav() } catch (e: Throwable) { errorView("Erro ao montar a barra de baixo", e) },
            lin(MATCH, WRAP)
        )

        // pétalas de fundo ficam ATRÁS de tudo; as explosões de festa ficam por cima
        petals = PetalsView(this, listOf("petal", "blossom", "sparkle"), Palette.pink, 16)
        root.addView(petals, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        val fxTop = PetalsView(this, listOf("petal"), Palette.pink, 0)
        petals.fx = fxTop
        root.addView(fxTop, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        // depois de atualizar, mostra o resumo do que mudou; só então confere se há versão nova
        root.post {
            val shown = Novidades.showIfNew(this) { Updater.autoCheck(this) }
            if (!shown) Updater.autoCheck(this)
        }

        if (!Store.askedName) {
            root.post { askName() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (contentFrame.childCount == 0) {
            seenVersion = Store.version
            showTab(tab, true)
            return
        }
        if (seenVersion != Store.version) {
            seenVersion = Store.version
            if (tab == 1 && listAdapter != null) {
                refreshList()
            } else {
                showTab(tab, false)
            }
        }
    }

    private fun openEdit(from: View?) {
        if (from != null) {
            from.animate().rotation(90f).setDuration(220).withEndAction { from.rotation = 0f }.start()
        }
        startActivity(Intent(this, EditActivity::class.java))
        overridePendingTransition(R.anim.screen_in, R.anim.screen_out_back)
    }

    private fun lighten(c: Int, f: Float): Int {
        val r = Color.red(c) + ((255 - Color.red(c)) * f).toInt()
        val g = Color.green(c) + ((255 - Color.green(c)) * f).toInt()
        val b = Color.blue(c) + ((255 - Color.blue(c)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    // ------------------------------------------------------- menu e atmosfera

    private fun buildNav(): View {
        val bar = FrameLayout(this)
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))
        bar.background = roundRect(Palette.card, dp(30).toFloat(), Palette.line, dp(1))
        bar.elevation = 0f
        navBar = bar

        navIndicator = View(this)
        navIndicator.background = roundRect(Palette.pink, dp(22).toFloat())
        bar.addView(navIndicator, FrameLayout.LayoutParams(0, 0))

        navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        bar.addView(navRow, FrameLayout.LayoutParams(MATCH, WRAP))

        val holder = FrameLayout(this)
        holder.clipToPadding = false
        holder.clipChildren = false
        holder.setPadding(dp(14), dp(4), dp(14), dp(12))
        holder.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP))

        navItems.clear()
        val icons = listOf("home", "grid", "add", "chart", "tune")
        val names = listOf("Início", "Estante", "", "Números", "Config.")
        val tabs = listOf(0, 1, -1, 2, 4)
        for (i in icons.indices) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            if (tabs[i] == -1) {
                box.setPadding(dp(2), dp(2), dp(2), dp(2))
                val circle = FrameLayout(this)
                circle.background = ovalGradient(lighten(Palette.pink, 0.22f), Palette.pink)
                circle.elevation = 0f
                circle.addView(IconView(this, "add", Color.WHITE, 26), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addCircle = circle
                box.addView(circle, lin(dp(48), dp(48)))
                box.setOnClickListener { openEdit(circle) }
                box.pressable(0.9f)
                navRow.addView(box, lin(0, WRAP, 1f, l = 2, r = 2))
                navItems.add(NavItem(box, null, null, -1))
            } else {
                box.setPadding(dp(2), dp(8), dp(2), dp(8))
                val ic = IconView(this, icons[i], Palette.muted, 22)
                val tx = label(names[i], 11f, Palette.muted, true)
                tx.maxLines = 1
                box.addView(ic, lin(WRAP, WRAP))
                box.addView(tx, lin(WRAP, WRAP, t = 2))
                val target = tabs[i]
                box.setOnClickListener { if (tab != target) showTab(target, true) else box.pop() }
                box.pressable(0.92f)
                navRow.addView(box, lin(0, WRAP, 1f, l = 2, r = 2))
                navItems.add(NavItem(box, ic, tx, target))
            }
        }
        navRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeIndicator(false) }
        return holder
    }

    private fun placeIndicator(animated: Boolean) {
        if (navItems.isEmpty()) return
        val box = navItems.firstOrNull { it.tab == tab }?.box ?: return
        if (box.width <= 0) return
        val lp = navIndicator.layoutParams as FrameLayout.LayoutParams
        val wide = box.width - dp(4)
        val tall = navRow.height
        if (lp.width != wide || lp.height != tall) {
            lp.width = wide
            lp.height = tall
            navIndicator.layoutParams = lp
        }
        val x = box.left.toFloat() + dp(2)
        if (animated) {
            navIndicator.animate().translationX(x).setDuration(380)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        } else {
            navIndicator.animate().cancel()
            navIndicator.translationX = x
        }
    }

    private fun updateNav(animated: Boolean) {
        for (n in navItems) {
            val ic = n.icon ?: continue
            val tx = n.text ?: continue
            val sel = n.tab == tab
            val to = if (sel) Color.WHITE else Palette.muted
            val from = ic.tint
            if (animated && from != to) {
                animateColor(from, to) { c ->
                    ic.tint = c
                    tx.setTextColor(c)
                }
            } else {
                ic.tint = to
                tx.setTextColor(to)
            }
            if (sel && animated) ic.pop(1.3f)
        }
        placeIndicator(animated)
    }

    /** Cor de destaque da tela atual: muda com o dorama em destaque e com os filtros. */
    private fun currentAccent(): Int {
        return when (tab) {
            0 -> {
                val d = homeWatching.getOrNull(homePage)
                if (d != null) Genres.byKey(d.genre).primary else Palette.pink
            }
            1 -> when {
                genreFilter != "all" -> Genres.byKey(genreFilter).primary
                statusFilter == "fav" -> Palette.pink
                statusFilter != "all" -> Statuses.byKey(statusFilter).color
                countryFilter != "all" -> Atmosphere.country(countryFilter).second
                else -> Palette.pink
            }
            else -> Palette.pink
        }
    }

    private fun applyAtmos() {
        val a: Atmos = when (tab) {
            0 -> {
                val d = homeWatching.getOrNull(homePage)
                if (d != null) Atmosphere.ofDrama(d) else Atmosphere.of("all", "all", "all")
            }
            1 -> Atmosphere.of(genreFilter, statusFilter, countryFilter)
            else -> Atmosphere.of("all", "all", "all")
        }
        petals.setTheme(a.icons, a.tints)
        val to = currentAccent()
        val from = accent
        accent = to
        animateColor(from, to, 380L) { c ->
            addCircle?.background = ovalGradient(lighten(c, 0.22f), c)
            navIndicator.background = roundRect(c, dp(22).toFloat())
            searchBox?.background = roundRect(Palette.card, dp(24).toFloat(), if (tab == 1) c else Palette.line, dp(if (c == Palette.pink) 1 else 2))
            listInfo?.setTextColor(c)
        }
    }

    private fun showTab(t: Int, animate: Boolean = true) {
        val dir = if (t >= tab) 1 else -1
        tab = t
        contentFrame.removeAllViews()
        listAdapter = null
        listRv = null
        val v: View = try {
            val crash = getSharedPreferences("doramabloom_crash", MODE_PRIVATE)
            val last = crash.getString("last", null)
            if (last != null) {
                crash.edit().remove("last").commit()
                errorView("O app fechou da última vez com este erro", RuntimeException(last))
            } else {
                when (t) {
                    0 -> buildHome()
                    1 -> buildListTab()
                    4 -> buildSettings()
                    else -> buildStats()
                }
            }
        } catch (e: Throwable) {
            errorView("Erro ao montar a tela " + t, e)
        }
        contentFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        val changed = shownTab != t
        shownTab = t
        updateNav(animate && changed)
        applyAtmos()
        if (animate) {
            v.slideIn(dir)
            if (v is ScrollView && v.childCount > 0) {
                val inner = v.getChildAt(0)
                if (inner is LinearLayout) inner.staggerIn(60L)
            }
        }
    }

    /** Abre o dorama; [list] é a lista em que ele está (dá para deslizar entre eles). */
    private fun open(d: Drama, list: List<Drama>? = null, name: String = "Todos") {
        val i = Intent(this, DetailActivity::class.java)
        i.putExtra("id", d.id)
        i.putExtra("listName", name)
        val l = list ?: Store.all().sortedByDescending { it.addedAt }
        i.putExtra("ids", LongArray(l.size) { l[it].id })
        startActivity(i)
        overridePendingTransition(R.anim.screen_in, R.anim.screen_out_back)
    }

    /** Abre o navegador deste dorama, direto na última página que você viu. */
    private fun openWatch(d: Drama) {
        val i = Intent(this, WatchActivity::class.java)
        i.putExtra("id", d.id)
        startActivity(i)
        overridePendingTransition(R.anim.screen_in, R.anim.screen_out_back)
    }

    private fun section(t: String, icon: String): View {
        val v = sectionTitle(t, icon)
        v.setPadding(dp(4), dp(20), 0, dp(10))
        return v
    }

    // ---------------------------------------------------------------- INÍCIO

    private fun buildHome(): View {
        val all = Store.all()
        val watchingAll = all.filter { it.status == "assistindo" }
        val watching = if (sortMode == 4) manualOrder(watchingAll) else watchingAll
        homeWatching = watching
        if (homePage >= watching.size) homePage = 0

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(10), dp(16), dp(24))
        sv.addView(col)

        // cartão de boas-vindas
        val name = Store.userName
        val hiWrap = FrameLayout(this)
        hiWrap.background = gradient(
            Color.parseColor("#FF9DBF"), Color.parseColor("#FF6B9D"),
            dp(28).toFloat(), GradientDrawable.Orientation.TL_BR
        )
        hiWrap.elevation = 0f
        val deco = IconView(this, "blossom", Color.parseColor("#44FFFFFF"), 96)
        val dlp = FrameLayout.LayoutParams(WRAP, WRAP)
        dlp.gravity = Gravity.END or Gravity.TOP
        dlp.setMargins(0, dp(-14), dp(-10), 0)
        hiWrap.addView(deco, dlp)
        val deco2 = IconView(this, "petal", Color.parseColor("#55FFFFFF"), 30)
        val dlp2 = FrameLayout.LayoutParams(WRAP, WRAP)
        dlp2.gravity = Gravity.END or Gravity.BOTTOM
        dlp2.setMargins(0, 0, dp(70), dp(16))
        hiWrap.addView(deco2, dlp2)

        val hi = LinearLayout(this)
        hi.orientation = LinearLayout.VERTICAL
        hi.setPadding(dp(22), dp(20), dp(22), dp(20))
        hi.addView(label(if (name.isBlank()) "Oi, bem-vinda!" else "Oi, $name!", 25f, Color.WHITE, true, true))
        hi.addView(label("O que vamos assistir hoje?", 13f, Color.WHITE), lin(WRAP, WRAP, t = 2))
        hiWrap.addView(hi, FrameLayout.LayoutParams(MATCH, WRAP))

        col.addView(hiWrap, lin(MATCH, WRAP))

        // destaque: ocupa todo o espaço entre o cartão e a barra de baixo
        val feat = FrameLayout(this)
        feat.clipChildren = false
        col.addView(feat, lin(MATCH, dp(460), t = 14))

        var dotsBox: LinearLayout? = null
        if (watching.isEmpty()) {
            val e = LinearLayout(this)
            e.orientation = LinearLayout.VERTICAL
            e.gravity = Gravity.CENTER
            e.setPadding(dp(26), dp(26), dp(26), dp(26))
            e.background = roundRect(Palette.card, dp(34).toFloat(), Palette.line, dp(1))
            e.elevation = 0f
            val big = FrameLayout(this)
            big.background = ovalGradient(Palette.pinkSoft, Color.parseColor("#FFC2D8"))
            big.addView(IconView(this, "blossom", Palette.pink, 64), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            e.addView(big, lin(dp(120), dp(120)))
            big.postDelayed({ big.pop(1.4f) }, 300)
            if (all.isEmpty()) {
                e.addView(label("Sua estante está vazia", 22f, Palette.text, true, true), lin(WRAP, WRAP, t = 18))
                val m = label("Adicione seu primeiro dorama, com capa, nota, temporadas e muito carinho.", 13.5f, Palette.muted)
                m.gravity = Gravity.CENTER
                e.addView(m, lin(WRAP, WRAP, t = 6))
            } else {
                e.addView(label("Nada em andamento", 22f, Palette.text, true, true), lin(WRAP, WRAP, t = 18))
                val m = label("Que tal começar algum dos que você quer ver?", 13.5f, Palette.muted)
                m.gravity = Gravity.CENTER
                e.addView(m, lin(WRAP, WRAP, t = 6))
            }
            val b = pill("Adicionar dorama", Palette.pink, Color.WHITE, 15f, "add")
            b.setPadding(dp(22), dp(13), dp(22), dp(13))
            b.setOnClickListener { openEdit(null) }
            e.addView(b, lin(WRAP, WRAP, t = 18))
            feat.addView(e, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val rv = RecyclerView(this)
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.clipToPadding = false
            rv.itemAnimator = null
            val ad = DramaAdapter(4, { open(it, homeWatching, "Assistindo") }, { d, btn ->
                val g = Genres.byKey(d.genre)
                petals.burstFrom(
                    btn, listOf(g.icon, "heart", "blossom", "sparkle"),
                    listOf(g.primary, Color.WHITE, g.soft, Palette.pink), 12
                )
                openWatch(d)
            })
            ad.submit(watching)
            homeAdapter = ad
            rv.adapter = ad
            val snap = PagerSnapHelper()
            snap.attachToRecyclerView(rv)
            feat.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
            rv.post { (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(homePage, 0) }

            if (watching.size > 1) {
                val dots = LinearLayout(this)
                dots.gravity = Gravity.CENTER_HORIZONTAL
                dotsBox = dots
                val dotViews = ArrayList<View>()
                for (i in watching.indices) {
                    val dot = View(this)
                    dotViews.add(dot)
                    dots.addView(dot, lin(dp(if (i == homePage) 18 else 8), dp(8), l = 3, r = 3))
                }
                fun setDots(active: Int, animated: Boolean) {
                    for (i in dotViews.indices) {
                        val dot = dotViews[i]
                        val wTo = dp(if (i == active) 18 else 8)
                        val cTo = if (i == active) accent else Color.parseColor("#F3C6D8")
                        val lp = dot.layoutParams
                        if (animated && lp.width != wTo) {
                            val a = android.animation.ValueAnimator.ofInt(lp.width, wTo)
                            a.duration = 240
                            a.addUpdateListener {
                                lp.width = it.animatedValue as Int
                                dot.layoutParams = lp
                            }
                            a.start()
                        } else {
                            lp.width = wTo
                            dot.layoutParams = lp
                        }
                        dot.background = roundRect(cTo, dp(4).toFloat())
                    }
                }
                setDots(homePage, false)
                col.addView(dots, lin(MATCH, WRAP, t = 8))
                var lastPage = homePage
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                        val sv2 = snap.findSnapView(r.layoutManager) ?: return
                        val p = r.getChildAdapterPosition(sv2)
                        if (p >= 0 && p != lastPage) {
                            lastPage = p
                            homePage = p
                            applyAtmos()
                            setDots(p, true)
                        }
                    }
                })
            }
        }

        // ajusta a altura do destaque para preencher a tela
        val dotsH = if (dotsBox != null) dp(24) else 0
        sv.post {
            val avail = sv.height
            val gh = hiWrap.height
            if (avail > 0 && gh > 0) {
                val h = maxOf(dp(430), avail - gh - dp(10) - dp(14) - dotsH - dp(24))
                if (feat.layoutParams.height != h) {
                    feat.layoutParams.height = h
                    feat.requestLayout()
                }
            }
        }

        return sv
    }

    private fun askName() {
        val et = EditText(this)
        et.hint = "Seu nome"
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        et.setText(Store.userName)
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this)
            .setTitle("Como posso te chamar?")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                Store.userName = et.text.toString().trim()
                Store.askedName = true
                showTab(tab)
            }
            .setNegativeButton("Depois") { _, _ ->
                Store.askedName = true
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (tab != 0) {
            showTab(0, true)
            return
        }
        super.onBackPressed()
    }

    // --------------------------------------------------------------- ESTANTE

    private fun filtered(): List<Drama> {
        var l = Store.all()
        if (statusFilter == "fav") {
            l = l.filter { it.favorite }
        } else if (statusFilter != "all") {
            l = l.filter { it.status == statusFilter }
        }
        if (genreFilter != "all") l = l.filter { it.genre == genreFilter || it.tags.contains(genreFilter) }
        if (otherFilters.isNotEmpty()) l = l.filter { d -> otherFilters.all { d.tags.contains(it) } }
        if (countryFilter != "all") l = l.filter { it.country == countryFilter }
        if (query.isNotBlank()) {
            l = l.filter {
                it.title.contains(query, true) || it.original.contains(query, true) ||
                    it.cast.contains(query, true) || Streamings.names(it.platform).contains(query, true) ||
                    it.couple.contains(query, true)
            }
        }
        return when (sortMode) {
            0 -> l.sortedByDescending { it.addedAt }
            1 -> l.sortedBy { it.title.lowercase() }
            2 -> l.sortedByDescending { it.score }
            3 -> l.sortedByDescending { it.year }
            else -> manualOrder(l)
        }
    }

    private fun sortLabel(): String = when (sortMode) {
        0 -> "Recentes"
        1 -> "A–Z"
        2 -> "Melhor nota"
        3 -> "Ano"
        else -> "Minha ordem"
    }

    /** Nome da lista aberta na Estante, conforme os filtros (ex.: "Assistindo", "Esporte", "Favoritos · Coreia do Sul"). */
    private fun shelfListName(): String {
        val parts = ArrayList<String>()
        if (statusFilter == "fav") parts.add("Favoritos")
        else if (statusFilter != "all") parts.add(Statuses.byKey(statusFilter).label)
        if (genreFilter != "all") parts.add(Genres.byKey(genreFilter).label)
        for (k in otherFilters) if (OtherGenres.exists(k)) parts.add(OtherGenres.byKey(k).label)
        if (countryFilter != "all") parts.add(countryFilter)
        if (query.isNotBlank()) parts.add("Busca")
        return if (parts.isEmpty()) "Todos" else parts.joinToString(" · ")
    }

    private fun anyFilter(): Boolean = statusFilter != "all" || genreFilter != "all" || otherFilters.isNotEmpty() || countryFilter != "all"

    private fun filterCount(): Int =
        (if (statusFilter != "all") 1 else 0) + (if (genreFilter != "all") 1 else 0) +
            (if (otherFilters.isNotEmpty()) 1 else 0) + (if (countryFilter != "all") 1 else 0)

    private fun statusTagline(k: String): String = when (k) {
        "fav" -> "Os queridinhos do seu coração"
        "assistindo" -> "O que está rolando agora"
        "quero" -> "Sua listinha de desejos"
        "concluido" -> "Maratonas finalizadas, que orgulho!"
        "pausado" -> "Esperando a hora certa"
        else -> "Os que ficaram pelo caminho"
    }

    /** Faixa que mostra o "efeito" dos filtros ativos. */
    private fun updateEffect() {
        val box = effBox ?: return
        if (!anyFilter()) {
            if (box.visibility == View.VISIBLE) box.collapse()
            return
        }
        val parts = ArrayList<String>()
        var icon: String
        var title: String
        var sub: String
        var col: Int
        var soft: Int
        if (genreFilter != "all") {
            val g = Genres.byKey(genreFilter)
            icon = g.icon; title = g.label; sub = g.tagline; col = g.dark; soft = g.soft
        } else if (statusFilter != "all") {
            if (statusFilter == "fav") {
                icon = "star"; title = "Favoritos"; col = Palette.pinkDark; soft = Palette.pinkSoft
            } else {
                val st = Statuses.byKey(statusFilter)
                icon = st.icon; title = st.label; col = st.color; soft = lighten(st.color, 0.82f)
            }
            sub = statusTagline(statusFilter)
        } else if (otherFilters.any { OtherGenres.exists(it) }) {
            val ogs = otherFilters.filter { OtherGenres.exists(it) }.map { OtherGenres.byKey(it) }
            val og = ogs[0]
            icon = og.icon; title = ogs.joinToString(" · ") { it.label }; col = og.dark; soft = og.soft
            sub = "Doramas marcados como " + ogs.joinToString(" e ") { it.label }
        } else {
            val cc = Atmosphere.country(countryFilter)
            icon = "flag"; title = countryFilter; col = cc.second; soft = lighten(cc.second, 0.85f)
            sub = "Doramas direto de " + countryFilter
        }
        if (genreFilter != "all") parts.add(Genres.byKey(genreFilter).label)
        for (k in otherFilters) if (OtherGenres.exists(k)) parts.add(OtherGenres.byKey(k).label)
        if (statusFilter != "all") parts.add(if (statusFilter == "fav") "Favoritos" else Statuses.byKey(statusFilter).label)
        if (countryFilter != "all") parts.add(countryFilter)
        effIcon?.setIcon(icon)
        effIcon?.tint = col
        if (genreFilter != "all") {
            effSeal?.set(Genres.byKey(genreFilter))
            effSeal?.visibility = View.VISIBLE
            effIcon?.visibility = View.GONE
        } else {
            effSeal?.visibility = View.GONE
            effIcon?.visibility = View.VISIBLE
        }
        effTitle?.text = if (parts.size > 1) parts.joinToString(" · ") else title
        effTitle?.setTextColor(col)
        effSub?.text = sub
        box.background = roundRect(soft, dp(22).toFloat(), lighten(col, 0.5f), dp(1))
        if (box.visibility != View.VISIBLE) box.expand() else box.pop(1.05f)
    }

    private fun updateFilterPills() {
        val n = filterCount()
        filterPill?.text = if (n > 0) "Filtros · $n" else "Filtros"
        val cp = clearPill ?: return
        if (n > 0 && cp.visibility != View.VISIBLE) {
            cp.visibility = View.VISIBLE
            cp.fadeScaleIn()
        } else if (n == 0 && cp.visibility == View.VISIBLE) {
            cp.visibility = View.GONE
        }
    }

    private fun filtersChanged() {
        refreshList()
        updateEffect()
        updateFilterPills()
        applyAtmos()
    }

    private fun refreshList() {
        val l = filtered()
        listAdapter?.submit(l)
        listEmpty?.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        listEmpty?.text = if (Store.all().isEmpty()) {
            "Sua estante está vazia.\nToque no + para adicionar!"
        } else {
            "Nenhum dorama por aqui."
        }
        if (l.isEmpty()) listEmpty?.fadeScaleIn()
        val info = listInfo
        if (info != null) {
            val prev = info.text.toString().substringBefore(" ").toIntOrNull() ?: 0
            info.text = l.size.toString() + (if (l.size == 1) " dorama" else " doramas")
            if (prev != l.size) info.pop(1.08f)
        }
    }

    private var touchHelper: ItemTouchHelper? = null

    private fun applyView(rv: RecyclerView) {
        rv.layoutManager = if (gridMode) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        listAdapter = DramaAdapter(if (gridMode) 3 else 0, { open(it, filtered(), shelfListName()) })
        rv.adapter = listAdapter
        refreshList()

        // arrastar para reordenar (vale quando a ordem é "Minha ordem"; segure e arraste)
        touchHelper?.attachToRecyclerView(null)
        val th = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = sortMode == 4

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val a = viewHolder.bindingAdapterPosition
                val b = target.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                listAdapter?.move(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                    viewHolder.itemView.elevation = dp(10).toFloat()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                viewHolder.itemView.elevation = 0f
                val l = listAdapter?.items ?: return
                if (sortMode == 4) Store.reorder(l.map { it.id })
            }
        })
        th.attachToRecyclerView(rv)
        touchHelper = th
    }

    private fun squareBtn(icon: String, selected: Boolean): FrameLayout {
        val f = FrameLayout(this)
        f.background = roundRect(if (selected) Palette.pink else Palette.card, dp(16).toFloat(), Palette.line, dp(1))
        f.addView(
            IconView(this, icon, if (selected) Color.WHITE else Palette.pink, 20),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )
        f.pressable(0.88f)
        return f
    }

    private fun styleSquare(f: FrameLayout?, selected: Boolean) {
        if (f == null) return
        f.background = roundRect(if (selected) Palette.pink else Palette.card, dp(16).toFloat(), Palette.line, dp(1))
        (f.getChildAt(0) as IconView).tint = if (selected) Color.WHITE else Palette.pink
        if (selected) f.pop(1.2f)
    }

    private fun switchView(grid: Boolean) {
        if (gridMode == grid) return
        gridMode = grid
        styleSquare(listBtn, !gridMode)
        styleSquare(gridBtn, gridMode)
        val rv = listRv ?: return
        rv.animate().cancel()
        rv.animate().alpha(0f).setDuration(130).withEndAction {
            applyView(rv)
            rv.animate().alpha(1f).setDuration(260).start()
        }.start()
    }

    private fun buildListTab(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(12), dp(14), 0)

        // busca + alternar lista/grade
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val sbox = LinearLayout(this)
        sbox.orientation = LinearLayout.HORIZONTAL
        sbox.gravity = Gravity.CENTER_VERTICAL
        sbox.setPadding(dp(14), 0, dp(10), 0)
        sbox.background = roundRect(Palette.card, dp(24).toFloat(), Palette.line, dp(1))
        searchBox = sbox
        sbox.addView(IconView(this, "search", Palette.muted, 20))
        val search = EditText(this)
        search.hint = "Buscar título, elenco, plataforma"
        search.textSize = 14f
        search.setSingleLine(true)
        search.setTextColor(Palette.text)
        search.setHintTextColor(Palette.muted)
        search.background = null
        search.setText(query)
        search.doAfterTextChanged {
            query = it?.toString() ?: ""
            refreshList()
        }
        sbox.addView(search, lin(0, WRAP, 1f, l = 8))
        top.addView(sbox, lin(0, dp(46), 1f))

        val lb = squareBtn("list", !gridMode)
        val gb = squareBtn("grid", gridMode)
        listBtn = lb
        gridBtn = gb
        top.addView(lb, lin(dp(46), dp(46), l = 8))
        top.addView(gb, lin(dp(46), dp(46), l = 6))
        col.addView(top, lin(MATCH, WRAP))

        // status
        val statusOpts = ArrayList<Opt>()
        statusOpts.add(Opt("all", "Todos", Palette.pink, "heart"))
        statusOpts.add(Opt("fav", "Favoritos", Palette.pink, "star"))
        for (s in Statuses.all) statusOpts.add(Opt(s.key, s.label, s.color, s.icon))
        col.addView(chipScroller(statusOpts, statusFilter) {
            statusFilter = it
            filtersChanged()
        }, lin(MATCH, WRAP, t = 10))

        // filtros extras (gênero e país)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = if (filtersOpen) View.VISIBLE else View.GONE
        val genreOpts = ArrayList<Opt>()
        genreOpts.add(Opt("all", "Todos os gêneros", Palette.pink, "tag"))
        for (g in Genres.all) genreOpts.add(Opt(g.key, g.label, g.primary, g.icon))
        val glabRow = LinearLayout(this)
        glabRow.orientation = LinearLayout.HORIZONTAL
        glabRow.gravity = Gravity.CENTER_VERTICAL
        glabRow.addView(label("Gêneros", 13.5f, Palette.muted, true), lin(0, WRAP, 1f, l = 4))
        panel.addView(glabRow, lin(MATCH, WRAP, t = 8))
        panel.addView(chipScroller(genreOpts, genreFilter) {
            genreFilter = it
            filtersChanged()
        }, lin(MATCH, WRAP, t = 4))
        // outros gêneros: só etiquetas para filtrar (não trocam o tema nem os símbolos)
        val otherOpts = ArrayList<Opt>()
        for (og in OtherGenres.all) otherOpts.add(Opt(og.key, og.label, og.color, og.icon, og.colors))
        panel.addView(label("Outros gêneros (pode marcar vários)", 13.5f, Palette.muted, true), lin(WRAP, WRAP, t = 8, l = 4))
        panel.addView(multiChips(otherOpts, otherFilters) {
            otherFilters = it
            filtersChanged()
        }, lin(MATCH, WRAP, t = 4))
        val countryOpts = ArrayList<Opt>()
        countryOpts.add(Opt("all", "Todos os países", Palette.pink, "flag"))
        for (c in countries) countryOpts.add(Opt(c, c, Atmosphere.country(c).second, "flag"))
        panel.addView(label("Países", 13.5f, Palette.muted, true), lin(WRAP, WRAP, t = 8, l = 4))
        panel.addView(chipScroller(countryOpts, countryFilter) {
            countryFilter = it
            filtersChanged()
        }, lin(MATCH, WRAP, t = 4))
        col.addView(panel, lin(MATCH, WRAP))

        // faixa com o efeito dos filtros ativos
        val eb = LinearLayout(this)
        eb.orientation = LinearLayout.HORIZONTAL
        eb.gravity = Gravity.CENTER_VERTICAL
        eb.setPadding(dp(14), dp(10), dp(14), dp(10))
        val eseal = SealView(this)
        eseal.visibility = View.GONE
        eb.addView(eseal, lin(dp(48), dp(48)))
        effSeal = eseal
        val ei = IconView(this, "heart", Palette.pink, 30)
        eb.addView(ei)
        val etx = LinearLayout(this)
        etx.orientation = LinearLayout.VERTICAL
        val et = fitLabel("", 16f, Palette.pinkDark, true, true, 9f, true)
        val es = fitLabel("", 12f, Palette.muted, false, false, 8f)
        etx.addView(et, lin(MATCH, WRAP))
        etx.addView(es, lin(MATCH, WRAP))
        eb.addView(etx, lin(0, WRAP, 1f, l = 12))
        eb.visibility = if (anyFilter()) View.VISIBLE else View.GONE
        effBox = eb
        effIcon = ei
        effTitle = et
        effSub = es
        col.addView(eb, lin(MATCH, WRAP, t = 8))

        // contagem + filtros + ordenar
        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        val count = label("", 13f, Palette.muted, true)
        listInfo = count
        info.addView(count, lin(0, WRAP, 1f))
        val clr = pill("Limpar", Palette.pinkSoft, Palette.pinkDark, 12f, "close")
        clr.visibility = View.GONE
        clr.setOnClickListener {
            statusFilter = "all"
            genreFilter = "all"
            otherFilters = emptySet()
            countryFilter = "all"
            showTab(1, false)
        }
        clearPill = clr
        info.addView(clr, lin(WRAP, WRAP, r = 6))
        val fb = pill("Filtros", Palette.card, Palette.pink, 12f, "filter")
        filterPill = fb
        fb.setOnClickListener {
            filtersOpen = !filtersOpen
            if (filtersOpen) panel.expand() else panel.collapse()
        }
        info.addView(fb, lin(WRAP, WRAP, r = 6))
        val sb = pill(sortLabel(), Palette.card, Palette.pink, 12f, "sort")
        sb.setOnClickListener {
            sortMode = (sortMode + 1) % 5
            sb.text = sortLabel()
            sb.pop(1.2f)
            refreshList()
            if (sortMode == 4) softToast("Segure um dorama e arraste para mudar a ordem", Palette.pink, "sort")
        }
        info.addView(sb, lin(WRAP, WRAP))
        col.addView(info, lin(MATCH, WRAP, t = 8, b = 2))

        val rv = RecyclerView(this)
        rv.clipToPadding = false
        rv.setPadding(0, dp(4), 0, dp(24))
        listRv = rv

        val empty = label("", 15f, Palette.muted, true, true)
        empty.gravity = Gravity.CENTER
        listEmpty = empty

        val frame = FrameLayout(this)
        frame.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        col.addView(frame, lin(MATCH, 0, 1f))

        lb.setOnClickListener { switchView(false) }
        gb.setOnClickListener { switchView(true) }

        applyView(rv)
        updateFilterPills()
        if (anyFilter()) {
            // preenche a faixa já aberta, sem animar
            val keep = eb.visibility
            eb.visibility = View.VISIBLE
            updateEffectNow()
            eb.visibility = keep
        }
        return col
    }

    private fun updateEffectNow() {
        val box = effBox ?: return
        val saved = box.visibility
        box.visibility = View.VISIBLE
        updateEffect()
        box.visibility = saved
    }

    // --------------------------------------------------------------- NÚMEROS

    private fun statBox(icon: String, value: String, name: String, count: Int? = null, fmt: (Int) -> String = { it.toString() }): LinearLayout {
        val b = card(12, 20)
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.addView(IconView(this, icon, Palette.pink, 22))
        val v = label(value, 19f, Palette.pinkDark, true, true)
        b.addView(v, lin(WRAP, WRAP, t = 4))
        if (count != null) v.countTo(count, 0, 250L, 800L, fmt)
        b.addView(label(name, 11f, Palette.muted), lin(WRAP, WRAP, t = 2))
        b.pressable(0.95f)
        return b
    }

    private fun barRow(name: String, n: Int, frac: Float, color: Int, delay: Long = 300L): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(label(name, 13f, Palette.text, true), lin(dp(96), WRAP))
        val bar = SoftBar(this)
        bar.barColor = color
        bar.animateTo(frac, 0f, delay, 700L)
        r.addView(bar, lin(0, dp(10), 1f, l = 4, r = 8))
        val cnt = label(n.toString(), 13f, Palette.muted, true)
        cnt.countTo(n, 0, delay, 700L)
        r.addView(cnt, lin(dp(24), WRAP))
        return r
    }

    private fun buildStats(): View {
        val all = Store.all()
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(14), dp(16), dp(24))
        sv.addView(col)

        val rated = all.filter { it.score > 0 }
        val avg = if (rated.isEmpty()) 0.0 else rated.map { it.score }.sum().toDouble() / rated.size
        val eps = all.map { watchedEps(it) }.sum()
        val hours = all.map { hoursWatched(it) }.sum()

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(statBox("heart", all.size.toString(), "doramas", all.size), lin(0, WRAP, 1f, r = 6))
        top.addView(statBox("play", eps.toString(), "episódios", eps), lin(0, WRAP, 1f, r = 6))
        top.addView(statBox("clock", "%.0f h".format(hours), "assistidas", hours.toInt()) { "$it h" }, lin(0, WRAP, 1f, r = 6))
        top.addView(statBox("star", if (rated.isEmpty()) "-" else "%.1f".format(avg), "nota média"), lin(0, WRAP, 1f))
        col.addView(top, lin(MATCH, WRAP))

        if (all.isEmpty()) {
            val e = card(16, 22)
            e.addView(label("Ainda sem números", 16f, Palette.text, true, true))
            e.addView(label("Adicione doramas e acompanhe suas estatísticas aqui.", 13f, Palette.muted), lin(WRAP, WRAP, t = 4))
            col.addView(e, lin(MATCH, WRAP, t = 16))
        } else {
            val byGenre = all.groupBy { it.genre }.entries.sortedByDescending { it.value.size }
            val fav = Genres.byKey(byGenre[0].key)
            val favCard = card(16, 24, fav.soft)
            val favRow = LinearLayout(this)
            favRow.orientation = LinearLayout.HORIZONTAL
            favRow.gravity = Gravity.CENTER_VERTICAL
            val favIcon = SealView(this)
            favIcon.set(fav)
            favRow.addView(favIcon, lin(dp(58), dp(58)))
            favIcon.postDelayed({ favIcon.pop(1.5f) }, 450)
            val ft = LinearLayout(this)
            ft.orientation = LinearLayout.VERTICAL
            ft.addView(label("Seu gênero favorito", 11.5f, Palette.muted))
            ft.addView(label(fav.label, 22f, fav.dark, true, true))
            ft.addView(label(fav.tagline, 12f, Palette.muted))
            favRow.addView(ft, lin(WRAP, WRAP, l = 14))
            favCard.addView(favRow)
            favCard.pressable(0.98f)
            col.addView(favCard, lin(MATCH, WRAP, t = 14))

            // anel por status
            col.addView(section("Minha estante", "bookmark"))
            val sc = card(16, 24)
            val srow = LinearLayout(this)
            srow.orientation = LinearLayout.HORIZONTAL
            srow.gravity = Gravity.CENTER_VERTICAL
            val donut = DonutView(this)
            donut.set(
                Statuses.all.map { s -> all.count { it.status == s.key }.toFloat() },
                Statuses.all.map { it.color },
                all.size.toString(), if (all.size == 1) "dorama" else "doramas"
            )
            srow.addView(donut, lin(WRAP, WRAP))
            val legend = LinearLayout(this)
            legend.orientation = LinearLayout.VERTICAL
            for (s in Statuses.all) {
                val n = all.count { it.status == s.key }
                val lr = LinearLayout(this)
                lr.orientation = LinearLayout.HORIZONTAL
                lr.gravity = Gravity.CENTER_VERTICAL
                val dot = View(this)
                dot.background = ovalGradient(s.color, s.color)
                lr.addView(dot, lin(dp(10), dp(10), r = 8))
                lr.addView(label(s.label, 13f, Palette.text, true), lin(0, WRAP, 1f))
                lr.addView(label(n.toString(), 13f, Palette.muted, true), lin(WRAP, WRAP))
                legend.addView(lr, lin(MATCH, WRAP, t = 5))
            }
            srow.addView(legend, lin(0, WRAP, 1f, l = 18))
            sc.addView(srow)
            col.addView(sc, lin(MATCH, WRAP))

            col.addView(section("Por gênero", "tag"))
            val gc = card(14, 22)
            val maxG = maxOf(1, byGenre[0].value.size)
            var gi = 0
            for (e in byGenre) {
                val g = Genres.byKey(e.key)
                gc.addView(barRow(g.label, e.value.size, e.value.size.toFloat() / maxG, g.primary, 300L + gi * 70L), lin(MATCH, WRAP, t = 6))
                gi++
            }
            col.addView(gc, lin(MATCH, WRAP))

            col.addView(section("Por país", "flag"))
            val cc = card(14, 22)
            val byCountry = all.groupBy { it.country }.entries.sortedByDescending { it.value.size }
            val maxC = maxOf(1, byCountry[0].value.size)
            var ci = 0
            for (e in byCountry) {
                cc.addView(barRow(e.key, e.value.size, e.value.size.toFloat() / maxC, Atmosphere.country(e.key).second, 300L + ci * 70L), lin(MATCH, WRAP, t = 6))
                ci++
            }
            col.addView(cc, lin(MATCH, WRAP))

            if (rated.isNotEmpty()) {
                col.addView(section("Distribuição das notas", "star"))
                val dc = card(14, 22)
                val maxS = maxOf(1, (1..10).map { s -> rated.count { it.score == s } }.maxOrNull() ?: 1)
                var si = 0
                for (s in 10 downTo 1) {
                    val n = rated.count { it.score == s }
                    dc.addView(barRow("Nota $s", n, n.toFloat() / maxS, Palette.pink, 300L + si * 50L), lin(MATCH, WRAP, t = 4))
                    si++
                }
                col.addView(dc, lin(MATCH, WRAP))

                col.addView(section("Mais bem avaliados", "heart"))
                val tc = card(14, 22)
                for (d in rated.sortedByDescending { it.score }.take(5)) {
                    val r = LinearLayout(this)
                    r.orientation = LinearLayout.HORIZONTAL
                    r.gravity = Gravity.CENTER_VERTICAL
                    r.setPadding(0, dp(6), 0, dp(6))
                    val t = label(d.title, 14f, Palette.text, true)
                    t.maxLines = 1
                    t.ellipsize = android.text.TextUtils.TruncateAt.END
                    r.addView(t, lin(0, WRAP, 1f))
                    val rv = RatingView(this, 14, false)
                    rv.score = d.score
                    rv.color = Genres.byKey(d.genre).primary
                    r.addView(rv, lin(WRAP, WRAP, l = 8))
                    r.setOnClickListener { open(d) }
                    r.pressable(0.97f)
                    tc.addView(r, lin(MATCH, WRAP))
                }
                col.addView(tc, lin(MATCH, WRAP))
            }
        }

        return sv
    }

    // --------------------------------------------------------------- CONFIG

    private fun askHome(show: TextView) {
        val et = EditText(this)
        et.hint = "https://..."
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        et.setText(Store.homeUrl)
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this)
            .setTitle("Página inicial")
            .setMessage("É onde cada dorama abre na primeira vez.")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                var v = et.text.toString().trim()
                if (v.isEmpty()) v = Store.DEFAULT_HOME
                else if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
                Store.homeUrl = v
                show.text = "Página inicial: $v"
            }
            .setNeutralButton("Google") { _, _ ->
                Store.homeUrl = Store.DEFAULT_HOME
                show.text = "Página inicial: " + Store.DEFAULT_HOME
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun buildSettings(): View {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(14), dp(16), dp(28))
        sv.addView(col)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconView(this, "tune", Palette.pink, 24))
        head.addView(label("Configurações", 26f, Palette.text, true, true), lin(WRAP, WRAP, l = 10))
        col.addView(head, lin(MATCH, WRAP, l = 4, b = 2))

        // aparência: claro, escuro ou o que o celular estiver usando
        col.addView(section("Aparência", "sparkle"))
        val ac = card(14, 22)
        ac.addView(label("Escolha o visual do app. Em \"Automático\" ele acompanha o celular.", 12f, Palette.muted))
        val themeOpts = listOf(
            Opt("0", "Automático", Palette.pink, "tune"),
            Opt("1", "Claro", Color.parseColor("#E0A93B"), "sun"),
            Opt("2", "Escuro", Color.parseColor("#6C63FF"), "moon")
        )
        ac.addView(chipFlow(themeOpts, Store.themeMode.toString()) {
            ThemeMode.set(it.toInt())
            // se o visual mudou, o AppCompat refaz as telas sozinho; senão só confirmamos
            if (ThemeMode.isDark() != Palette.dark) recreate()
        }, lin(MATCH, WRAP, t = 10))
        col.addView(ac, lin(MATCH, WRAP))

        // atualizações pelo próprio app
        col.addView(section("Atualizações", "download"))
        val uc = card(14, 22)
        val verTv = label("Versão instalada: v" + Updater.currentCode(this), 14f, Palette.text, true)
        verTv.setOnClickListener { Novidades.showAll(this) }
        uc.addView(verTv)
        val upBtn = bigPill("Verificar atualização", Palette.pink, Color.WHITE, 14f, "download")
        upBtn.setOnClickListener { Updater.check(this, false) }
        uc.addView(upBtn, lin(MATCH, WRAP, t = 12))
        col.addView(uc, lin(MATCH, WRAP))

        // perfil
        col.addView(section("Perfil", "person"))
        val pc = card(14, 22)
        val nm = Store.userName
        pc.addView(label(if (nm.isBlank()) "Ainda sem nome" else "Olá, $nm!", 16f, Palette.text, true, true))
        pc.addView(label("É assim que o app te chama na tela inicial.", 12f, Palette.muted), lin(WRAP, WRAP, t = 2))
        val nb = pill("Mudar nome", Palette.pink, Color.WHITE, 13f, "edit")
        nb.setOnClickListener { askName() }
        pc.addView(nb, lin(WRAP, WRAP, t = 10))
        col.addView(pc, lin(MATCH, WRAP))

        // navegador
        col.addView(section("Navegador", "globe"))
        val nc = card(14, 22)
        nc.addView(label("Tamanho do texto nas páginas", 12.5f, Palette.muted))
        val zr = LinearLayout(this)
        zr.orientation = LinearLayout.HORIZONTAL
        zr.gravity = Gravity.CENTER_VERTICAL
        val zTxt = label(Store.textZoom.toString() + "%", 18f, Palette.text, true, true)
        zTxt.gravity = Gravity.CENTER
        fun stepZoom(delta: Int) {
            val nv = (Store.textZoom + delta).coerceIn(50, 200)
            if (nv != Store.textZoom) {
                Store.textZoom = nv
                zTxt.text = nv.toString() + "%"
                zTxt.pop(1.2f)
            }
        }
        zr.addView(roundBtn("minus", Palette.pink, false, 16) { stepZoom(-10) }, lin(dp(42), dp(42)))
        zr.addView(zTxt, lin(0, WRAP, 1f))
        zr.addView(roundBtn("add", Palette.pink, true, 16) { stepZoom(10) }, lin(dp(42), dp(42)))
        nc.addView(zr, lin(MATCH, WRAP, t = 8))

        val homeTxt = label("Página inicial: " + Store.homeUrl, 12.5f, Palette.muted)
        nc.addView(homeTxt, lin(MATCH, WRAP, t = 16))
        val hb = pill("Mudar página inicial", Palette.pinkSoft, Palette.pinkDark, 13f, "globe")
        hb.setOnClickListener { askHome(homeTxt) }
        nc.addView(hb, lin(WRAP, WRAP, t = 8))

        val fb = pill("Esquecer páginas salvas", Palette.pinkSoft, Palette.pinkDark, 13f, "clock")
        fb.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Esquecer páginas salvas?")
                .setMessage("Cada dorama volta a abrir na página inicial. Seus doramas e progresso não mudam.")
                .setPositiveButton("Esquecer") { _, _ ->
                    for (d in Store.all()) {
                        if (d.lastUrl.isNotBlank()) {
                            d.lastUrl = ""
                            Store.save(d)
                        }
                    }
                    softToast("Páginas esquecidas", Palette.pink, "check")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        nc.addView(fb, lin(WRAP, WRAP, t = 10))

        val cb = pill("Limpar cookies e cache", Palette.pinkSoft, Palette.pinkDark, 13f, "delete")
        cb.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar cookies e cache?")
                .setMessage("Você vai sair das contas dos sites onde estiver logada.")
                .setPositiveButton("Limpar") { _, _ ->
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    softToast("Tudo limpinho", Palette.pink, "check")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        nc.addView(cb, lin(WRAP, WRAP, t = 10))
        col.addView(nc, lin(MATCH, WRAP))

        // gêneros
        col.addView(section("Gêneros", "tag"))
        val gcard = card(14, 22)
        gcard.addView(label("Cada gênero tem cor, ícone e selo próprios. Toque no lápis para editar qualquer um, ou crie os seus!", 12f, Palette.muted))
        val mine = Store.all()
        for (g in Genres.all) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val gs = SealView(this)
            gs.set(g)
            row.addView(gs, lin(dp(42), dp(42), r = 12))
            val gt = LinearLayout(this)
            gt.orientation = LinearLayout.VERTICAL
            gt.addView(label(g.label, 14f, g.dark, true))
            val n = mine.count { it.genre == g.key }
            val tg = label(g.tagline, 11f, Palette.muted)
            tg.maxLines = 1
            tg.ellipsize = android.text.TextUtils.TruncateAt.END
            gt.addView(tg, lin(MATCH, WRAP, t = 1))
            gt.addView(label(if (n == 1) "1 dorama" else "$n doramas", 11f, g.primary, true), lin(WRAP, WRAP, t = 2))
            row.addView(gt, lin(0, WRAP, 1f))
            val openEditor = {
                showGenreEditor(g) {
                    seenVersion = Store.version
                    showTab(4, false)
                }
            }
            row.addView(roundBtn("edit", g.primary, false, 14) { openEditor() }, lin(dp(34), dp(34), l = 8))
            if (g.custom) {
                row.addView(roundBtn("delete", g.primary, false, 14) {
                    AlertDialog.Builder(this)
                        .setTitle("Excluir gênero?")
                        .setMessage("Os doramas com \"" + g.label + "\" voltam para Romance.")
                        .setPositiveButton("Excluir") { _, _ ->
                            if (genreFilter == g.key) genreFilter = "all"
                            Store.removeGenre(g.key)
                            seenVersion = Store.version
                            showTab(4, false)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }, lin(dp(34), dp(34), l = 8))
            }
            gcard.addView(row, lin(MATCH, WRAP, t = 10))
        }
        val newGenre = bigPill("Criar novo gênero", Palette.pink, Color.WHITE, 14f, "add")
        newGenre.setOnClickListener {
            showGenreCreator {
                seenVersion = Store.version
                showTab(4, false)
            }
        }
        gcard.addView(newGenre, lin(MATCH, WRAP, t = 18))
        col.addView(gcard, lin(MATCH, WRAP))

        // outros gêneros: só classificam e filtram; dá para editar, criar, apagar e mesclar cores
        col.addView(section("Outros gêneros", "tag"))
        val ocard = card(14, 22)
        ocard.addView(
            label(
                "Etiquetas para classificar e filtrar (Policial, LGBTQ+, Chaebol…). Edite, apague ou crie as suas, e misture quantas cores quiser.",
                12f, Palette.muted
            )
        )
        val refreshOthers = {
            seenVersion = Store.version
            showTab(4, false)
        }
        val othersMine = Store.all()
        for (og in OtherGenres.all) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.addView(otherPill(og, 13f), lin(WRAP, WRAP))
            val n = othersMine.count { it.tags.contains(og.key) }
            left.addView(
                label(if (n == 1) "1 dorama" else "$n doramas", 11f, Palette.muted, true),
                lin(WRAP, WRAP, t = 3, l = 4)
            )
            row.addView(left, lin(0, WRAP, 1f))
            row.addView(roundBtn("edit", og.color, false, 14) {
                showOtherGenreEditor(og) { refreshOthers() }
            }, lin(dp(34), dp(34), l = 8))
            row.addView(roundBtn("delete", og.color, false, 14) {
                AlertDialog.Builder(this)
                    .setTitle("Excluir gênero?")
                    .setMessage("\"" + og.label + "\" sai dos doramas que têm essa etiqueta.")
                    .setPositiveButton("Excluir") { _, _ ->
                        if (otherFilters.contains(og.key)) otherFilters = otherFilters - og.key
                        if (og.custom) Store.removeOtherGenre(og.key) else Store.hideOtherGenre(og.key)
                        refreshOthers()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }, lin(dp(34), dp(34), l = 8))
            ocard.addView(row, lin(MATCH, WRAP, t = 10))
        }
        val newOther = bigPill("Criar novo outro gênero", Palette.pink, Color.WHITE, 14f, "add")
        newOther.setOnClickListener { showOtherGenreEditor(null) { refreshOthers() } }
        ocard.addView(newOther, lin(MATCH, WRAP, t = 18))
        if (OtherGenres.hiddenKeys().isNotEmpty() || OtherGenres.edited().isNotEmpty()) {
            val rst = pill("Restaurar padrões", Palette.pinkSoft, Palette.pinkDark, 13f, "replay")
            rst.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Restaurar padrões?")
                    .setMessage("Os que vieram com o app voltam ao original (os que você criou ficam).")
                    .setPositiveButton("Restaurar") { _, _ ->
                        Store.restoreOtherDefaults()
                        refreshOthers()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            ocard.addView(rst, lin(WRAP, WRAP, t = 10))
        }
        col.addView(ocard, lin(MATCH, WRAP))

        // backup
        col.addView(section("Backup", "download"))
        val bc = card(14, 22)
        bc.addView(
            label(
                "Cria um arquivo .zip com tudo: doramas, capas, gêneros e ajustes. " +
                    "Para restaurar neste ou em outro celular, é só escolher o arquivo.",
                12f, Palette.muted
            )
        )
        val brow = LinearLayout(this)
        brow.orientation = LinearLayout.HORIZONTAL
        val exp = pill("Baixar backup", Palette.pink, Color.WHITE, 13f, "upload")
        exp.setOnClickListener { exportBackup() }
        val imp = pill("Restaurar", Color.parseColor("#C38BD8"), Color.WHITE, 13f, "download")
        imp.setOnClickListener { importBackup() }
        brow.addView(exp, lin(WRAP, WRAP, r = 8))
        brow.addView(imp, lin(WRAP, WRAP))
        bc.addView(brow, lin(WRAP, WRAP, t = 10))
        col.addView(bc, lin(MATCH, WRAP))

        col.addView(View(this), lin(MATCH, dp(16)))
        return sv
    }

    private val reqExport = 7301
    private val reqImport = 7302

    /** Abre o seletor do celular para você escolher onde salvar o arquivo de backup (.zip). */
    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_TITLE, Store.backupFileName())
        try {
            startActivityForResult(i, reqExport)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui abrir o seletor de arquivos.", Toast.LENGTH_LONG).show()
        }
    }

    /** Abre o seletor do celular para você escolher o arquivo de backup. */
    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/zip", "application/x-zip-compressed", "application/octet-stream",
                "application/json", "text/plain"
            )
        )
        try {
            startActivityForResult(i, reqImport)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui abrir o seletor de arquivos.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == reqExport) {
            doExport(uri)
        } else if (requestCode == reqImport) {
            askRestoreMode(uri)
        }
    }

    private fun doExport(uri: Uri) {
        Toast.makeText(this, "Gerando o backup…", Toast.LENGTH_SHORT).show()
        val snapshot = Store.backupSnapshot()
        Thread {
            var ok = false
            try {
                val os = contentResolver.openOutputStream(uri, "wt")
                if (os != null) {
                    os.use { ok = Store.writeBackup(snapshot, it) }
                }
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Backup salvo! Guarde esse arquivo com carinho.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Não consegui salvar o backup.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun askRestoreMode(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restaurar backup")
            .setMessage(
                "Substituir tudo apaga a lista atual e coloca a do arquivo, com capas e ajustes. " +
                    "Mesclar só adiciona o que ainda não existe."
            )
            .setPositiveButton("Substituir tudo") { _, _ -> doImport(uri, true) }
            .setNeutralButton("Mesclar") { _, _ -> doImport(uri, false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doImport(uri: Uri, replace: Boolean) {
        Toast.makeText(this, "Lendo o arquivo…", Toast.LENGTH_SHORT).show()
        Thread {
            var bytes: ByteArray? = null
            try {
                val ins = contentResolver.openInputStream(uri)
                if (ins != null) {
                    ins.use { bytes = it.readBytes() }
                }
            } catch (e: Exception) {
                bytes = null
            }
            val data = bytes
            runOnUiThread {
                val n = if (data == null) -1 else Store.readBackup(data, replace)
                if (n < 0) {
                    Toast.makeText(this, "Esse arquivo não parece um backup do app.", Toast.LENGTH_LONG).show()
                } else {
                    val msg = if (replace) "Backup restaurado: $n doramas." else "$n doramas adicionados."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    ThemeMode.applyNight()
                    if (ThemeMode.isDark() != Palette.dark) recreate() else showTab(4, false)
                }
            }
        }.start()
    }
}
