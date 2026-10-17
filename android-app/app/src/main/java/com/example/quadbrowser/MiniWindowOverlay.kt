package com.example.quadbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Janela flutuante mini: uma janelinha arrastável por cima de qualquer app
 * (permissão "Sobrepor a outros apps") que hospeda a MESMA instância (mesmo
 * WebView, mesma sessão) que estava na tela. Como o WebView não é recriado,
 * o jogo continua conectado e rodando; o serviço em primeiro plano mantém o
 * processo vivo enquanto o app está em segundo plano.
 */
@SuppressLint("ClickableViewAccessibility")
class MiniWindowOverlay(
    private val activity: Activity,
    private val content: View,
    private val aspect: Float,
    private val onOpenApp: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val appContext = activity.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = appContext.getSharedPreferences("mini_window", Context.MODE_PRIVATE)
    private val sizesDp = intArrayOf(120, 180, 260)
    private val sizeLabels = arrayOf("P", "M", "G")
    private var sizeIndex = prefs.getInt("size", 0).coerceIn(0, sizesDp.size - 1)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizeLabel: TextView? = null
    private val barHeight = dp(26)

    fun show(): Boolean {
        if (root != null) return true
        (content.parent as? ViewGroup)?.removeView(content)

        val body = FrameLayout(activity).apply {
            clipChildren = true
            clipToPadding = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val grip = FrameLayout(activity).apply {
            addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(Color.argb(150, 255, 255, 255))
                    }
                },
                FrameLayout.LayoutParams(dp(26), dp(4), Gravity.CENTER),
            )
        }

        val label = TextView(activity).apply {
            text = sizeLabels[sizeIndex]
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setOnClickListener { cycleSize() }
        }
        sizeLabel = label

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(245, 16, 24, 38))
            addView(grip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(label, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(iconButton(R.drawable.ic_expand) { onOpenApp() }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(iconButton(R.drawable.ic_close) { onClose() }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2), activity.getColor(R.color.accent))
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val metrics = appContext.resources.displayMetrics
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            dp(sizesDp[sizeIndex]),
            barHeight + bodyHeight(),
            windowType,
            // HARDWARE_ACCELERATED é obrigatório: sem ele o WebView não desenha direito numa janela de overlay.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", metrics.widthPixels - dp(sizesDp[sizeIndex]) - dp(10))
            y = prefs.getInt("y", metrics.heightPixels / 2)
        }
        clampPosition(layoutParams)

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = layoutParams.x; startY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    layoutParams.x = startX + dx.toInt()
                    layoutParams.y = startY + dy.toInt()
                    clampPosition(layoutParams)
                    runCatching { windowManager.updateViewLayout(frame, layoutParams) }
                    true
                }
                else -> true
            }
        }

        val added = runCatching { windowManager.addView(frame, layoutParams) }.isSuccess
        if (!added) {
            // Volta o conteúdo para quem chamou resolver (o MainActivity recoloca no lugar).
            body.removeView(content)
            return false
        }
        root = frame
        params = layoutParams
        return true
    }

    /** Remove a janela e solta o conteúdo (sem destruir) para o app recolocar na tela. */
    fun dismiss() {
        val frame = root ?: return
        params?.let { p ->
            prefs.edit().putInt("x", p.x).putInt("y", p.y).putInt("size", sizeIndex).apply()
        }
        runCatching { windowManager.removeView(frame) }
        (content.parent as? ViewGroup)?.removeView(content)
        root = null
        params = null
    }

    private fun cycleSize() {
        sizeIndex = (sizeIndex + 1) % sizesDp.size
        sizeLabel?.text = sizeLabels[sizeIndex]
        val frame = root ?: return
        val p = params ?: return
        p.width = dp(sizesDp[sizeIndex])
        p.height = barHeight + bodyHeight()
        clampPosition(p)
        runCatching { windowManager.updateViewLayout(frame, p) }
        prefs.edit().putInt("size", sizeIndex).apply()
    }

    private fun bodyHeight(): Int {
        val metrics = appContext.resources.displayMetrics
        val raw = (dp(sizesDp[sizeIndex]) * aspect).toInt()
        return raw.coerceIn(dp(60), (metrics.heightPixels * 0.8f).toInt() - barHeight)
    }

    private fun clampPosition(p: WindowManager.LayoutParams) {
        val metrics = appContext.resources.displayMetrics
        p.x = p.x.coerceIn(0, (metrics.widthPixels - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - p.height).coerceAtLeast(0))
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageButton = ImageButton(activity).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
