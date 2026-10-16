package com.example.quadbrowser

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Tela "O que mudou": lê assets/novidades.txt (seção nova sempre no TOPO) e
 * mostra um resumo na primeira abertura depois de uma atualização.
 *
 * Formato:
 *   #id 2026-01-01-a
 *   #titulo Título curto
 *   - uma novidade por linha
 */
object WhatsNew {
    private const val PREFS = "whats_new_prefs"
    private const val KEY_LAST_SEEN = "last_seen_id"

    data class Section(val id: String, val title: String, val items: List<String>)

    fun load(context: Context): List<Section> {
        val text = try {
            context.assets.open("novidades.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return emptyList()
        }
        val sections = mutableListOf<Section>()
        var id: String? = null
        var title = ""
        var items = mutableListOf<String>()
        fun flush() {
            id?.let { sections += Section(it, title, items) }
            id = null; title = ""; items = mutableListOf()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#id")) {
                flush()
                id = line.removePrefix("#id").trim()
            } else if (line.startsWith("#titulo")) {
                title = line.removePrefix("#titulo").trim()
            } else if (line.startsWith("-")) {
                items.add(line.removePrefix("-").trim())
            }
        }
        flush()
        return sections
    }

    /** Mostra automaticamente só quando a seção do topo ainda não foi vista. */
    fun showIfNew(activity: Activity) {
        val sections = load(activity)
        val newest = sections.firstOrNull() ?: return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_SEEN, null) == newest.id) return
        prefs.edit().putString(KEY_LAST_SEEN, newest.id).apply()
        show(activity, listOf(newest))
    }

    /** Abre manualmente (botão "O que mudou"): mostra as últimas seções. */
    fun showAll(activity: Activity) {
        val sections = load(activity)
        if (sections.isEmpty()) return
        show(activity, sections.take(5))
    }

    private fun show(activity: Activity, sections: List<Section>) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val primary = ContextCompat.getColor(activity, R.color.text_primary)
        val secondary = ContextCompat.getColor(activity, R.color.text_secondary)
        val accent = ContextCompat.getColor(activity, R.color.accent)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        sections.forEachIndexed { index, section ->
            column.addView(TextView(activity).apply {
                text = section.title.ifBlank { section.id }
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, if (index == 0) 0 else dp(18), 0, dp(2))
            })
            column.addView(TextView(activity).apply {
                text = section.id
                textSize = 11f
                setTextColor(secondary)
                setPadding(0, 0, 0, dp(8))
            })
            section.items.forEach { item ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(activity).apply {
                    text = "•"
                    textSize = 15f
                    setTextColor(accent)
                    gravity = Gravity.TOP
                    setPadding(0, 0, dp(10), 0)
                })
                row.addView(
                    TextView(activity).apply {
                        text = item
                        textSize = 14f
                        setTextColor(primary)
                        setLineSpacing(0f, 1.15f)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                column.addView(row)
            }
        }
        val scroll = ScrollView(activity).apply {
            addView(column)
            alpha = 0f
            translationY = dp(12).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(280L).start()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.whats_new_title)
            .setView(scroll)
            .setPositiveButton(R.string.whats_new_ok, null)
            .show()
    }
}
