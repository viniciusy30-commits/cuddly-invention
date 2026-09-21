package com.example.quadbrowser

    import android.annotation.SuppressLint
    import android.content.Context
    import android.graphics.Color
    import android.graphics.PixelFormat
    import android.graphics.drawable.GradientDrawable
    import android.os.Build
    import android.view.Gravity
    import android.view.MotionEvent
    import android.view.ViewConfiguration
    import android.view.WindowManager
    import android.widget.ImageView

    @SuppressLint("ClickableViewAccessibility")
    class FloatingBubbleOverlay(
      private val context: Context,
      private val onClick: () -> Unit,
    ) {
      private val windowManager = context.getSystemService(WindowManager::class.java)
      private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
      private var bubbleView: ImageView? = null

      fun show() {
          if (bubbleView != null) return
          val size = dp(60)
          val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          } else {
              @Suppress("DEPRECATION")
              WindowManager.LayoutParams.TYPE_PHONE
          }
          val params = WindowManager.LayoutParams(
              size,
              size,
              windowType,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
              PixelFormat.TRANSLUCENT,
          ).apply {
              gravity = Gravity.TOP or Gravity.START
              x = (context.resources.displayMetrics.widthPixels - size - dp(16)).coerceAtLeast(0)
              y = (context.resources.displayMetrics.heightPixels / 2 - size / 2).coerceAtLeast(dp(24))
          }
          val view = ImageView(context).apply {
              contentDescription = context.getString(R.string.floating_bubble_description)
              setImageDrawable(context.applicationInfo.loadIcon(context.packageManager))
              scaleType = ImageView.ScaleType.CENTER_INSIDE
              setPadding(dp(7), dp(7), dp(7), dp(7))
              background = GradientDrawable().apply {
                  shape = GradientDrawable.OVAL
                  setColor(Color.WHITE)
                  setStroke(dp(2), context.getColor(R.color.accent))
              }
              clipToOutline = true
              elevation = dp(5).toFloat()
          }
          var downRawX=0f; var downRawY=0f; var startX=0; var startY=0; var moved=false
          view.setOnTouchListener { _, event ->
              when (event.actionMasked) {
                  MotionEvent.ACTION_DOWN -> { downRawX=event.rawX; downRawY=event.rawY; startX=params.x; startY=params.y; moved=false; true }
                  MotionEvent.ACTION_MOVE -> {
                      val dx=event.rawX-downRawX; val dy=event.rawY-downRawY
                      if (!moved && (kotlin.math.abs(dx)>touchSlop || kotlin.math.abs(dy)>touchSlop)) moved=true
                      params.x=startX+dx.toInt(); params.y=startY+dy.toInt()
                      runCatching { windowManager.updateViewLayout(view, params) }; true
                  }
                  MotionEvent.ACTION_UP -> { if (!moved) onClick(); true }
                  MotionEvent.ACTION_CANCEL -> true
                  else -> true
              }
          }
          runCatching { windowManager.addView(view, params); bubbleView=view }
      }

      fun hide() {
          bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
          bubbleView=null
      }

      private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
    