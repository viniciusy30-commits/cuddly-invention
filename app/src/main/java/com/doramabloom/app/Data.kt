package com.doramabloom.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Genre(
    val key: String,
    val label: String,
    val icon: String,
    /** Cor escolhida para o gênero (a de verdade; no modo escuro, veja [primary]). */
    val base: Int,
    val softL: Int,
    val darkL: Int,
    val petals: List<String>,
    /** Frase das notas 8 a 10 (a única de cada gênero). */
    val tagline: String,
    val custom: Boolean = false,
    /** Frase das notas 1 a 4. */
    val lowLine: String = DEFAULT_LOW_LINE,
    /** Frase das notas 5 a 7. */
    val midLine: String = DEFAULT_MID_LINE
) {
    /** Cor principal; no modo escuro as muito escuras (preto, cinza) são clareadas para aparecer. */
    val primary: Int
        get() {
            if (!Palette.dark) return base
            val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
            return if (lum < 0.30f) mixColor(base, Color.WHITE, 0.45f) else base
        }

    /** Tom de fundo suave: claro no modo claro, escuro com um toque da cor no modo escuro. */
    val soft: Int
        get() = if (Palette.dark) mixColor(Color.parseColor("#251B24"), primary, 0.22f) else softL

    /** Cor de texto/ícone sobre o tom suave: escura no modo claro, clara no modo escuro. */
    val dark: Int
        get() = if (Palette.dark) mixColor(primary, Color.WHITE, 0.55f) else darkL

    /** Tom escuro de verdade (para fundos com texto branco por cima), igual nos dois modos. */
    val deep: Int get() = darkL
}

const val DEFAULT_LOW_LINE = "Terminei por educação"
const val DEFAULT_MID_LINE = "Tá ok, mas cadê o plot? \uD83D\uDDFF"

/** Frase do gênero de acordo com a nota do dorama: sem nota nenhuma, 1-4 educação, 5-7 meme, 8-10 a frase do gênero. */
fun Genre.lineFor(score: Int): String = when {
    score <= 0 -> ""
    score <= 4 -> lowLine.ifBlank { DEFAULT_LOW_LINE }
    score <= 7 -> midLine.ifBlank { DEFAULT_MID_LINE }
    else -> tagline
}

/** Mistura duas cores (f = 0 fica em a, f = 1 fica em b). */
fun mixColor(a: Int, b: Int, f: Float): Int {
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), bl.coerceIn(0, 255))
}

object Genres {
    private fun c(s: String): Int = Color.parseColor(s)

    private val factory: List<Genre> = listOf(
        Genre("romance", "Romance", "heart", c("#FF6B9D"), c("#FFE4EE"), c("#A3305B"),
            listOf("heart", "petal", "ring"), "Para suspirar abraçada na almofada"),
        Genre("comedia", "Comédia", "smile", c("#FFB84D"), c("#FFF3D6"), c("#8A5A00"),
            listOf("smile", "balloon", "star"), "Risadinhas garantidas"),
        Genre("acao", "Ação", "bolt", c("#FF7A6B"), c("#FFE5E0"), c("#9C2F22"),
            listOf("bolt", "flame", "shield"), "Coração acelerado e muita adrenalina"),
        Genre("terror", "Terror", "ghost", c("#9B7EDE"), c("#EDE6FA"), c("#4B2E8F"),
            listOf("ghost", "bat", "tomb"), "Luz acesa e coberta até o nariz"),
        Genre("fantasia", "Fantasia", "sparkle", c("#6FA8FF"), c("#E3EEFF"), c("#1F4F9C"),
            listOf("sparkle", "butterfly", "wand"), "Magia, brilho e mundos encantados"),
        Genre("historico", "Histórico", "pagoda", c("#D6536D"), c("#FBE3E8"), c("#8E1F3A"),
            listOf("pagoda", "lantern", "fan"), "Hanboks, palácios e intrigas da corte"),
        Genre("misterio", "Mistério", "search", c("#5FB3A8"), c("#DFF4F1"), c("#1F6B62"),
            listOf("search", "key", "lock"), "Quem será o culpado?"),
        Genre("escolar", "Escolar", "school", c("#7FD1AE"), c("#E1F7EC"), c("#226B4B"),
            listOf("school", "book", "pencil"), "Uniformes, amizades e primeiros amores"),
        Genre("drama", "Drama", "drop", c("#C38BD8"), c("#F6E6FB"), c("#6E2F86"),
            listOf("drop", "cloud", "heartbreak"), "Lencinho por perto, vai ter choro"),
        Genre("suspense", "Suspense", "eye", c("#4F6D9A"), c("#E3EAF5"), c("#24395A"),
            listOf("eye", "moon", "clock"), "Segura a respiração, a trama não dá trégua"),
        Genre("medico", "Médico", "cross", c("#3FB6C9"), c("#DDF4F8"), c("#17657A"),
            listOf("cross", "pill", "syringe"), "Plantões, jalecos e corações em tratamento"),
        Genre("familia", "Família", "home", c("#F29B5C"), c("#FFEBDC"), c("#96501A"),
            listOf("home", "cake", "gift"), "Mesa farta, abraço apertado e muito afeto"),
        Genre("musical", "Musical", "music", c("#E36BC4"), c("#FDE4F6"), c("#8A2A72"),
            listOf("music", "mic", "headphones"), "Melodias que grudam no coração"),
        Genre("esporte", "Esporte", "trophy", c("#5DB56E"), c("#E1F5E5"), c("#226B35"),
            listOf("trophy", "medal", "ball"), "Suor, garra e superação em campo"),
        Genre("realeza", "Realeza", "crown", c("#E0A93B"), c("#FFF1CC"), c("#7A5A00"),
            listOf("crown", "gem", "castle"), "Coroas, tronos e segredos do palácio"),
        Genre("scifi", "Ficção científica", "rocket", c("#6C63FF"), c("#E7E5FF"), c("#2E2A99"),
            listOf("rocket", "planet", "ufo"), "Futuro, viagens no tempo e mistérios do espaço"),
        Genre("vida", "Vida real", "coffee", c("#C79A7B"), c("#F6E9DF"), c("#6B4630"),
            listOf("coffee", "leaf", "sun"), "Cotidiano gostoso, café quentinho e paz"),
        Genre("vinganca", "Vingança", "skull", c("#B03A5B"), c("#F8DDE5"), c("#5E1128"),
            listOf("skull", "dagger", "hourglass"), "Frieza, planos e a hora do acerto de contas")
    )

    private var edits: Map<String, Genre> = emptyMap()
    private var builtin: List<Genre> = factory
    private var extra: List<Genre> = emptyList()

    /** Edições que você fez nos gêneros de fábrica (chave -> gênero editado). */
    fun setEdits(m: Map<String, Genre>) {
        edits = m
        builtin = factory.map { edits[it.key] ?: it }
    }

    fun edited(): Map<String, Genre> = edits

    fun isEdited(k: String): Boolean = edits.containsKey(k)

    fun factoryOf(k: String): Genre? = factory.firstOrNull { it.key == k }

    /** Gênero de fábrica com nome, ícone, cor e frase novos (mantém o resto se a cor não mudou). */
    fun editBuiltin(base: Genre, label: String, icon: String, primary: Int, tagline: String, low: String = "", mid: String = ""): Genre {
        val orig = factoryOf(base.key) ?: base
        val tag = if (tagline.isBlank()) orig.tagline else tagline
        val lo = if (low.isBlank()) DEFAULT_LOW_LINE else low
        val mi = if (mid.isBlank()) DEFAULT_MID_LINE else mid
        val pet = if (icon == orig.icon) orig.petals else (listOf(icon) + orig.petals.drop(1)).distinct()
        return if (primary == orig.base) {
            orig.copy(label = label, icon = icon, tagline = tag, petals = pet, lowLine = lo, midLine = mi)
        } else {
            orig.copy(
                label = label, icon = icon, base = primary,
                softL = mixColor(primary, Color.WHITE, 0.84f),
                darkL = mixColor(primary, Color.BLACK, 0.42f),
                tagline = tag, petals = pet, lowLine = lo, midLine = mi
            )
        }
    }

    /** Os gêneros de fábrica (com suas edições) e depois os que você criou. */
    val all: List<Genre> get() = builtin + extra

    fun custom(): List<Genre> = extra

    fun setCustom(l: List<Genre>) {
        extra = l
    }

    fun exists(k: String): Boolean = all.any { it.key == k }

    fun byKey(k: String): Genre = all.firstOrNull { it.key == k } ?: builtin[0]

    /** Monta um gênero novo a partir de nome, ícone e cor; o resto (tons claro e escuro) sai da cor. */
    fun makeCustom(key: String, label: String, icon: String, primary: Int, tagline: String, low: String = "", mid: String = ""): Genre =
        Genre(
            key, label, icon, primary,
            mixColor(primary, Color.WHITE, 0.84f),
            mixColor(primary, Color.BLACK, 0.42f),
            listOf(icon, "sparkle", "petal").distinct(),
            if (tagline.isBlank()) "Seu gênero $label" else tagline,
            true,
            if (low.isBlank()) DEFAULT_LOW_LINE else low,
            if (mid.isBlank()) DEFAULT_MID_LINE else mid
        )
}

/**
 * "Outros gêneros": etiquetas só para classificar e filtrar.
 * Não têm tema próprio (cor, símbolos e pétalas continuam sendo os do gênero principal),
 * então escolher um deles nunca muda a atmosfera das telas.
 * Ficam guardados no mesmo campo "tags" do dorama, com chave começando em "x_".
 */
class OtherGenre(
    val key: String,
    val label: String,
    val icon: String,
    val color: Int,
    val custom: Boolean = false,
    /** Cores extras para mesclar (a principal é [color]). */
    val extra: List<Int> = emptyList()
) {
    /** Todas as cores, sem repetir (a primeira é a principal). */
    val colors: List<Int> get() = (listOf(color) + extra).distinct()
    val soft: Int get() = mixColor(color, Color.WHITE, 0.84f)
    val dark: Int get() = mixColor(color, Color.BLACK, 0.42f)
}

object OtherGenres {
    private fun c(s: String): Int = Color.parseColor(s)

    /** Cada um tem cor e símbolo só dele (nenhum repete os símbolos dos gêneros com tema). */
    val factory: List<OtherGenre> = listOf(
        OtherGenre("x_policial", "Policial", "shield", c("#5B7FD9")),
        OtherGenre("x_juridico", "Jurídico", "scale", c("#8A6D5A")),
        OtherGenre("x_politico", "Político", "flag", c("#E8505B")),
        OtherGenre("x_sobrenatural", "Sobrenatural", "moon", c("#7B6CF0")),
        OtherGenre("x_zumbi", "Zumbi", "tomb", c("#7A9A5A")),
        OtherGenre("x_vampiro", "Vampiro", "bat", c("#9E1F4D")),
        OtherGenre("x_viagem_tempo", "Viagem no tempo", "hourglass", c("#E0A93B")),
        OtherGenre("x_reencarnacao", "Reencarnação", "replay", c("#2BA6A0")),
        OtherGenre("x_superpoderes", "Superpoderes", "flame", c("#FF7A3D")),
        OtherGenre("x_distopia", "Distopia", "globe", c("#4A8FA8")),
        OtherGenre("x_militar", "Militar", "medal", c("#6B7A3A")),
        OtherGenre("x_trabalho", "Trabalho", "chart", c("#3E6FD8")),
        OtherGenre("x_chaebol", "Chaebol", "gem", c("#D45FA0")),
        OtherGenre("x_casamento", "Casamento por contrato", "ring", c("#FF8FB7")),
        OtherGenre("x_amizade", "Amizade", "person", c("#F29B5C")),
        OtherGenre("x_lgbt", "LGBTQ+", "rainbow", c("#E8505B"), false,
            listOf(c("#FF7A3D"), c("#F5D547"), c("#5DB56E"), c("#4F6D9A"), c("#A068E0"))),
        OtherGenre("x_slice", "Slice of life", "leaf", c("#6FBF4A")),
        OtherGenre("x_culinario", "Culinário", "cake", c("#D98B5F")),
        OtherGenre("x_moda", "Moda", "butterfly", c("#E36BC4")),
        OtherGenre("x_idol", "Idols", "mic", c("#9B5DE5")),
        OtherGenre("x_superacao", "Superação", "sun", c("#F2A93B"))
    )

    private var edits: Map<String, OtherGenre> = emptyMap()
    private var builtin: List<OtherGenre> = factory
    private var extraList: List<OtherGenre> = emptyList()
    private var hidden: Set<String> = emptySet()

    /** Gêneros de fábrica que você apagou. */
    fun hiddenKeys(): Set<String> = hidden

    fun setHidden(s: Set<String>) {
        hidden = s
    }

    fun setEdits(m: Map<String, OtherGenre>) {
        edits = m
        builtin = factory.map { edits[it.key] ?: it }
    }

    fun edited(): Map<String, OtherGenre> = edits

    fun isEdited(k: String): Boolean = edits.containsKey(k)

    fun factoryOf(k: String): OtherGenre? = factory.firstOrNull { it.key == k }

    /** De fábrica (com suas edições) e depois os que você criou. */
    val all: List<OtherGenre> get() = builtin.filter { !hidden.contains(it.key) } + extraList

    fun custom(): List<OtherGenre> = extraList

    fun setCustom(l: List<OtherGenre>) {
        extraList = l
    }

    fun makeCustom(key: String, label: String, icon: String, color: Int, extra: List<Int>): OtherGenre =
        OtherGenre(key, label, icon, color, true, extra.filter { it != color }.distinct())

    /** Edita um gênero de fábrica (nome, símbolo e cores); a chave continua a mesma. */
    fun editBuiltin(base: OtherGenre, label: String, icon: String, color: Int, extra: List<Int>): OtherGenre =
        OtherGenre(base.key, label, icon, color, false, extra.filter { it != color }.distinct())

    fun exists(k: String): Boolean = all.any { it.key == k }

    fun byKey(k: String): OtherGenre = all.first { it.key == k }
}

/** Três tons do gênero para as pétalas: uma mistura rica em vez de uma cor só. */
fun genreTints(g: Genre): List<Int> = listOf(
    g.primary,
    mixColor(g.primary, g.dark, 0.32f),
    mixColor(g.primary, Color.WHITE, 0.28f)
)

data class Status(val key: String, val label: String, val icon: String, val color: Int)

object Statuses {
    val all: List<Status> = listOf(
        Status("assistindo", "Assistindo", "play", Color.parseColor("#6FA8FF")),
        Status("quero", "Quero ver", "bookmark", Color.parseColor("#C38BD8")),
        Status("concluido", "Concluído", "check", Color.parseColor("#5CC6A0")),
        Status("pausado", "Pausado", "pause", Color.parseColor("#FFB84D")),
        Status("dropado", "Dropado", "close", Color.parseColor("#B7A3AE"))
    )

    fun byKey(k: String): Status = all.firstOrNull { it.key == k } ?: all[1]
}

val countries: List<String> = listOf(
    "Coreia do Sul", "Japão", "China", "Tailândia", "Taiwan", "Outro"
)

/** Ator ou atriz do elenco, com foto opcional (caminho do arquivo). */
class CastPerson(var name: String, var photo: String = "")

data class Drama(
    var id: Long,
    var title: String,
    var original: String,
    var synopsis: String,
    var country: String,
    var genre: String,
    var tags: List<String>,
    var status: String,
    var score: Int,
    var seasonEps: List<Int>,
    var watched: List<Int>,
    var epMinutes: Int,
    var year: String,
    var platform: String,
    var cast: String,
    var couple: String,
    var startDate: Long,
    var endDate: Long,
    var rewatch: Int,
    var notes: String,
    var favorite: Boolean,
    var cover: String,
    var addedAt: Long,
    var link: String = "",
    var lastUrl: String = "",
    var watchSeason: Int = -1,
    /** Posição na sua ordem manual (0 = ainda sem posição, aparece no topo). */
    var order: Long = 0L,
    /** "serie" ou "filme". */
    var kind: String = "serie",
    /** Arquivo de áudio da trilha sonora (cópia dentro do app); vazio = sem trilha. */
    var soundtrack: String = "",
    var castPeople: List<CastPerson> = emptyList(),
    var couplePhoto: String = "",
    /** Até 2 gêneros extras escolhidos para aparecer no cartão da Estante (vazio = os 2 primeiros). */
    var shelfTags: List<String> = emptyList()
)

/** Gêneros extras que aparecem no cartão da Estante: os escolhidos (até 2) ou, sem escolha, os 2 primeiros. */
fun shelfPick(d: Drama): List<String> {
    val avail = d.tags.filter { it != d.genre && (Genres.exists(it) || OtherGenres.exists(it)) }
    val chosen = d.shelfTags.filter { avail.contains(it) }.distinct().take(2)
    return if (chosen.isNotEmpty()) chosen else avail.take(2)
}

fun totalEps(d: Drama): Int = d.seasonEps.sum()
fun watchedEps(d: Drama): Int = d.watched.sum()
fun seasonCount(d: Drama): Int = d.seasonEps.size

fun allTotalsKnown(d: Drama): Boolean = d.seasonEps.all { it > 0 }

fun seasonDone(d: Drama, i: Int): Boolean =
    i in d.seasonEps.indices && d.seasonEps[i] > 0 && d.watched[i] >= d.seasonEps[i]

fun currentSeason(d: Drama): Int {
    for (i in d.seasonEps.indices) {
        val t = d.seasonEps[i]
        if (t == 0 || d.watched[i] < t) return i
    }
    return maxOf(0, d.seasonEps.size - 1)
}

/**
 * Temporada em que você está: a mais avançada que já tem episódio assistido;
 * se ela já terminou e existe a próxima, é a próxima. (Não depende mais da aba escolhida no
 * navegador, que antes ficava presa na T1.)
 */
fun activeSeason(d: Drama): Int {
    var last = -1
    for (i in d.seasonEps.indices) if (d.watched[i] > 0) last = i
    if (last < 0) return 0
    if (seasonDone(d, last) && last + 1 < d.seasonEps.size) return last + 1
    return last
}

/** Quantos episódios ainda faltam (só conta temporadas com total conhecido). */
fun remainingEps(d: Drama): Int {
    var r = 0
    for (i in d.seasonEps.indices) if (d.seasonEps[i] > 0) r += maxOf(0, d.seasonEps[i] - d.watched[i])
    return r
}

/** "Temp. 2" ou "" quando só há uma temporada. */
fun seasonTag(d: Drama): String = if (seasonCount(d) > 1) "Temp. " + (activeSeason(d) + 1) else ""

/** "Ep. 3/12" da temporada atual. */
fun epTag(d: Drama): String {
    val s = activeSeason(d)
    val t = d.seasonEps[s]
    return if (t > 0) "Ep. " + d.watched[s] + "/" + t else "Ep. " + d.watched[s]
}

/** Texto do contador do navegador, ex.: "T2 · Ep. 5/12". */
fun counterText(d: Drama, s: Int): String {
    val t = d.seasonEps[s]
    val w = d.watched[s]
    val ep = if (t > 0) "Ep. $w/$t" else "Ep. $w"
    return if (seasonCount(d) > 1) "T${s + 1} · $ep" else ep
}

fun progressText(d: Drama): String {
    val t = totalEps(d)
    val w = watchedEps(d)
    if (seasonCount(d) <= 1) {
        return if (t > 0) "Ep. $w de $t" else "Ep. $w"
    }
    val cs = activeSeason(d)
    val st = d.seasonEps[cs]
    val part = if (st > 0) "${d.watched[cs]}/$st" else "${d.watched[cs]}"
    return "Temp. ${cs + 1} · Ep. $part"
}

fun progressOf(d: Drama): Float {
    var t = 0
    var w = 0
    for (i in d.seasonEps.indices) {
        if (d.seasonEps[i] > 0) {
            t += d.seasonEps[i]
            w += minOf(d.watched[i], d.seasonEps[i])
        }
    }
    return if (t > 0) w.toFloat() / t.toFloat() else 0f
}

fun subtitle(d: Drama): String {
    val parts = ArrayList<String>()
    parts.add(d.country)
    if (d.year.isNotBlank()) parts.add(d.year)
    if (seasonCount(d) > 1) parts.add(seasonCount(d).toString() + " temp.")
    val t = totalEps(d)
    if (t > 0) parts.add(t.toString() + " eps")
    return parts.joinToString(" · ")
}

/** Ordem manual: quem tem posição vem pela posição; os novos (sem posição) ficam no topo. */
fun manualOrder(l: List<Drama>): List<Drama> =
    l.sortedWith(compareBy<Drama> { it.order }.thenByDescending { it.addedAt })

fun hoursWatched(d: Drama): Double =
    watchedEps(d) * (if (d.epMinutes > 0) d.epMinutes else 60) / 60.0

fun normalize(d: Drama) {
    val e = d.seasonEps.toMutableList()
    val w = d.watched.toMutableList()
    if (e.isEmpty()) e.add(0)
    while (w.size < e.size) w.add(0)
    while (w.size > e.size) w.removeAt(w.size - 1)
    for (i in e.indices) {
        if (e[i] < 0) e[i] = 0
        if (w[i] < 0) w[i] = 0
        if (e[i] > 0 && w[i] > e[i]) w[i] = e[i]
    }
    d.seasonEps = e
    d.watched = w
    if (d.watchSeason < -1 || d.watchSeason >= e.size) d.watchSeason = -1
}

fun applyStatus(d: Drama, key: String) {
    d.status = key
    val now = System.currentTimeMillis()
    if (key == "assistindo" && d.startDate == 0L) d.startDate = now
    if (key == "concluido") {
        val w = ArrayList<Int>()
        for (i in d.seasonEps.indices) {
            w.add(if (d.seasonEps[i] > 0) d.seasonEps[i] else d.watched[i])
        }
        d.watched = w
        if (d.startDate == 0L) d.startDate = now
        if (d.endDate == 0L) d.endDate = now
    }
}

private fun intsToJson(l: List<Int>): JSONArray {
    val a = JSONArray()
    for (v in l) a.put(v)
    return a
}

private fun jsonInts(a: JSONArray?): List<Int> {
    val r = ArrayList<Int>()
    if (a != null) {
        for (i in 0 until a.length()) r.add(a.optInt(i, 0))
    }
    return r
}

private fun jsonStrings(a: JSONArray?): List<String> {
    val r = ArrayList<String>()
    if (a != null) {
        for (i in 0 until a.length()) r.add(a.optString(i, ""))
    }
    return r
}

/** Lê o elenco; backups antigos (só texto "A, B, C") viram pessoas sem foto. */
private fun castFromJson(a: JSONArray?, oldText: String): List<CastPerson> {
    val r = ArrayList<CastPerson>()
    if (a != null && a.length() > 0) {
        for (i in 0 until a.length()) {
            val po = a.optJSONObject(i) ?: continue
            val n = po.optString("name", "").trim()
            if (n.isNotEmpty()) r.add(CastPerson(n, po.optString("photo", "")))
        }
    } else {
        for (n in oldText.split(",", ";", "/")) {
            val t = n.trim()
            if (t.isNotEmpty()) r.add(CastPerson(t, ""))
        }
    }
    return r
}

private fun Drama.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id)
    o.put("title", title)
    o.put("original", original)
    o.put("synopsis", synopsis)
    o.put("country", country)
    o.put("genre", genre)
    val ta = JSONArray()
    for (t in tags) ta.put(t)
    o.put("tags", ta)
    o.put("status", status)
    o.put("score", score)
    o.put("seasonEps", intsToJson(seasonEps))
    o.put("watched", intsToJson(watched))
    o.put("epMinutes", epMinutes)
    o.put("year", year)
    o.put("platform", platform)
    o.put("cast", cast)
    o.put("couple", couple)
    o.put("startDate", startDate)
    o.put("endDate", endDate)
    o.put("rewatch", rewatch)
    o.put("notes", notes)
    o.put("favorite", favorite)
    o.put("cover", cover)
    o.put("addedAt", addedAt)
    o.put("link", link)
    o.put("lastUrl", lastUrl)
    o.put("watchSeason", watchSeason)
    o.put("order", order)
    o.put("kind", kind)
    o.put("soundtrack", soundtrack)
    o.put("couplePhoto", couplePhoto)
    o.put("shelfTags", JSONArray().also { a -> for (t in shelfTags) a.put(t) })
    val cp = JSONArray()
    for (p in castPeople) {
        val po = JSONObject()
        po.put("name", p.name)
        po.put("photo", p.photo)
        cp.put(po)
    }
    o.put("castPeople", cp)
    return o
}

private fun dramaFromJson(o: JSONObject): Drama {
    var eps = jsonInts(o.optJSONArray("seasonEps"))
    var wat = jsonInts(o.optJSONArray("watched"))
    if (eps.isEmpty()) {
        // versão antiga: um único bloco de episódios
        eps = listOf(o.optInt("epTotal", 0))
        wat = listOf(o.optInt("epWatched", 0))
    }
    val score = if (o.has("score")) o.optInt("score", 0) else o.optInt("rating", 0) * 2
    val d = Drama(
        id = o.optLong("id", System.currentTimeMillis()),
        title = o.optString("title", ""),
        original = o.optString("original", ""),
        synopsis = o.optString("synopsis", ""),
        country = o.optString("country", countries[0]),
        genre = o.optString("genre", "romance"),
        tags = jsonStrings(o.optJSONArray("tags")),
        status = o.optString("status", "quero"),
        score = score,
        seasonEps = eps,
        watched = wat,
        epMinutes = o.optInt("epMinutes", 0),
        year = o.optString("year", ""),
        platform = o.optString("platform", ""),
        cast = o.optString("cast", ""),
        couple = o.optString("couple", ""),
        startDate = o.optLong("startDate", 0L),
        endDate = o.optLong("endDate", 0L),
        rewatch = o.optInt("rewatch", 0),
        notes = o.optString("notes", ""),
        favorite = o.optBoolean("favorite", false),
        cover = o.optString("cover", ""),
        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
        link = o.optString("link", ""),
        lastUrl = o.optString("lastUrl", ""),
        watchSeason = o.optInt("watchSeason", -1),
        order = o.optLong("order", 0L),
        kind = o.optString("kind", "serie"),
        soundtrack = o.optString("soundtrack", ""),
        castPeople = castFromJson(o.optJSONArray("castPeople"), o.optString("cast", "")),
        couplePhoto = o.optString("couplePhoto", ""),
        shelfTags = jsonStrings(o.optJSONArray("shelfTags"))
    )
    normalize(d)
    return d
}

object Store {
    private const val PREF = "doramabloom"
    private const val KEY = "dramas"

    private lateinit var prefs: SharedPreferences
    lateinit var appContext: Context
    private val list = ArrayList<Drama>()
    private var loaded = false

    /** Sobe a cada gravação; as telas usam para saber se precisam se atualizar. */
    var version = 0
        private set

    fun init(c: Context) {
        if (loaded) return
        appContext = c.applicationContext
        prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        loadGenres()
        loadGenreEdits()
        loadOtherGenres()
        load()
        loaded = true
    }

    const val DEFAULT_HOME = "https://www.google.com"

    /** Aparência do app: 0 = seguir o sistema, 1 = claro, 2 = escuro. */
    var themeMode: Int
        get() = prefs.getInt("themeMode", 0)
        set(v) {
            prefs.edit().putInt("themeMode", v).apply()
        }

    var textZoom: Int
        get() = prefs.getInt("textZoom", 100)
        set(v) {
            prefs.edit().putInt("textZoom", v).apply()
        }

    var homeUrl: String
        get() {
            val u = prefs.getString("homeUrl", DEFAULT_HOME) ?: DEFAULT_HOME
            return if (u.isBlank()) DEFAULT_HOME else u
        }
        set(v) {
            prefs.edit().putString("homeUrl", v).apply()
        }

    var userName: String
        get() = prefs.getString("userName", "") ?: ""
        set(v) {
            prefs.edit().putString("userName", v).apply()
        }

    var askedName: Boolean
        get() = prefs.getBoolean("askedName", false)
        set(v) {
            prefs.edit().putBoolean("askedName", v).apply()
        }

    private const val GKEY = "customGenres"

    private fun genreToJson(g: Genre): JSONObject {
        val o = JSONObject()
        o.put("key", g.key)
        o.put("label", g.label)
        o.put("icon", g.icon)
        o.put("color", g.base)
        o.put("tagline", g.tagline)
        o.put("low", g.lowLine)
        o.put("mid", g.midLine)
        return o
    }

    private fun loadGenres() {
        val r = ArrayList<Genre>()
        try {
            val arr = JSONArray(prefs.getString(GKEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.optString("key", "")
                val l = o.optString("label", "")
                if (k.isBlank() || l.isBlank()) continue
                r.add(
                    Genres.makeCustom(
                        k, l, o.optString("icon", "heart"),
                        o.optInt("color", Palette.pink), o.optString("tagline", ""), o.optString("low", ""), o.optString("mid", "")
                    )
                )
            }
        } catch (e: Exception) {
            // dados corrompidos: sem gêneros próprios
        }
        Genres.setCustom(r)
    }

    private const val EKEY = "genreEdits"

    private fun loadGenreEdits() {
        val m = HashMap<String, Genre>()
        try {
            val arr = JSONArray(prefs.getString(EKEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = editFromJson(o) ?: continue
                m[g.key] = g
            }
        } catch (e: Exception) {
            // dados corrompidos: sem edições
        }
        Genres.setEdits(m)
    }

    /** Lê uma edição de gênero de fábrica; devolve null se a chave não for de fábrica. */
    private fun editFromJson(o: JSONObject): Genre? {
        val k = o.optString("key", "")
        val base = Genres.factoryOf(k) ?: return null
        val l = o.optString("label", "").trim()
        if (l.isEmpty()) return null
        return Genres.editBuiltin(
            base, l, o.optString("icon", base.icon),
            o.optInt("color", base.base), o.optString("tagline", ""), o.optString("low", ""), o.optString("mid", "")
        )
    }

    private const val OKEY = "customOthers"
    private const val OEKEY = "otherEdits"
    private const val OHKEY = "otherHidden"

    private fun otherToJson(g: OtherGenre): JSONObject {
        val o = JSONObject()
        o.put("key", g.key)
        o.put("label", g.label)
        o.put("icon", g.icon)
        o.put("color", g.color)
        val ex = JSONArray()
        for (c in g.extra) ex.put(c)
        o.put("extra", ex)
        return o
    }

    private fun otherFromJson(o: JSONObject, custom: Boolean): OtherGenre? {
        val k = o.optString("key", "")
        val l = o.optString("label", "").trim()
        if (k.isBlank() || l.isEmpty()) return null
        val ic = o.optString("icon", "tag")
        val col = o.optInt("color", Palette.pink)
        val ex = ArrayList<Int>()
        val ea = o.optJSONArray("extra")
        if (ea != null) for (i in 0 until ea.length()) ex.add(ea.optInt(i))
        return if (custom) {
            OtherGenres.makeCustom(k, l, ic, col, ex)
        } else {
            val base = OtherGenres.factoryOf(k) ?: return null
            OtherGenres.editBuiltin(base, l, ic, col, ex)
        }
    }

    private fun parseOthers(arr: JSONArray?, custom: Boolean): List<OtherGenre> {
        val r = ArrayList<OtherGenre>()
        if (arr == null) return r
        for (i in 0 until arr.length()) {
            var g: OtherGenre? = null
            try {
                g = otherFromJson(arr.getJSONObject(i), custom)
            } catch (e: Exception) {
                // item inválido: ignora
            }
            if (g == null) continue
            if (r.any { it.key == g.key }) continue
            r.add(g)
        }
        return r
    }

    private fun loadOtherGenres() {
        try {
            val c = parseOthers(JSONArray(prefs.getString(OKEY, "[]") ?: "[]"), true)
            OtherGenres.setCustom(c)
            val m = HashMap<String, OtherGenre>()
            for (g in parseOthers(JSONArray(prefs.getString(OEKEY, "[]") ?: "[]"), false)) m[g.key] = g
            OtherGenres.setEdits(m)
            val hs = HashSet<String>()
            val ha = JSONArray(prefs.getString(OHKEY, "[]") ?: "[]")
            for (i in 0 until ha.length()) hs.add(ha.optString(i, ""))
            OtherGenres.setHidden(hs)
        } catch (e: Exception) {
            // dados corrompidos: sem outros gêneros próprios
        }
    }

    private fun persistOthers() {
        version++
        val arr = JSONArray()
        for (g in OtherGenres.custom()) arr.put(otherToJson(g))
        val earr = JSONArray()
        for (g in OtherGenres.edited().values) earr.put(otherToJson(g))
        val harr = JSONArray()
        for (k in OtherGenres.hiddenKeys()) harr.put(k)
        prefs.edit().putString(OKEY, arr.toString()).putString(OEKEY, earr.toString()).putString(OHKEY, harr.toString()).apply()
    }

    /** Salva a edição de um "outro gênero" (criado por você ou de fábrica). */
    fun updateOtherGenre(g: OtherGenre) {
        if (g.custom) {
            OtherGenres.setCustom(OtherGenres.custom().map { if (it.key == g.key) g else it })
        } else {
            val m = HashMap(OtherGenres.edited())
            m[g.key] = g
            OtherGenres.setEdits(m)
        }
        persistOthers()
    }

    fun resetOtherGenre(key: String) {
        val m = HashMap(OtherGenres.edited())
        m.remove(key)
        OtherGenres.setEdits(m)
        persistOthers()
    }

    fun addOtherGenre(g: OtherGenre) {
        val l = ArrayList(OtherGenres.custom())
        l.removeAll { it.key == g.key }
        l.add(g)
        OtherGenres.setCustom(l)
        persistOthers()
    }

    /** Apaga um "outro gênero" criado por você; ele sai das etiquetas dos doramas. */
    fun removeOtherGenre(key: String) {
        OtherGenres.setCustom(OtherGenres.custom().filter { it.key != key })
        var changed = false
        for (d in list) {
            if (d.tags.contains(key) || d.shelfTags.contains(key)) {
                d.tags = d.tags.filter { it != key }
                d.shelfTags = d.shelfTags.filter { it != key }
                changed = true
            }
        }
        persistOthers()
        if (changed) persist()
    }

    /** Apaga um "outro gênero" que veio com o app (volta em "Restaurar padrões"). */
    fun hideOtherGenre(key: String) {
        OtherGenres.setHidden(OtherGenres.hiddenKeys() + key)
        var changed = false
        for (d in list) {
            if (d.tags.contains(key) || d.shelfTags.contains(key)) {
                d.tags = d.tags.filter { it != key }
                d.shelfTags = d.shelfTags.filter { it != key }
                changed = true
            }
        }
        persistOthers()
        if (changed) persist()
    }

    /** Traz de volta os de fábrica apagados e desfaz as edições deles (os seus ficam). */
    fun restoreOtherDefaults() {
        OtherGenres.setHidden(emptySet())
        OtherGenres.setEdits(emptyMap())
        persistOthers()
    }

    /** Junta (ou troca, se replace) os "outros gêneros" de um backup. */
    private fun importOthers(root: JSONObject, replace: Boolean) {
        val cust = parseOthers(root.optJSONArray("otherGenres"), true)
        val eds = parseOthers(root.optJSONArray("otherEdits"), false)
        if (replace) {
            OtherGenres.setCustom(cust)
            OtherGenres.setEdits(eds.associateBy { it.key })
        } else {
            val cur = ArrayList(OtherGenres.custom())
            for (g in cust) if (!OtherGenres.exists(g.key)) cur.add(g)
            OtherGenres.setCustom(cur)
            val m = HashMap(OtherGenres.edited())
            for (g in eds) if (!m.containsKey(g.key)) m[g.key] = g
            OtherGenres.setEdits(m)
        }
        val hid = HashSet<String>(if (replace) emptySet() else OtherGenres.hiddenKeys())
        val ha = root.optJSONArray("otherHidden")
        if (ha != null) for (i in 0 until ha.length()) hid.add(ha.optString(i, ""))
        OtherGenres.setHidden(hid)
        persistOthers()
    }

    private fun persistGenres() {
        version++
        val arr = JSONArray()
        for (g in Genres.custom()) arr.put(genreToJson(g))
        val earr = JSONArray()
        for (g in Genres.edited().values) earr.put(genreToJson(g))
        prefs.edit().putString(GKEY, arr.toString()).putString(EKEY, earr.toString()).apply()
    }

    /** Salva a edição de um gênero (criado por você ou de fábrica). */
    fun updateGenre(g: Genre) {
        if (g.custom) {
            Genres.setCustom(Genres.custom().map { if (it.key == g.key) g else it })
        } else {
            val m = HashMap(Genres.edited())
            m[g.key] = g
            Genres.setEdits(m)
        }
        persistGenres()
    }

    /** Volta um gênero de fábrica ao nome, ícone e cor originais. */
    fun resetGenre(key: String) {
        val m = HashMap(Genres.edited())
        m.remove(key)
        Genres.setEdits(m)
        persistGenres()
    }

    fun addGenre(g: Genre) {
        val l = ArrayList(Genres.custom())
        l.removeAll { it.key == g.key }
        l.add(g)
        Genres.setCustom(l)
        persistGenres()
    }

    /** Apaga um gênero criado por você; os doramas dele voltam para Romance. */
    fun removeGenre(key: String) {
        Genres.setCustom(Genres.custom().filter { it.key != key })
        var changed = false
        for (d in list) {
            if (d.genre == key) {
                d.genre = "romance"
                changed = true
            }
            if (d.tags.contains(key)) {
                d.tags = d.tags.filter { it != key }
                changed = true
            }
        }
        persistGenres()
        if (changed) persist()
    }

    fun setWatchSeason(d: Drama, s: Int) {
        d.watchSeason = s
        persist()
    }

    private fun load() {
        list.clear()
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                list.add(dramaFromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            // dados corrompidos: começa vazio
        }
    }

    private fun persist() {
        version++
        val arr = JSONArray()
        for (d in list) arr.put(d.toJson())
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun all(): List<Drama> = list.toList()

    fun get(id: Long): Drama? = list.firstOrNull { it.id == id }

    fun save(d: Drama) {
        normalize(d)
        val i = list.indexOfFirst { it.id == d.id }
        if (i >= 0) list[i] = d else list.add(0, d)
        persist()
    }

    fun delete(id: Long) {
        val d = get(id) ?: return
        val extras = ArrayList<String>()
        if (d.soundtrack.isNotEmpty()) extras.add(d.soundtrack)
        if (d.couplePhoto.isNotEmpty()) extras.add(d.couplePhoto)
        for (p in d.castPeople) if (p.photo.isNotEmpty()) extras.add(p.photo)
        for (f in extras) {
            try {
                File(f).delete()
            } catch (e: Exception) {
            }
        }
        if (d.cover.isNotEmpty()) {
            try {
                File(d.cover).delete()
            } catch (e: Exception) {
            }
            Covers.clear()
        }
        list.removeAll { it.id == id }
        persist()
    }

    /** Soma um episódio na temporada que você está assistindo (ou na primeira em andamento). Devolve true se acabou de concluir o dorama. */
    fun bump(d: Drama): Boolean {
        val w = d.watched.toMutableList()
        var target = -1
        val a = activeSeason(d)
        val ta = d.seasonEps[a]
        if (ta == 0 || w[a] < ta) {
            target = a
        } else {
            for (i in d.seasonEps.indices) {
                val t = d.seasonEps[i]
                if (t == 0 || w[i] < t) {
                    target = i
                    break
                }
            }
        }
        if (target < 0) return false
        w[target] = w[target] + 1
        d.watched = w
        return afterProgress(d, true)
    }

    /** Ajusta os episódios de uma temporada específica (delta positivo ou negativo). */
    fun adjust(d: Drama, season: Int, delta: Int): Boolean {
        if (season < 0 || season >= d.seasonEps.size) return false
        val w = d.watched.toMutableList()
        val t = d.seasonEps[season]
        val old = w[season]
        var nv = old + delta
        if (nv < 0) nv = 0
        if (t > 0 && nv > t) nv = t
        if (nv == old) return false
        w[season] = nv
        d.watched = w
        if (nv < old && d.status == "concluido") d.status = "assistindo"
        return afterProgress(d, nv > old)
    }

    /** Só conclui/atualiza o status quando você AVANÇOU um episódio (grew); nunca ao voltar ou sem mudar nada. */
    private fun afterProgress(d: Drama, grew: Boolean): Boolean {
        var finished = false
        val total = totalEps(d)
        if (grew && watchedEps(d) > 0 && total > 0 && d.seasonEps.all { it > 0 } && watchedEps(d) >= total) {
            if (d.status != "concluido") finished = true
            applyStatus(d, "concluido")
        } else if (grew && watchedEps(d) > 0 && d.status != "assistindo" && d.status != "concluido") {
            applyStatus(d, "assistindo")
        }
        persist()
        return finished
    }

    /**
     * Guarda a sua ordem manual. [ids] é a lista na nova ordem (pode ser só uma parte da estante,
     * por causa dos filtros): os outros doramas não saem do lugar.
     */
    fun reorder(ids: List<Long>) {
        val seq = manualOrder(list)
        for (i in seq.indices) seq[i].order = (i + 1).toLong()
        val items = ids.mapNotNull { get(it) }
        val slots = items.map { it.order }.sorted()
        for (i in items.indices) items[i].order = slots[i]
        persist()
    }

    fun setStatus(d: Drama, key: String) {
        applyStatus(d, key)
        persist()
    }

    fun persistNow() {
        persist()
    }

    fun exportJson(): String {
        val arr = JSONArray()
        for (d in list) {
            val o = d.toJson()
            o.put("cover", "")
            arr.put(o)
        }
        val ga = JSONArray()
        for (g in Genres.custom()) ga.put(genreToJson(g))
        val root = JSONObject()
        root.put("genres", ga)
        val ea = JSONArray()
        for (g in Genres.edited().values) ea.put(genreToJson(g))
        root.put("genreEdits", ea)
        root.put("otherGenres", JSONArray().also { a -> for (g in OtherGenres.custom()) a.put(otherToJson(g)) })
        root.put("otherEdits", JSONArray().also { a -> for (g in OtherGenres.edited().values) a.put(otherToJson(g)) })
        root.put("otherHidden", JSONArray().also { a -> for (k in OtherGenres.hiddenKeys()) a.put(k) })
        root.put("dramas", arr)
        return root.toString()
    }

    /** Importa um backup (novo ou antigo). Devolve quantos foram adicionados, ou -1 se o texto for inválido. */
    fun importJson(s: String): Int {
        return try {
            val t = s.trim()
            val arr: JSONArray
            if (t.startsWith("{")) {
                val root = JSONObject(t)
                val ga = root.optJSONArray("genres")
                if (ga != null) {
                    val cur = ArrayList(Genres.custom())
                    for (i in 0 until ga.length()) {
                        val o = ga.getJSONObject(i)
                        val k = o.optString("key", "")
                        val l = o.optString("label", "")
                        if (k.isBlank() || l.isBlank() || Genres.exists(k)) continue
                        cur.add(
                            Genres.makeCustom(
                                k, l, o.optString("icon", "heart"),
                                o.optInt("color", Palette.pink), o.optString("tagline", ""), o.optString("low", ""), o.optString("mid", "")
                            )
                        )
                    }
                    Genres.setCustom(cur)
                    persistGenres()
                }
                val ea = root.optJSONArray("genreEdits")
                if (ea != null) {
                    val m = HashMap(Genres.edited())
                    for (i in 0 until ea.length()) {
                        val g = editFromJson(ea.getJSONObject(i)) ?: continue
                        if (!m.containsKey(g.key)) m[g.key] = g
                    }
                    Genres.setEdits(m)
                    persistGenres()
                }
                importOthers(root, false)
                arr = root.optJSONArray("dramas") ?: JSONArray()
            } else {
                arr = JSONArray(t)
            }
            var n = 0
            for (i in 0 until arr.length()) {
                val d = dramaFromJson(arr.getJSONObject(i))
                if (d.title.isBlank()) continue
                if (list.any { it.title.equals(d.title, true) && it.year == d.year }) continue
                d.cover = ""
                d.id = System.currentTimeMillis() + i
                list.add(d)
                n++
            }
            persist()
            n
        } catch (e: Exception) {
            -1
        }
    }

    // ------------------------------------------------------------------ BACKUP COMPLETO (.zip)

    fun backupFileName(): String =
        "MyDoramas-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"

    /** Foto da lista no momento do pedido (o arquivo é gravado em segundo plano). */
    fun backupSnapshot(): List<Drama> = list.map { it.copy() }

    private fun settingsJson(): JSONObject {
        val s = JSONObject()
        s.put("themeMode", themeMode)
        s.put("textZoom", textZoom)
        s.put("homeUrl", homeUrl)
        s.put("userName", userName)
        s.put("askedName", askedName)
        return s
    }

    /**
     * Grava o backup completo num .zip: backup.json (doramas, gêneros, ajustes)
     * e a pasta covers/ com a imagem de cada capa. Devolve false se der erro.
     */
    fun writeBackup(dramas: List<Drama>, out: OutputStream): Boolean {
        return try {
            val zip = ZipOutputStream(BufferedOutputStream(out))
            val arr = JSONArray()
            val files = ArrayList<Pair<String, File>>()
            for (d in dramas) {
                val o = d.toJson()
                val f = if (d.cover.isNotEmpty()) File(d.cover) else null
                if (f != null && f.exists()) {
                    val name = "covers/" + d.id + ".jpg"
                    o.put("cover", name)
                    files.add(Pair(name, f))
                } else {
                    o.put("cover", "")
                }
                arr.put(o)
            }
            val ga = JSONArray()
            for (g in Genres.custom()) ga.put(genreToJson(g))
            val ea = JSONArray()
            for (g in Genres.edited().values) ea.put(genreToJson(g))
            val root = JSONObject()
            root.put("app", "MyDoramas")
            root.put("backupVersion", 2)
            root.put("createdAt", System.currentTimeMillis())
            root.put("settings", settingsJson())
            root.put("genres", ga)
            root.put("genreEdits", ea)
            root.put("otherGenres", JSONArray().also { a -> for (g in OtherGenres.custom()) a.put(otherToJson(g)) })
            root.put("otherEdits", JSONArray().also { a -> for (g in OtherGenres.edited().values) a.put(otherToJson(g)) })
        root.put("otherHidden", JSONArray().also { a -> for (k in OtherGenres.hiddenKeys()) a.put(k) })
            root.put("dramas", arr)
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in files) {
                zip.putNextEntry(ZipEntry(p.first))
                FileInputStream(p.second).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parseCustomGenres(ga: JSONArray?, taken: (String) -> Boolean): List<Genre> {
        val r = ArrayList<Genre>()
        if (ga == null) return r
        for (i in 0 until ga.length()) {
            val o = ga.getJSONObject(i)
            val k = o.optString("key", "")
            val l = o.optString("label", "")
            if (k.isBlank() || l.isBlank() || taken(k) || r.any { it.key == k }) continue
            r.add(
                Genres.makeCustom(
                    k, l, o.optString("icon", "heart"),
                    o.optInt("color", Palette.pink), o.optString("tagline", ""), o.optString("low", ""), o.optString("mid", "")
                )
            )
        }
        return r
    }

    private fun applySettings(s: JSONObject?, overwrite: Boolean) {
        if (s == null) return
        if (overwrite) {
            if (s.has("themeMode")) themeMode = s.optInt("themeMode", 0)
            if (s.has("textZoom")) textZoom = s.optInt("textZoom", 100)
            if (s.has("homeUrl")) homeUrl = s.optString("homeUrl", DEFAULT_HOME)
            if (s.has("userName")) userName = s.optString("userName", "")
            if (s.has("askedName")) askedName = s.optBoolean("askedName", true)
        } else {
            val n = s.optString("userName", "")
            if (userName.isBlank() && n.isNotBlank()) {
                userName = n
                askedName = true
            }
        }
    }

    /**
     * Lê um backup (o .zip completo ou o texto antigo).
     * replace = apaga o que existe e restaura tudo; senão só junta o que ainda não existe.
     * Devolve quantos doramas entraram, ou -1 se o arquivo não for um backup válido.
     */
    fun readBackup(bytes: ByteArray, replace: Boolean): Int {
        return try {
            if (bytes.size < 2) return -1
            val covers = HashMap<String, ByteArray>()
            var jsonText: String? = null
            val isZip = bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            if (isZip) {
                val z = ZipInputStream(ByteArrayInputStream(bytes))
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val data = z.readBytes()
                        if (e.name == "backup.json") {
                            jsonText = String(data, Charsets.UTF_8)
                        } else if (e.name.startsWith("covers/")) {
                            covers[e.name] = data
                        }
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
                z.close()
            } else {
                jsonText = String(bytes, Charsets.UTF_8)
            }
            val t = (jsonText ?: return -1).trim()
            val root: JSONObject? = if (t.startsWith("{")) JSONObject(t) else null
            val arr: JSONArray = if (root != null) (root.optJSONArray("dramas") ?: JSONArray()) else JSONArray(t)

            // 1) lê e confere tudo antes de mexer em qualquer coisa
            val keep = ArrayList<Pair<Drama, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = dramaFromJson(o)
                if (d.title.isBlank()) continue
                if (!replace) {
                    val dup = list.any { it.title.equals(d.title, true) && it.year == d.year } ||
                        keep.any { it.first.title.equals(d.title, true) && it.first.year == d.year }
                    if (dup) continue
                }
                keep.add(Pair(d, o.optString("cover", "")))
            }
            if (replace && keep.isEmpty()) return -1

            // 2) grava as capas e acerta os ids
            val dir = File(appContext.filesDir, "covers")
            dir.mkdirs()
            val stamp = System.currentTimeMillis()
            val used = HashSet<Long>()
            if (!replace) for (d in list) used.add(d.id)
            var next = stamp
            for (i in keep.indices) {
                val d = keep[i].first
                if (!replace || used.contains(d.id)) {
                    while (used.contains(next)) next++
                    d.id = next
                }
                used.add(d.id)
                d.cover = ""
                val data = covers[keep[i].second]
                if (data != null && data.isNotEmpty()) {
                    val f = File(dir, "c" + stamp + "_" + i + ".jpg")
                    FileOutputStream(f).use { it.write(data) }
                    d.cover = f.absolutePath
                }
            }

            // 3) aplica
            if (replace) {
                val novas = HashSet<String>()
                for (p in keep) novas.add(p.first.cover)
                for (d in list) {
                    if (d.cover.isNotEmpty() && !novas.contains(d.cover)) {
                        try {
                            File(d.cover).delete()
                        } catch (e: Exception) {
                        }
                    }
                }
                list.clear()
                for (p in keep) list.add(p.first)
                if (root != null) {
                    if (root.has("genres")) {
                        Genres.setCustom(parseCustomGenres(root.optJSONArray("genres")) { Genres.factoryOf(it) != null })
                    }
                    if (root.has("genreEdits")) {
                        val m = HashMap<String, Genre>()
                        val ea = root.optJSONArray("genreEdits")
                        if (ea != null) {
                            for (i in 0 until ea.length()) {
                                val g = editFromJson(ea.getJSONObject(i)) ?: continue
                                m[g.key] = g
                            }
                        }
                        Genres.setEdits(m)
                    }
                    persistGenres()
                    importOthers(root, true)
                    applySettings(root.optJSONObject("settings"), true)
                }
            } else {
                for (p in keep) list.add(p.first)
                if (root != null) {
                    val novos = parseCustomGenres(root.optJSONArray("genres")) { Genres.exists(it) }
                    if (novos.isNotEmpty()) {
                        Genres.setCustom(Genres.custom() + novos)
                    }
                    val ea = root.optJSONArray("genreEdits")
                    if (ea != null) {
                        val m = HashMap(Genres.edited())
                        for (i in 0 until ea.length()) {
                            val g = editFromJson(ea.getJSONObject(i)) ?: continue
                            if (!m.containsKey(g.key)) m[g.key] = g
                        }
                        Genres.setEdits(m)
                    }
                    persistGenres()
                    importOthers(root, false)
                    applySettings(root.optJSONObject("settings"), false)
                }
            }
            Covers.clear()
            persist()
            keep.size
        } catch (e: Exception) {
            -1
        }
    }

    /** Copia a imagem escolhida para a pasta do app (reduzida) e devolve o caminho. */
    /** Copia o áudio escolhido para dentro do app (assim ele continua tocando mesmo se o original sumir). */
    fun saveAudio(uri: Uri): String? {
        return try {
            val dir = File(appContext.filesDir, "music")
            dir.mkdirs()
            val f = File(dir, "t" + System.currentTimeMillis() + ".audio")
            appContext.contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(f).use { out -> i.copyTo(out) }
            } ?: return null
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun saveCover(uri: Uri): String? {
        return try {
            val cr = appContext.contentResolver
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            var rot = 0
            try {
                cr.openInputStream(uri)?.use { s ->
                    val ex = ExifInterface(s)
                    val o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    rot = when (o) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
            }
            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val dir = File(appContext.filesDir, "covers")
            dir.mkdirs()
            val f = File(dir, "c" + System.currentTimeMillis() + ".jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
