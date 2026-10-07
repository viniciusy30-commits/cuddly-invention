package com.doramabloom.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.VelocityTracker
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tela é montada UMA vez; cada ação só atualiza os pedaços que mudam (com animação).
 * Antes ela era remontada inteira a cada toque, o que fazia a tela piscar.
 */
class DetailActivity : AppCompatActivity() {

    private var id = -1L
    private var ids = LongArray(0)
    private var rootView: View? = null
    private var listName = ""
    private var seen = -1
    private var scroll: ScrollView? = null
    private lateinit var petals: PetalsView
    private lateinit var host: FrameLayout
    private lateinit var gapBg: View
    private lateinit var gapPetals: PetalsView
    private val updaters = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        ThemeMode.refresh(this)
        id = intent.getLongExtra("id", -1L)
        ids = intent.getLongArrayExtra("ids") ?: LongArray(0)
        listName = intent.getStringExtra("listName") ?: ""
        if (Store.get(id) == null) {
            finish()
            return
        }
        // moldura fixa: atrás da página fica o "vão" com símbolos caindo, que aparece quando a página desliza
        host = FrameLayout(this)
        gapBg = View(this)
        gapBg.visibility = View.GONE
        host.addView(gapBg, FrameLayout.LayoutParams(MATCH, MATCH))
        gapPetals = PetalsView(this, listOf("petal"), Palette.pink, 34)
        gapPetals.visibility = View.GONE
        host.addView(gapPetals, FrameLayout.LayoutParams(MATCH, MATCH))
        build(true)
    }

    // ---------- deslizar entre os doramas da lista: a tela acompanha o dedo e troca com animação

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false
    private var tracker: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ids.size < 2) return super.dispatchTouchEvent(ev)
        if (animating) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                // tocar no player da trilha (inclusive arrastar as ondas) não troca de dorama
                blocked = swipeBlock?.let { v ->
                    val r = android.graphics.Rect()
                    v.isShown && v.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
                } ?: false
                // rolar o elenco (ou qualquer faixa que role para o lado) também não troca de dorama
                if (!blocked) {
                    val r2 = android.graphics.Rect()
                    blocked = swipeBlocks.any { v ->
                        val scrolls = v !is HorizontalScrollView || v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)
                        scrolls && v.isShown && v.getGlobalVisibleRect(r2) && r2.contains(ev.rawX.toInt(), ev.rawY.toInt())
                    }
                }
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && !blocked && Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    dragging = true
                    // cancela o toque dos filhos (rolagem, botões) para a tela acompanhar o dedo
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                }
                if (dragging) {
                    dragTo(dx)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    finishDrag(ev.x - downX, vx)
                    return true
                }
                tracker?.recycle()
                tracker = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private var gapStep = 0
    private var gapShown = false

    /** Mostra o vão atrás da página: fundo do tema + símbolos das categorias dos dois doramas, misturados. */
    private fun showGap(step: Int) {
        if (gapShown && gapStep == step) return
        val cur = Store.get(id) ?: return
        val nxt = neighbor(step)?.let { Store.get(it) }
        gapStep = step
        val a = Atmosphere.ofCategories(if (nxt != null) listOf(cur, nxt) else listOf(cur))
        gapPetals.setTheme(a.icons, a.tints)
        val g1 = Genres.byKey(cur.genre)
        val g2 = if (nxt != null) Genres.byKey(nxt.genre) else g1
        gapBg.background = gradient(mixColor(g1.soft, g2.soft, 0.5f), Palette.card)
        if (!gapShown) {
            gapShown = true
            gapBg.animate().cancel()
            gapPetals.animate().cancel()
            gapBg.alpha = 0f
            gapPetals.alpha = 0f
            gapBg.visibility = View.VISIBLE
            gapPetals.visibility = View.VISIBLE
            gapBg.animate().alpha(1f).setDuration(160).start()
            gapPetals.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun hideGap() {
        if (!gapShown) return
        gapShown = false
        gapBg.animate().alpha(0f).setDuration(380).start()
        gapPetals.animate().alpha(0f).setDuration(380).withEndAction {
            if (!gapShown) {
                gapBg.visibility = View.GONE
                gapPetals.visibility = View.GONE
            }
        }.start()
    }

    /** Durante a troca a página vira um cartãozinho de cantos redondos. */
    private fun cardify(r: View, on: Boolean) {
        if (on) {
            r.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(30).toFloat())
                }
            }
            r.clipToOutline = true
        } else {
            r.clipToOutline = false
            r.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun dragTo(dx: Float) {
        val r = rootView ?: return
        val w = maxOf(1f, r.width.toFloat())
        val step = if (dx < 0) 1 else -1
        val has = neighbor(step) != null
        val d = if (has) dx else dx * 0.25f // no fim da lista a tela "segura"
        val p = Math.min(1f, Math.abs(d) / w)
        cardify(r, true)
        showGap(step)
        r.translationX = d
        r.alpha = 1f - 0.45f * p
        r.scaleX = 1f - 0.07f * p
        r.scaleY = 1f - 0.07f * p
        r.rotation = d / w * 5f
    }

    private fun finishDrag(dx: Float, vx: Float) {
        val r = rootView ?: return
        val w = maxOf(1f, r.width.toFloat())
        val step = if (dx < 0) 1 else -1
        val nid = neighbor(step)
        val fast = Math.abs(vx) > 900f && (vx < 0) == (dx < 0)
        val commit = nid != null && (Math.abs(dx) > w * 0.25f || fast)
        if (!commit) {
            animating = true
            r.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(420).setInterpolator(OvershootInterpolator(1.5f))
                .withEndAction {
                    cardify(r, false)
                    animating = false
                }.start()
            hideGap()
            return
        }
        animating = true
        // a página sai pelo lado do dedo, mostrando o vão com os símbolos caindo...
        r.animate().translationX(-step * w * 1.1f).alpha(0f).scaleX(0.88f).scaleY(0.88f).rotation(-step * 7f)
            .setDuration(230).setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction {
                id = nid!!
                scroll = null
                // o vão fica à mostra um instante, só com os símbolos caindo
                showGap(step)
                build(true)
                val nr = rootView
                if (nr == null) {
                    animating = false
                    hideGap()
                } else {
                    cardify(nr, true)
                    nr.translationX = step * w * 0.9f
                    nr.alpha = 0f
                    nr.scaleX = 0.9f
                    nr.scaleY = 0.9f
                    nr.rotation = step * 5f
                    // ...e a próxima entra pelo lado oposto, com uma molinha no final
                    nr.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                        .setStartDelay(170).setDuration(560).setInterpolator(OvershootInterpolator(1.1f))
                        .withEndAction {
                            cardify(nr, false)
                            animating = false
                            hideGap()
                        }.start()
                }
            }.start()
    }

    /** Próximo (step = 1) ou anterior (step = -1) dorama da mesma lista. */
    private fun neighbor(step: Int): Long? {
        val i = ids.indexOf(id)
        if (i < 0) return null
        var n = i + step
        while (n >= 0 && n < ids.size) {
            if (Store.get(ids[n]) != null) return ids[n]
            n += step
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (Store.get(id) == null) {
            finish()
            return
        }
        // voltou da edição: reconstrói só se os dados mudaram
        if (seen != -1 && seen != Store.version) build(false)
    }

    // ---------------- trilha sonora: toca, pausa e para sozinha quando você sai da tela
    private var mp: MediaPlayer? = null
    private val ticker = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var musicReset: (() -> Unit)? = null
    @Volatile private var mpPreparing = false

    private fun releaseSound() {
        mpPreparing = false
        tick?.let { ticker.removeCallbacks(it) }
        tick = null
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        musicReset?.invoke()
    }

    override fun onStop() {
        super.onStop()
        releaseSound()
    }

    override fun onDestroy() {
        releaseSound()
        musicReset = null
        super.onDestroy()
    }

    private var swipeBlock: View? = null
    private val swipeBlocks = ArrayList<View>()
    private var blocked = false

    /** Player da trilha sonora: vinil girando, botão com símbolos do gênero em volta e ondas para pular. */
    private fun buildMusicBar(d: Drama, g: Genre): View {
        val sv = SoundtrackView(this, g, d.id)
        swipeBlock = sv

        musicReset = {
            sv.playing = false
            sv.progress = 0f
            sv.posMs = 0
            sv.durMs = 0
        }

        fun startTick() {
            tick?.let { ticker.removeCallbacks(it) }
            val r = object : Runnable {
                override fun run() {
                    val m = mp ?: return
                    try {
                        val dur = m.duration
                        sv.durMs = dur
                        sv.posMs = m.currentPosition
                        if (dur > 0) sv.progress = m.currentPosition.toFloat() / dur
                        if (m.isPlaying) ticker.postDelayed(this, 250)
                    } catch (e: Exception) {
                    }
                }
            }
            tick = r
            ticker.post(r)
        }

        fun toggle() {
            val cur = mp
            if (cur == null) {
                if (mpPreparing) return
                mpPreparing = true
                // o escurecer começa já no toque. Tudo que é pesado (criar o player, abrir o arquivo,
                // carregar e dar o start) roda em segundo plano, para a tela não travar no meio da animação
                sv.playing = true
                val path = d.soundtrack
                val tapAt = android.os.SystemClock.uptimeMillis()
                Thread {
                    var m: MediaPlayer? = null
                    try {
                        m = MediaPlayer()
                        m.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        m.setDataSource(path)
                        m.prepare()
                        if (!mpPreparing) {
                            m.release()
                            return@Thread
                        }
                        // espera o escurecer terminar: o início do áudio pesa e não pode travar a animação
                        val wait = tapAt + SoundtrackView.FADE_MS + 50L - android.os.SystemClock.uptimeMillis()
                        if (wait > 0L) Thread.sleep(wait)
                        if (!mpPreparing) {
                            m.release()
                            return@Thread
                        }
                        m.start()
                        val ready = m
                        runOnUiThread {
                            if (!mpPreparing || isFinishing || isDestroyed) {
                                try {
                                    ready.release()
                                } catch (e: Exception) {
                                }
                                return@runOnUiThread
                            }
                            ready.setOnCompletionListener {
                                it.seekTo(0)
                                sv.playing = false
                                sv.progress = 0f
                                sv.posMs = 0
                            }
                            ready.setOnErrorListener { _, _, _ ->
                                releaseSound()
                                softToast("Não consegui tocar esse arquivo.", Palette.pink, "music")
                                true
                            }
                            mp = ready
                            mpPreparing = false
                            sv.durMs = ready.duration
                            startTick()
                        }
                    } catch (e: Exception) {
                        try {
                            m?.release()
                        } catch (e2: Exception) {
                        }
                        runOnUiThread {
                            if (mpPreparing) {
                                releaseSound()
                                softToast("Não consegui tocar esse arquivo.", Palette.pink, "music")
                            }
                        }
                    }
                }.start()
            } else if (mpPreparing) {
                return
            } else if (cur.isPlaying) {
                cur.pause()
                sv.playing = false
            } else {
                cur.start()
                sv.playing = true
                startTick()
            }
        }

        sv.onToggle = { toggle() }
        sv.onSeek = { frac ->
            val m = mp
            if (m != null && !mpPreparing) {
                try {
                    val dur = m.duration
                    if (dur > 0) {
                        m.seekTo((frac * dur).toInt())
                        sv.posMs = (frac * dur).toInt()
                        sv.progress = frac
                    }
                } catch (e: Exception) {
                }
            }
        }
        return sv
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.screen_back_in, R.anim.screen_back_out)
    }

    private fun sync() {
        seen = Store.version
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(ms))

    private fun refreshAll() {
        for (u in updaters) u()
    }

    private fun build(entrance: Boolean) {
        releaseSound()
        swipeBlock = null
        swipeBlocks.clear()
        val d = Store.get(id)
        if (d == null) {
            finish()
            return
        }
        seen = Store.version
        updaters.clear()
        val keep = scroll?.scrollY ?: 0
        val g = Genres.byKey(d.genre)
        window.statusBarColor = g.soft

        val root = FrameLayout(this)
        root.background = gradient(g.soft, Palette.card)

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(10), dp(16), dp(40))
        col.clipChildren = false
        col.clipToPadding = false
        sv.clipChildren = false
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        petals = PetalsView(this, g.petals, g.primary, 16)
        root.addView(petals, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        val fxTop = PetalsView(this, listOf("petal"), g.primary, 0)
        petals.fx = fxTop
        val glass = mixColor(g.primary, Color.WHITE, 0.24f)

        // ---- barra superior
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(roundBtn("back", g.dark, false, 18) { finish() }, lin(dp(40), dp(40)))
        val pos = ids.indexOf(id)
        if (listName.isNotBlank() || (ids.size > 1 && pos >= 0)) {
            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            mid.gravity = Gravity.CENTER_HORIZONTAL
            if (listName.isNotBlank()) {
                val nl = fitLabel(listName, 14f, g.dark, true, true, 8f, true)
                nl.gravity = Gravity.CENTER
                nl.maxLines = 1
                nl.ellipsize = TextUtils.TruncateAt.END
                mid.addView(nl, lin(MATCH, WRAP))
            }
            if (ids.size > 1 && pos >= 0) {
                val pl = label("‹   " + (pos + 1) + " de " + ids.size + "   ›", 11.5f, g.primary, true)
                pl.gravity = Gravity.CENTER
                mid.addView(pl, lin(MATCH, WRAP, t = 1))
            }
            top.addView(mid, lin(0, WRAP, 1f, l = 6, r = 6))
        } else {
            top.addView(View(this), lin(0, dp(1), 1f))
        }
        top.addView(roundBtn("edit", g.dark, false, 18) {
            val i = Intent(this, EditActivity::class.java)
            i.putExtra("id", d.id)
            startActivity(i)
            overridePendingTransition(R.anim.screen_in, R.anim.screen_out_back)
        }, lin(dp(40), dp(40), r = 8))

        val favBtn = FrameLayout(this)
        val favIcon = IconView(this, "heart", g.primary, 28)
        favBtn.addView(favIcon, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        fun styleFav() {
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (d.favorite) g.primary else Palette.card)
            bg.setStroke(dp(2), g.primary)
            favBtn.background = bg
            favIcon.tint = if (d.favorite) Color.WHITE else g.primary
        }
        styleFav()
        favBtn.pressable(0.86f)
        favBtn.setOnClickListener {
            d.favorite = !d.favorite
            Store.save(d)
            sync()
            styleFav()
            favIcon.pop(1.9f)
            if (d.favorite) {
                petals.burstFrom(favBtn, listOf("heart", "heart", "sparkle"), listOf(g.primary, g.dark, Color.WHITE), 14)
            }
            updatePetals(d)
        }
        top.addView(favBtn, lin(dp(52), dp(52)))
        col.addView(top, lin(MATCH, WRAP))

        // ---- hero: capa grande no centro, fundo liso e enfeitado (nada borrado), duas capas com a foto atrás
        val hero = FrameLayout(this)
        hero.background = gradient(g.primary, g.deep, dp(34).toFloat(), GradientDrawable.Orientation.TL_BR)
        hero.elevation = 0f
        hero.clipToOutline = true
        hero.addView(HeroDecor(this, g), FrameLayout.LayoutParams(MATCH, MATCH))

        val heroCol = LinearLayout(this)
        heroCol.orientation = LinearLayout.VERTICAL

        // palco da capa
        val cw = dp(180)
        val ch = dp(270)
        val stage = FrameLayout(this)
        stage.clipChildren = false
        // as duas capas dos lados continuam mostrando a foto (agora bem nítidas)
        for (side in intArrayOf(-1, 1)) {
            val ghost = CoverView(this, 24)
            ghost.bind(d, 400)
            ghost.alpha = 0.88f
            ghost.rotation = side * 9f
            ghost.scaleX = 0.86f
            ghost.scaleY = 0.86f
            ghost.translationX = side * dp(80).toFloat()
            stage.addView(ghost, FrameLayout.LayoutParams(cw, ch, Gravity.CENTER))
        }
        val ring = FrameLayout(this)
        ring.clipChildren = false
        ring.setPadding(dp(4), dp(4), dp(4), dp(4))
        ring.background = roundRect(Color.WHITE, dp(30).toFloat())
        ring.elevation = dp(6).toFloat()
        val cover = CoverView(this, 26)
        cover.bind(d, 900)
        ring.addView(cover, FrameLayout.LayoutParams(cw, ch))
        stage.addView(ring, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        heroCol.addView(stage, lin(MATCH, WRAP, t = 28, b = 6))

        val title = label(d.title, 26f, Color.WHITE, true, true)
        title.gravity = Gravity.CENTER
        title.maxLines = 3
        title.ellipsize = TextUtils.TruncateAt.END
        title.setShadowLayer(6f, 0f, 2f, Color.parseColor("#55000000"))
        heroCol.addView(title, lin(MATCH, WRAP, t = 14, l = 20, r = 20))
        if (d.original.isNotBlank()) {
            val og = label(d.original, 13f, Color.parseColor("#E6FFFFFF"))
            og.gravity = Gravity.CENTER
            heroCol.addView(og, lin(MATCH, WRAP, t = 2, l = 20, r = 20))
        }
        val subL = label(subtitle(d), 12.5f, Color.parseColor("#E6FFFFFF"))
        subL.gravity = Gravity.CENTER
        heroCol.addView(subL, lin(MATCH, WRAP, t = 6, l = 20, r = 20))

        // gêneros, status, país e onde assistir: centralizados, quebram de linha, nada é cortado
        val flow = FlowLayout(this)
        flow.center = true
        flow.hGap = dp(6)
        flow.vGap = dp(6)
        flow.addView(genrePill(g.label, g.icon, g.primary))
        for (tk in d.tags) {
            if (tk == d.genre) continue
            if (Genres.exists(tk)) {
                val tg = Genres.byKey(tk)
                flow.addView(genrePill(tg.label, tg.icon, tg.primary))
            } else if (OtherGenres.exists(tk)) {
                // "outros gêneros" também aparecem, com a própria cor e o próprio símbolo
                val og = OtherGenres.byKey(tk)
                flow.addView(genrePill(og.label, og.icon, og.color, og.colors))
            }
        }
        heroCol.addView(flow, lin(MATCH, WRAP, t = 12, l = 16, r = 16))

        // status, país e onde assistir: um cartãozinho à parte, em colunas, separado dos gêneros
        val infoCard = LinearLayout(this)
        infoCard.orientation = LinearLayout.HORIZONTAL
        // alinhado pelo topo: se um valor (ex.: "Coreia do Sul") quebrar em 2 linhas, os ícones e títulos continuam na mesma altura
        infoCard.gravity = Gravity.TOP
        infoCard.setPadding(dp(4), dp(14), dp(4), dp(14))
        infoCard.background = gradient(
            mixColor(g.primary, Color.WHITE, 0.26f), mixColor(g.primary, Color.WHITE, 0.10f),
            dp(26).toFloat(), GradientDrawable.Orientation.TOP_BOTTOM
        )
        // bolinha de ícone: círculo branco com anel, ícone colorido dentro
        fun bubble(icon: String, tint: Int, fill: Int): Pair<FrameLayout, IconView> {
            val b = FrameLayout(this)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(fill)
            bg.setStroke(dp(2), Color.WHITE)
            b.background = bg
            b.elevation = dp(2).toFloat()
            val iv = IconView(this, icon, tint, 18)
            b.addView(iv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            return Pair(b, iv)
        }
        val wide4 = d.platform.isNotBlank()
        val valSp = if (wide4) 13f else 14.5f
        fun infoCol(title: String, b: View, valueView: View): LinearLayout {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(b, lin(dp(38), dp(38)))
            val t = label(title, if (wide4) 9f else 10f, Color.parseColor("#D9FFFFFF"), true)
            t.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(110, 0, 0, 0))
            t.letterSpacing = if (wide4) 0.06f else 0.14f
            t.gravity = Gravity.CENTER
            c.addView(t, lin(WRAP, WRAP, t = 7))
            if (valueView is TextView) {
                valueView.maxLines = 2
                valueView.gravity = Gravity.CENTER
                valueView.setPadding(dp(4), 0, dp(4), 0)
                valueView.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(110, 0, 0, 0))
            }
            c.addView(valueView, lin(WRAP, WRAP, t = 2))
            return c
        }
        fun divider(): View {
            val v = View(this)
            v.setBackgroundColor(mixColor(g.primary, Color.WHITE, 0.45f))
            return v
        }
        val isFilm = d.kind == "filme"
        val (kBubble, _) = bubble(if (isFilm) "film" else "tv", g.primary, Color.WHITE)
        infoCard.addView(infoCol("TIPO", kBubble, label(if (isFilm) "Filme" else "Série", valSp, Color.WHITE, true, true)), lin(0, WRAP, 1f))
        infoCard.addView(divider(), lin(dp(1), dp(46)))
        val (stBubble, stIcon) = bubble("play", g.primary, Palette.pink)
        val statusText = label("", valSp, Color.WHITE, true, true)
        infoCard.addView(infoCol("STATUS", stBubble, statusText), lin(0, WRAP, 1f))
        infoCard.addView(divider(), lin(dp(1), dp(46)))
        val (coBubble, _) = bubble("flag", g.primary, Color.WHITE)
        infoCard.addView(infoCol("PAÍS", coBubble, label(d.country, valSp, Color.WHITE, true, true)), lin(0, WRAP, 1f))
        if (d.platform.isNotBlank()) {
            infoCard.addView(divider(), lin(dp(1), dp(46)))
            val (plBubble, _) = bubble("tv", g.primary, Color.WHITE)
            infoCard.addView(infoCol("ONDE ASSISTIR", plBubble, streamRow(d.platform, 10f, true)), lin(0, WRAP, 1f))
        }
        heroCol.addView(infoCard, lin(MATCH, WRAP, t = 14, l = 14, r = 14))

        // trilha sonora do dorama (só aparece se você escolheu um arquivo na edição)
        if (d.soundtrack.isNotEmpty() && File(d.soundtrack).exists()) {
            heroCol.addView(buildMusicBar(d, g), lin(MATCH, WRAP, t = 10, l = 14, r = 14))
        }

        // faixa da nota
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setPadding(dp(10), dp(8), dp(16), dp(8))
        strip.background = roundRect(mixColor(g.primary, Color.WHITE, 0.16f), dp(28).toFloat(), mixColor(g.primary, Color.WHITE, 0.36f), dp(1))
        val badge = scoreBadge(46, 19f)
        badge.setTextColor(g.primary)
        val bbg = GradientDrawable()
        bbg.shape = GradientDrawable.OVAL
        bbg.setColor(Color.WHITE)
        badge.background = bbg
        strip.addView(badge, lin(dp(46), dp(46), r = 12))
        val rcol = LinearLayout(this)
        rcol.orientation = LinearLayout.VERTICAL
        // sempre 5 corações à mostra: cheios, meio-cheios ou só o contorno
        val rv = RatingView(this, 22, false)
        rv.color = Color.WHITE
        rcol.addView(rv)
        val rvNote = label("", 13f, Color.parseColor("#F2FFFFFF"), true)
        rvNote.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(110, 0, 0, 0))
        rcol.addView(rvNote, lin(WRAP, WRAP, t = 4))
        strip.addView(rcol, lin(0, WRAP, 1f))
        heroCol.addView(strip, lin(MATCH, WRAP, t = 16, l = 14, r = 14, b = 8))
        val tagLine = label(g.lineFor(d.score), 14f, Color.WHITE)
        tagLine.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(120, 0, 0, 0))
        tagLine.gravity = Gravity.CENTER
        heroCol.addView(tagLine, lin(MATCH, WRAP, l = 16, r = 16, b = 16))

        hero.addView(heroCol, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(hero, lin(MATCH, WRAP, t = 10))

        updaters.add {
            val st = Statuses.byKey(d.status)
            statusText.text = st.label
            val sbg = GradientDrawable()
            sbg.shape = GradientDrawable.OVAL
            sbg.setColor(st.color)
            sbg.setStroke(dp(2), Color.WHITE)
            stBubble.background = sbg
            stIcon.tint = Color.WHITE
            stIcon.setIcon(st.icon)
            val newText = if (d.score <= 0) "-" else d.score.toString()
            if (badge.text.toString() != newText) {
                badge.text = newText
                badge.pop(1.6f)
            }
            rv.score = d.score
            rvNote.text = if (d.score <= 0) "ainda sem nota" else "nota " + d.score + " de 10"
            tagLine.text = g.lineFor(d.score)
            tagLine.visibility = if (tagLine.text.isBlank()) View.GONE else View.VISIBLE
        }

        // ---- assistir: abre a aba só deste dorama
        val watchRow = LinearLayout(this)
        watchRow.orientation = LinearLayout.HORIZONTAL
        watchRow.gravity = Gravity.CENTER
        watchRow.clipChildren = false
        val wcard = LinearLayout(this)
        wcard.orientation = LinearLayout.HORIZONTAL
        wcard.gravity = Gravity.CENTER_VERTICAL
        wcard.setPadding(dp(14), dp(14), dp(18), dp(14))
        wcard.background = gradient(g.primary, g.deep, dp(26).toFloat(), GradientDrawable.Orientation.LEFT_RIGHT)
        wcard.elevation = 0f
        val pbub = FrameLayout(this)
        pbub.background = roundRect(mixColor(g.primary, Color.WHITE, 0.24f), dp(18).toFloat())
        pbub.addView(IconView(this, "play", Color.WHITE, 24), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        wcard.addView(pbub, lin(dp(50), dp(50), r = 14))
        val wtx = LinearLayout(this)
        wtx.orientation = LinearLayout.VERTICAL
        val wsmall = label("", 10.5f, Color.parseColor("#E6FFFFFF"), true)
        wsmall.letterSpacing = 0.08f
        val wbig = label("", 17f, Color.WHITE, true, true)
        wtx.addView(wsmall)
        wtx.addView(wbig, lin(WRAP, WRAP, t = 1))
        wcard.addView(wtx, lin(0, WRAP, 1f))
        wcard.addView(IconView(this, "forward", Color.WHITE, 20))
        wcard.pressable(0.97f)
        wcard.setOnClickListener { openWatch(d) }
        watchRow.addView(wcard, lin(0, WRAP, 1f))
        col.addView(watchRow, lin(MATCH, WRAP, t = 14))
        updaters.add {
            if (d.lastUrl.isBlank() && watchedEps(d) == 0) {
                wsmall.text = "PRONTO PARA COMEÇAR"
                wbig.text = "Assistir agora"
            } else {
                wsmall.text = progressText(d).uppercase(Locale("pt", "BR"))
                wbig.text = "Continuar assistindo"
            }
        }

        // ---- tiles
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        tiles.clipChildren = false
        tiles.clipToPadding = false
        val tw = totalEps(d)
        val tSeasons = tile("tv", seasonCount(d).toString(), "temporadas", g.primary)
        val tEps = tile("play", "", "episódios", g.primary)
        val tMin = tile("clock", if (d.epMinutes > 0) d.epMinutes.toString() + " min" else "-", "por episódio", g.primary)
        val tRew = tile("replay", "", "reassistido", g.primary)
        tiles.addView(tSeasons.first, lin(0, WRAP, 1f, r = 6))
        tiles.addView(tEps.first, lin(0, WRAP, 1f, r = 6))
        tiles.addView(tMin.first, lin(0, WRAP, 1f, r = 6))
        tiles.addView(tRew.first, lin(0, WRAP, 1f))
        col.addView(tiles, lin(MATCH, WRAP, t = 14))
        updaters.add {
            val tw2 = totalEps(d)
            val txt = if (tw2 > 0 && allTotalsKnown(d)) watchedEps(d).toString() + "/" + tw2 else watchedEps(d).toString()
            if (tEps.second.text.toString() != txt) {
                tEps.second.text = txt
                tEps.second.pop(1.3f)
            }
            val rt = d.rewatch.toString() + "x"
            if (tRew.second.text.toString() != rt) {
                tRew.second.text = rt
                tRew.second.pop(1.3f)
            }
        }

        // ---- progresso por temporada
        val pc = card(16, 22)
        pc.addView(sectionTitle("Meu progresso", "play", g.primary))

        val oh = LinearLayout(this)
        oh.orientation = LinearLayout.HORIZONTAL
        oh.gravity = Gravity.BOTTOM
        val overall = label("", 17f, g.dark, true, true)
        oh.addView(overall, lin(0, WRAP, 1f))
        val pct = label("", 15f, g.primary, true)
        oh.addView(pct, lin(WRAP, WRAP))
        pc.addView(oh, lin(MATCH, WRAP, t = 14))
        val obar = SoftBar(this)
        obar.barColor = g.primary
        pc.addView(obar, lin(MATCH, dp(12), t = 8))

        val seasonBars = ArrayList<SoftBar>()
        val seasonTxt = ArrayList<TextView>()
        val seasonLbl = ArrayList<TextView>()
        for (i in d.seasonEps.indices) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(14), dp(10), dp(10), dp(10))
            box.background = roundRect(g.soft, dp(18).toFloat())

            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(label("Temporada " + (i + 1), 13.5f, g.dark, true), lin(WRAP, WRAP))
            val now = label("atual", 10.5f, Color.WHITE, true)
            now.setPadding(dp(8), dp(2), dp(8), dp(2))
            now.background = roundRect(g.primary, dp(10).toFloat())
            now.visibility = View.GONE
            seasonLbl.add(now)
            top.addView(now, lin(WRAP, WRAP, l = 8))
            top.addView(View(this), lin(0, 1, 1f))
            val cnt = label("", 13f, g.dark, true)
            seasonTxt.add(cnt)
            top.addView(cnt, lin(WRAP, WRAP, r = 4))
            box.addView(top, lin(MATCH, WRAP))

            val bot = LinearLayout(this)
            bot.orientation = LinearLayout.HORIZONTAL
            bot.gravity = Gravity.CENTER_VERTICAL
            val b = SoftBar(this)
            b.barColor = g.primary
            b.trackColor = Palette.card
            seasonBars.add(b)
            bot.addView(b, lin(0, dp(9), 1f, r = 12))
            bot.addView(roundBtn("minus", g.primary, false, 14) {
                Store.adjust(d, i, -1)
                sync()
                refreshAll()
            }, lin(dp(32), dp(32)))
            val addB = roundBtn("add", g.primary, true, 14) {}
            addB.setOnClickListener {
                val fin = Store.adjust(d, i, 1)
                sync()
                refreshAll()
                petals.burstFrom(addB, listOf(g.icon, "heart", "sparkle"), listOf(g.primary, Palette.pink), 8)
                if (fin) celebrate(d, g, addB)
            }
            bot.addView(addB, lin(dp(32), dp(32), l = 8))
            box.addView(bot, lin(MATCH, WRAP, t = 8))
            pc.addView(box, lin(MATCH, WRAP, t = if (i == 0) 16 else 10))
        }

        val rw = LinearLayout(this)
        rw.orientation = LinearLayout.HORIZONTAL
        rw.gravity = Gravity.CENTER_VERTICAL
        rw.addView(IconView(this, "replay", g.primary, 16))
        rw.addView(label("Vezes que reassisti", 13f, Palette.text, true), lin(0, WRAP, 1f, l = 8))
        val rewTxt = label("", 15f, g.dark, true)
        rw.addView(roundBtn("minus", g.primary, false, 14) {
            if (d.rewatch > 0) {
                d.rewatch -= 1
                Store.save(d)
                sync()
                refreshAll()
            }
        }, lin(dp(30), dp(30)))
        rewTxt.gravity = Gravity.CENTER
        rw.addView(rewTxt, lin(dp(34), WRAP))
        val rewAdd = roundBtn("add", g.primary, true, 14) {}
        rewAdd.setOnClickListener {
            d.rewatch += 1
            Store.save(d)
            sync()
            refreshAll()
            petals.burstFrom(rewAdd, listOf("replay", "heart"), listOf(g.primary, Palette.pink), 8)
        }
        rw.addView(rewAdd, lin(dp(30), dp(30)))
        val dv = View(this)
        dv.setBackgroundColor(Palette.line)
        pc.addView(dv, lin(MATCH, dp(1), t = 16))
        pc.addView(rw, lin(MATCH, WRAP, t = 14))
        col.addView(pc, lin(MATCH, WRAP, t = 12))

        var firstProgress = true
        updaters.add {
            val total = totalEps(d)
            val w = watchedEps(d)
            val txt = if (total > 0 && allTotalsKnown(d)) "$w de $total episódios" else "$w episódios assistidos"
            if (overall.text.toString() != txt) {
                overall.text = txt
                if (!firstProgress) overall.pop(1.06f)
            }
            val frac = progressOf(d)
            val pt = (frac * 100f).toInt().toString() + "%"
            if (pct.text.toString() != pt) {
                pct.text = pt
                if (!firstProgress) pct.pop(1.2f)
            }
            if (firstProgress) obar.animateTo(frac, 0f, 350L, 800L) else obar.animateTo(frac)
            val cur = activeSeason(d)
            for (i in seasonBars.indices) {
                val isCur = i == cur && seasonBars.size > 1
                seasonLbl[i].visibility = if (isCur) View.VISIBLE else View.GONE
                val t = d.seasonEps[i]
                val wi = d.watched[i]
                val f = if (t > 0) wi.toFloat() / t.toFloat() else 0f
                if (firstProgress) seasonBars[i].animateTo(f, 0f, 450L + i * 80L, 700L) else seasonBars[i].animateTo(f)
                val ct = if (t > 0) "$wi/$t" else "$wi"
                if (seasonTxt[i].text.toString() != ct) {
                    seasonTxt[i].text = ct
                    if (!firstProgress) seasonTxt[i].pop(1.3f)
                }
            }
            val rt = d.rewatch.toString()
            if (rewTxt.text.toString() != rt) {
                rewTxt.text = rt
                if (!firstProgress) rewTxt.pop(1.4f)
            }
            firstProgress = false
        }

        // ---- sinopse
        if (d.synopsis.isNotBlank()) {
            val sc = card(14, 22)
            sc.addView(sectionTitle("Sinopse", "book", g.primary))
            val syn = label(d.synopsis, 14f, Palette.text)
            syn.maxLines = 4
            syn.ellipsize = TextUtils.TruncateAt.END
            sc.addView(syn, lin(MATCH, WRAP, t = 8))
            if (d.synopsis.length > 140) {
                var expanded = false
                val more = label("Ler mais", 12f, g.primary, true)
                more.setOnClickListener {
                    expanded = !expanded
                    android.transition.TransitionManager.beginDelayedTransition(
                        col, android.transition.AutoTransition().setDuration(280)
                    )
                    syn.maxLines = if (expanded) 100 else 4
                    more.text = if (expanded) "Ler menos" else "Ler mais"
                }
                more.pressable(0.92f)
                sc.addView(more, lin(WRAP, WRAP, t = 6))
            }
            col.addView(sc, lin(MATCH, WRAP, t = 12))
        }

        // ---- informações
        val ic = card(14, 22)
        col.addView(ic, lin(MATCH, WRAP, t = 12))

        // ---- elenco (fotos com nome) e casal favorito
        buildCastCard(d, g)?.let { col.addView(it, lin(MATCH, WRAP, t = 12)) }
        buildCoupleCard(d, g)?.let { col.addView(it, lin(MATCH, WRAP, t = 12)) }
        var firstInfo = true
        updaters.add {
            while (ic.childCount > 0) ic.removeViewAt(0)
            ic.addView(sectionTitle("Informações", "tag", g.primary))
            if (d.platform.isNotBlank()) ic.addView(infoRowView("tv", "Onde assistir", streamRow(d.platform, 12f), g.primary))
            if (d.year.isNotBlank()) ic.addView(infoRow("calendar", "Ano de lançamento", d.year, g.primary))
            if (d.startDate > 0L) ic.addView(infoRow("play", "Comecei em", fmt(d.startDate), g.primary))
            if (d.endDate > 0L) ic.addView(infoRow("check", "Terminei em", fmt(d.endDate), g.primary))
            ic.addView(infoRow("calendar", "Adicionado em", fmt(d.addedAt), g.primary))
            if (!firstInfo) {
                val last = ic.getChildAt(ic.childCount - 1)
                for (k in 1 until ic.childCount) ic.getChildAt(k).fadeScaleIn(0L, 260L)
                last.alpha = 1f
            }
            firstInfo = false
        }

        // ---- resenha
        if (d.notes.isNotBlank()) {
            val nc = card(14, 22)
            nc.addView(sectionTitle("Minha resenha", "edit", g.primary))
            nc.addView(label(d.notes, 14f, Palette.text), lin(MATCH, WRAP, t = 8))
            col.addView(nc, lin(MATCH, WRAP, t = 12))
        }

        // ---- excluir
        val del = pill("Excluir dorama", Color.parseColor("#FFE0E6"), Color.parseColor("#C2185B"), 14f, "delete")
        del.setPadding(dp(18), dp(12), dp(18), dp(12))
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Excluir dorama?")
                .setMessage("\"" + d.title + "\" será removido da sua estante.")
                .setPositiveButton("Excluir") { _, _ ->
                    sv.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(260).withEndAction {
                        Store.delete(d.id)
                        sync()
                        finish()
                    }.start()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        col.addView(del, lin(MATCH, WRAP, t = 18))

        root.addView(fxTop, FrameLayout.LayoutParams(MATCH, MATCH))

        refreshAll()
        updatePetals(d)
        val oldRoot = rootView
        rootView = root
        host.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        if (oldRoot != null && !entrance) {
            // troca suave: a tela nova aparece por cima e a antiga some, sem corte nem piscada
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(240).start()
            oldRoot.animate().alpha(0f).setDuration(240).withEndAction { host.removeView(oldRoot) }.start()
        } else if (oldRoot != null) {
            host.removeView(oldRoot)
        }
        if (host.parent == null) setContentView(host)
        ThemeMode.refresh(this)
        window.statusBarColor = g.soft
        scroll = sv
        if (keep > 0) sv.post { sv.scrollTo(0, keep) }

        if (entrance) {
            hero.fadeScaleIn(0L, 450L)
            ring.pop(1.25f)
            for (i in 1 until col.childCount) col.getChildAt(i).riseIn(80L + i * 55L, 20, 400L)
        }
    }

    /** Etiqueta de gênero na cor dele (principal ou outro gênero), com borda branca para destacar sobre o fundo. */
    private fun genrePill(label: String, icon: String, color: Int, colors: List<Int> = emptyList()): TextView =
        vividPill(label, icon, if (colors.size > 1) colors else listOf(color), 11.5f)

    private fun openWatch(d: Drama) {
        val i = Intent(this, WatchActivity::class.java)
        i.putExtra("id", d.id)
        startActivity(i)
        overridePendingTransition(R.anim.screen_in, R.anim.screen_out_back)
    }

    private fun updatePetals(d: Drama) {
        val a = Atmosphere.ofDrama(d)
        petals.setTheme(a.icons, a.tints)
    }

    private fun celebrate(d: Drama, g: Genre, from: View) {
        val green = Color.parseColor("#5CC6A0")
        petals.burstFrom(from, listOf("check", "heart", "star", "sparkle", "blossom"), listOf(g.primary, Palette.pink, green, Color.parseColor("#FFB84D")), 34)
        softToast("Parabéns, você terminou!", green, "check")
        updatePetals(d)
    }

    private fun tile(icon: String, value: String, name: String, color: Int): Pair<LinearLayout, TextView> {
        val b = card(10, 18)
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.addView(IconView(this, icon, color, 18))
        val v = label(value, 14f, Palette.text, true, true)
        v.maxLines = 1
        b.addView(v, lin(WRAP, WRAP, t = 4))
        val n = label(name, 10f, Palette.muted)
        n.maxLines = 1
        b.addView(n, lin(WRAP, WRAP, t = 1))
        b.pressable(0.94f)
        return Pair(b, v)
    }

    /** Elenco: cartão enfeitado na cor do gênero, com um "crachá" por pessoa (foto grande, número e nome). */
    private fun buildCastCard(d: Drama, g: Genre): View? {
        val people = d.castPeople.filter { it.name.isNotBlank() }
        if (people.isEmpty()) return null

        val root = FrameLayout(this)
        root.clipChildren = false
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(g.soft, mixColor(g.soft, g.primary, 0.20f)))
        bg.cornerRadius = dp(28).toFloat()
        bg.setStroke(dp(1), mixColor(g.primary, Color.WHITE, 0.45f))
        root.background = bg
        val clipper = FrameLayout(this)
        clipper.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(28).toFloat())
            }
        }
        clipper.clipToOutline = true
        clipper.addView(CoupleDecor(this, g, true), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(clipper, FrameLayout.LayoutParams(MATCH, MATCH))

        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(0, dp(18), 0, dp(18))
        root.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))

        // cabeçalho: bolinha com ícone, título e quantidade
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val dot = FrameLayout(this)
        val dbg = GradientDrawable()
        dbg.shape = GradientDrawable.OVAL
        dbg.setColor(g.primary)
        dbg.setStroke(dp(2), Color.WHITE)
        dot.background = dbg
        dot.addView(IconView(this, g.icon, Color.WHITE, 18), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        head.addView(dot, lin(dp(38), dp(38), r = 10))
        // "Elenco" e a quantidade de pessoas ficam na MESMA linha; a frase do gênero desce para baixo
        head.addView(label("Elenco", 21f, g.dark, true, true), lin(0, WRAP, 1f))
        head.addView(
            vividPill(if (people.size == 1) "1 pessoa" else people.size.toString() + " pessoas", "sparkle", listOf(g.primary), 11.5f),
            lin(WRAP, WRAP)
        )
        c.addView(head, lin(MATCH, WRAP, l = 18, r = 18))

        val hs = FadeScrollView(this, dp(30).toFloat())
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        hs.setPadding(dp(18), dp(16), dp(8), dp(2))
        // as pontas da fileira somem suavemente (degradê próprio, sem faixa) enquanto você arrasta
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        swipeBlocks.add(hs)
        val row = LinearLayout(this)
        val castItems = ArrayList<View>()
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, p) in people.withIndex()) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(dp(4), dp(14), dp(4), dp(12))
            val ph = FrameLayout(this)
            ph.clipChildren = false
            ph.addView(avatarView(p.photo, 84, g.primary, g.soft, g.primary), FrameLayout.LayoutParams(dp(84), dp(84)))
            item.addView(ph, lin(dp(84), dp(84)))

            val nm = label(p.name, 13f, Palette.text, true)
            nm.gravity = Gravity.CENTER
            nm.maxLines = 2
            nm.ellipsize = TextUtils.TruncateAt.END
            item.addView(nm, lin(MATCH, WRAP, t = 9))
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER
            for (k in 0 until 3) line.addView(IconView(this, if (k == 1) g.icon else "sparkle", g.primary, if (k == 1) 12 else 9), lin(WRAP, WRAP, l = 2, r = 2))
            item.addView(line, lin(WRAP, WRAP, t = 6))
            item.fadeScaleIn(minOf(i, 6) * 60L, 300L)
            castItems.add(item)
            row.addView(item, lin(dp(116), WRAP, r = 8))
        }
        hs.addView(row)
        // os 3 primeiros atores ficam centralizados no cartão (cada cartinha se ajusta à largura da tela)
        var laidW = -1
        hs.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w <= 0 || w == laidW) return@addOnLayoutChangeListener
            laidW = w
            val gap = dp(8)
            val k = minOf(castItems.size, 3)
            val itemW = minOf(dp(116), (w - dp(2 * 14) - (k - 1) * gap) / k)
            for (it in castItems) {
                val lp = it.layoutParams as LinearLayout.LayoutParams
                lp.width = itemW
                lp.rightMargin = gap
                it.layoutParams = lp
            }
            val side = ((w - k * itemW - (k - 1) * gap) / 2).coerceAtLeast(dp(8))
            v.setPadding(side, v.paddingTop, side, v.paddingBottom)
        }
        c.addView(hs, lin(MATCH, WRAP))
        return root
    }

    /** Casal favorito: polaroid grande e centralizada (veja coupleCard em FlairUi.kt). */
    private fun buildCoupleCard(d: Drama, g: Genre): View? = coupleCard(d, g)

    private fun infoRowView(icon: String, name: String, value: View, color: Int): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(10), 0, 0)
        r.addView(IconView(this, icon, color, 18), lin(WRAP, WRAP, t = 2))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(label(name, 11f, Palette.muted, true))
        c.addView(value, lin(MATCH, WRAP, t = 4))
        r.addView(c, lin(0, WRAP, 1f, l = 10))
        return r
    }

    private fun infoRow(icon: String, name: String, value: String, color: Int): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(10), 0, 0)
        r.addView(IconView(this, icon, color, 18), lin(WRAP, WRAP, t = 2))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(label(name, 11f, Palette.muted, true))
        c.addView(label(value, 14f, Palette.text), lin(MATCH, WRAP, t = 1))
        r.addView(c, lin(0, WRAP, 1f, l = 10))
        return r
    }
}
