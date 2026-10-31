package com.nnexe.overlaybrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import java.net.URISyntaxException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The floating browser: an overlay window with a toolbar and a WebView, plus the bubble it turns
 * into when minimized. [ctx] must be a themed context from the overlay service.
 */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled", "InflateParams")
class FloatingWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    initialUrl: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onCloseRequested()
        fun onMinimizedChanged(minimized: Boolean)
        fun onOpenSettings()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val minWidth = dp(240)
    private val minHeight = dp(160)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val statusBarHeight = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        .let { if (it != 0) ctx.resources.getDimensionPixelSize(it) else 0 }
    private val main = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(InputMethodManager::class.java)

    private val root = LayoutInflater.from(ctx).inflate(R.layout.overlay_window, null) as OverlayRootView
    private val toolbar: View = root.findViewById(R.id.toolbar)
    private val dragHandle: ImageView = root.findViewById(R.id.drag_handle)
    private val btnBack: ImageButton = root.findViewById(R.id.btn_back)
    private val btnReload: ImageButton = root.findViewById(R.id.btn_reload)
    private val urlField: EditText = root.findViewById(R.id.url)
    private val barSpacer: View = root.findViewById(R.id.bar_spacer)
    private val btnFit: ImageButton = root.findViewById(R.id.btn_fit)
    private val btnMenu: ImageButton = root.findViewById(R.id.btn_menu)
    private val btnMute: ImageButton = root.findViewById(R.id.btn_mute)
    private val btnLock: ImageButton = root.findViewById(R.id.btn_lock)
    private val btnMinimize: ImageButton = root.findViewById(R.id.btn_minimize)
    private val btnClose: ImageButton = root.findViewById(R.id.btn_close)
    private val progress: View = root.findViewById(R.id.progress)
    private val webHolder: FreezableFrame = root.findViewById(R.id.web_holder)
    private val resizeLeft: View = root.findViewById(R.id.resize_bl)
    private val resizeRight: View = root.findViewById(R.id.resize_br)

    private val bubble = LayoutInflater.from(ctx).inflate(R.layout.overlay_bubble, null)
    private val bubbleIcon: View = bubble.findViewById(R.id.bubble_icon)

    /** The window's normal rectangle in screen pixels (what it returns to after minimize/fullscreen video). */
    private val bounds = Rect()
    private val params = overlayParams(0, 0)
    private val bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
    private var rootAdded = false
    private var bubbleAdded = false
    /** A drag or resize is in progress. */
    private var interacting = false
    /** Screen y of the keyboard's top edge while it's open over our window; 0 otherwise. */
    private var keyboardTop = 0

    private lateinit var webView: WebView
    private var mobileUserAgent = ""
    private var currentUrl = ""
    private var locked = prefs.locked
    private var muted = prefs.muted
    var isMinimized = false
        private set

    /** True while the window holds keyboard focus (and so takes the gamepad from the app behind). */
    private var typing = false
    private var webEditing = false
    private var scriptInjected = false
    /** The page's own fullscreen element (e.g. YouTube's fullscreen button), shown in the browser area. */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFullscreen = false
    private var activeDialog: AlertDialog? = null
    private var fileHintFolder = false
    private var fileHintMultiple = false
    private var fileHintTime = 0L
    private var lastBrowsedDir: File? = null

    /** The page's main video as last reported by the page script; 0x0 when there is none. */
    private var videoW = 0
    private var videoH = 0
    private var videoPlaying = false

    /** Video mode: only the player is shown and the window takes the video's shape. */
    private var videoMode = false
    private var videoSource = VideoSource.MANUAL
    private var videoModePending = false
    /** Aspect ratio the window is currently shaped to (width / height). */
    private var fitAspect = 0f
    /** The window as it was before video mode, to go back to. */
    private val normalBounds = Rect()
    /** Page where the user turned auto-fit off by hand; don't switch it straight back on there. */
    private var autoFitDeclinedUrl: String? = null

    private val isFullscreen get() = fullscreenView != null || pageFullscreen
    private val videoMaximized get() = isFullscreen && prefs.videoFillsScreen
    private val videoAspect get() = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 0f

    init {
        loadBounds()
        webView = createWebView()
        webHolder.addView(webView, matchParent())
        wireToolbar()
        resizeLeft.setOnTouchListener(resizeListener(fromLeft = true))
        resizeRight.setOnTouchListener(resizeListener(fromLeft = false))
        bubbleIcon.setOnTouchListener(bubbleListener())
        // Lets the EditText give up focus without focus jumping straight back into it.
        root.isFocusableInTouchMode = true
        root.onOutsideTouch = { stopTyping() }
        root.onBack = { handleBack() }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) main.post { fitToolbar() }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            trackKeyboard(insets)
            main.post { avoidSystemBars() }
            insets
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep edge "back" swipes from stealing drags that start on our handles.
            for (handle in listOf(dragHandle, resizeLeft, resizeRight)) {
                handle.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
        }
        updateMuteUi()
        updateLockUi()
        applyWindowLayout()
        wm.addView(root, params)
        rootAdded = true
        applyMuteToWebView(webView)
        webView.loadUrl(initialUrl?.let { Prefs.toUrl(it) } ?: prefs.lastUrl ?: prefs.homeUrl)
    }

    // region Public controls

    fun load(input: String) {
        val url = Prefs.toUrl(input) ?: return
        webView.loadUrl(url)
    }

    fun minimize() {
        if (isMinimized) return
        stopTyping()
        activeDialog?.dismiss()
        // Keep the page laid out at full size so it keeps running (and playing audio) while hidden.
        webHolder.freeze()
        isMinimized = true
        applyWindowLayout()
        showBubble()
        listener.onMinimizedChanged(true)
    }

    fun restore() {
        if (!isMinimized) return
        isMinimized = false
        hideBubble()
        webHolder.unfreeze()
        applyWindowLayout()
        listener.onMinimizedChanged(false)
    }

    /** Settings changed elsewhere (e.g. opacity on the settings screen): redraw with them. */
    fun refreshAppearance() {
        applyWindowLayout()
    }

    /** Screen size or rotation changed: keep everything on screen. */
    fun onScreenChanged() {
        main.post {
            if (videoMode) {
                placeVideoWindow(initial = false)
            } else {
                clampBounds()
                applyWindowLayout()
            }
            if (bubbleAdded) {
                clampBubble()
                wm.updateViewLayout(bubble, bubbleParams)
            }
        }
    }

    fun destroy() {
        main.removeCallbacks(exitWhenVideoGone)
        activeDialog?.dismiss()
        hideBubble()
        if (rootAdded) {
            wm.removeView(root)
            rootAdded = false
        }
        webHolder.removeAllViews()
        webView.stopLoading()
        webView.destroy()
        CookieManager.getInstance().flush()
    }

    // endregion

    // region Window placement

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun screen(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Rect(0, 0, b.width(), b.height())
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun loadBounds() {
        val s = screen()
        var w = prefs.width
        var h = prefs.height
        if (w <= 0 || h <= 0) {
            w = (s.width() * 0.55f).roundToInt()
            h = (s.height() * 0.75f).roundToInt()
        }
        var x = prefs.x
        var y = prefs.y
        if (x < 0 || y < 0) {
            x = s.width() - w - dp(12)
            y = dp(12)
        }
        bounds.set(x, y, x + w, y + h)
        clampBounds()
    }

    private fun clampBounds() {
        val s = screen()
        val w = bounds.width().clamp(minW(), s.width())
        val h = bounds.height().clamp(minH(), s.height())
        val x = bounds.left.clamp(0, s.width() - w)
        val y = bounds.top.clamp(0, s.height() - h)
        bounds.set(x, y, x + w, y + h)
    }

    private fun saveBounds() {
        if (videoMode) {
            prefs.videoX = bounds.left
            prefs.videoY = bounds.top
            prefs.videoArea = contentArea(bounds).roundToInt()
            return
        }
        prefs.x = bounds.left
        prefs.y = bounds.top
        prefs.width = bounds.width()
        prefs.height = bounds.height()
    }

    /** Pushes the current state (normal / minimized / maximized video, typing or not) into the window. */
    private fun applyWindowLayout() {
        val touchFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        when {
            isMinimized -> {
                // 1x1, invisible and untouchable, but still "showing" so the page keeps running.
                params.x = bounds.left
                params.y = bounds.top
                params.width = 1
                params.height = 1
                params.alpha = 0f
                params.flags = params.flags or touchFlag
            }
            videoMaximized -> {
                val s = screen()
                params.x = 0
                params.y = 0
                params.width = s.width()
                params.height = s.height()
                params.alpha = prefs.opacity
                params.flags = params.flags and touchFlag.inv()
            }
            else -> {
                var top = bounds.top
                var height = bounds.height()
                if (typing && keyboardTop > 0 && bounds.bottom > keyboardTop) {
                    // Overlays don't get pushed up by the keyboard, so lift (and if needed shrink) ourselves.
                    top = (keyboardTop - height).coerceAtLeast(statusBarHeight)
                    height = (keyboardTop - top).coerceAtLeast(dp(90))
                }
                params.x = bounds.left
                params.y = top
                params.width = bounds.width()
                params.height = height
                params.alpha = prefs.opacity
                params.flags = params.flags and touchFlag.inv()
            }
        }
        val focusFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val outsideFlag = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (typing && !isMinimized) {
            params.flags = (params.flags and focusFlag.inv()) or outsideFlag
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            params.flags = (params.flags or focusFlag) and outsideFlag.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (rootAdded) wm.updateViewLayout(root, params)
    }

    private fun moveListener() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (locked || videoMaximized || isMinimized) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interacting = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = bounds.left
                    startY = bounds.top
                }
                MotionEvent.ACTION_MOVE -> {
                    bounds.offsetTo(startX + (e.rawX - downX).roundToInt(), startY + (e.rawY - downY).roundToInt())
                    clampBounds()
                    applyWindowLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endInteraction()
            }
            return true
        }
    }

    private fun resizeListener(fromLeft: Boolean) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private val start = Rect()

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (locked || videoMaximized || isMinimized) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interacting = true
                    downX = e.rawX
                    downY = e.rawY
                    start.set(bounds)
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen()
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    if (videoMode && fitAspect > 0f) {
                        resizeVideo(start, dx, dy, fromLeft)
                        return true
                    }
                    val bottom = (start.bottom + dy).clamp(start.top + minHeight, s.height())
                    if (fromLeft) {
                        val left = (start.left + dx).clamp(0, start.right - minWidth)
                        bounds.set(left, start.top, start.right, bottom)
                    } else {
                        val right = (start.right + dx).clamp(start.left + minWidth, s.width())
                        bounds.set(start.left, start.top, right, bottom)
                    }
                    applyWindowLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endInteraction()
            }
            return true
        }
    }

    private fun endInteraction() {
        interacting = false
        saveBounds()
        // Give the last move a moment to land before checking it against the system bars.
        main.postDelayed({ avoidSystemBars() }, 150)
    }

    /**
     * The status and navigation bars draw above overlays, so a window under them loses its toolbar or
     * resize corners. Insets report how much of our window is covered by bars that are showing right now;
     * slide down / shrink until nothing is. In a full-screen game the bars are hidden and nothing moves.
     */
    private fun avoidSystemBars() {
        if (interacting || isMinimized || videoMaximized || keyboardTop > 0 || !rootAdded) return
        val insets = root.rootWindowInsets ?: return
        val coveredTop: Int
        val coveredBottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            // Only visible system bars and cutouts; mandatorySystemGestures are always non-zero at screen
            // edges even in immersive launchers like Cocoon FE and would trigger an endless layout loop.
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout(),
            )
            coveredTop = i.top
            coveredBottom = i.bottom
        } else {
            @Suppress("DEPRECATION")
            coveredTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            coveredBottom = insets.systemWindowInsetBottom
        }
        if (coveredTop <= 0 && coveredBottom <= 0) return
        val s = screen()
        var top = bounds.top
        var bottom = bounds.bottom
        if (coveredTop > 0 && top < coveredTop) {
            val shift = (coveredTop - top).coerceAtMost((s.height() - coveredBottom - bottom).coerceAtLeast(0))
            top += shift
            bottom += shift
        }
        if (coveredBottom > 0 && bottom > s.height() - coveredBottom) {
            bottom = (s.height() - coveredBottom).coerceAtLeast(top + minH())
        }
        if (top == bounds.top && bottom == bounds.bottom) return
        // In video mode a shorter window must also get narrower, or the video gets black bars.
        val right = if (videoMode && fitAspect > 0f) {
            bounds.left + max(((bottom - top - chromeHeight()) * fitAspect).roundToInt(), compactBarWidth()) + chromeWidth()
        } else {
            bounds.right
        }
        val prev = Rect(bounds)
        bounds.set(bounds.left, top, right, bottom)
        clampBounds()
        if (bounds != prev) applyWindowLayout()
    }

    private fun trackKeyboard(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        val top = if (!typing || !insets.isVisible(WindowInsets.Type.ime())) {
            0
        } else {
            val covered = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (covered > 0) {
                val frame = IntArray(2)
                root.getLocationOnScreen(frame)
                frame[1] + root.height - covered
            } else {
                keyboardTop // Already lifted clear of it.
            }
        }
        if (top != keyboardTop) {
            keyboardTop = top
            main.post { applyWindowLayout() }
        }
    }

    // endregion

    // region Bubble (minimized state)

    private fun showBubble() {
        if (bubbleAdded) return
        val s = screen()
        val size = dp(58)
        bubbleParams.x = if (prefs.bubbleX >= 0) prefs.bubbleX else s.width() - size - dp(8)
        // Default to the right edge, below where a floating window's title bar usually sits.
        bubbleParams.y = if (prefs.bubbleY >= 0) prefs.bubbleY else s.height() * 3 / 5
        clampBubble()
        wm.addView(bubble, bubbleParams)
        bubbleAdded = true
    }

    private fun hideBubble() {
        if (!bubbleAdded) return
        wm.removeView(bubble)
        bubbleAdded = false
    }

    private fun clampBubble() {
        val s = screen()
        val size = dp(58)
        bubbleParams.x = bubbleParams.x.clamp(0, s.width() - size)
        bubbleParams.y = bubbleParams.y.clamp(0, s.height() - size)
    }

    private fun bubbleListener() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            showBubbleMenu()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    longPressed = false
                    v.isPressed = true
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.roundToInt()
                        bubbleParams.y = startY + dy.roundToInt()
                        clampBubble()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        prefs.bubbleX = bubbleParams.x
                        prefs.bubbleY = bubbleParams.y
                    } else if (!longPressed) {
                        restore()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                }
            }
            return true
        }
    }

    private fun showBubbleMenu() {
        val popup = PopupMenu(ctx, bubbleIcon)
        popup.menu.add(0, 1, 0, R.string.restore)
        popup.menu.add(0, 2, 1, R.string.menu_brave_floating)
        popup.menu.add(0, 3, 2, R.string.close)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> restore()
                2 -> Brave.open(ctx, currentUrl.ifEmpty { prefs.homeUrl }, Rect(bounds))
                3 -> listener.onCloseRequested()
            }
            true
        }
        popup.show()
    }

    // endregion

    // region Toolbar

    private fun wireToolbar() {
        val mover = moveListener()
        dragHandle.setOnTouchListener(mover)
        toolbar.setOnTouchListener(mover)

        btnBack.setOnClickListener {
            when {
                isFullscreen -> exitFullscreen()
                webView.canGoBack() -> webView.goBack()
            }
        }
        btnReload.setOnClickListener { webView.reload() }
        btnFit.setOnClickListener { if (videoMode) exitVideoMode(byUser = true) else enterVideoMode(VideoSource.MANUAL) }
        btnMenu.setOnClickListener { showMenu() }
        btnMute.setOnClickListener { toggleMute() }
        btnLock.setOnClickListener { toggleLock() }
        btnMinimize.setOnClickListener { minimize() }
        btnClose.setOnClickListener { listener.onCloseRequested() }

        urlField.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                startTyping()
                main.postDelayed({ if (typing && urlField.hasFocus()) imm.showSoftInput(urlField, 0) }, 250)
            }
            false
        }
        urlField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                urlField.setText(currentUrl)
                main.postDelayed({ if (!urlField.hasFocus() && !webEditing) stopTyping() }, 300)
            }
        }
        urlField.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                load(urlField.text.toString())
                stopTyping()
                true
            } else {
                false
            }
        }
    }

    /** Drops the less important buttons when the window is narrow so the address bar stays usable. */
    private fun fitToolbar() {
        if (videoMode) {
            btnReload.visibility = View.GONE
            btnBack.visibility = View.GONE
            return
        }
        val widthDp = root.width / density
        val fit = if (btnFit.visibility == View.VISIBLE) 34 else 0
        btnReload.visibility = if (widthDp >= 374 + fit) View.VISIBLE else View.GONE
        btnBack.visibility = if (widthDp >= 324 + fit) View.VISIBLE else View.GONE
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack() || isFullscreen) 1f else 0.35f
    }

    private fun toggleMute() {
        muted = !muted
        prefs.muted = muted
        updateMuteUi()
        applyMuteToWebView(webView)
        Toast.makeText(ctx, if (muted) R.string.muted else R.string.unmuted, Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteUi() {
        btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        btnMute.imageTintList = ColorStateList.valueOf(ctx.getColor(if (muted) R.color.accent else R.color.text))
        btnMute.contentDescription = ctx.getString(if (muted) R.string.unmute else R.string.mute)
    }

    private fun toggleLock() {
        locked = !locked
        prefs.locked = locked
        updateLockUi()
        Toast.makeText(ctx, if (locked) R.string.locked else R.string.unlocked, Toast.LENGTH_SHORT).show()
    }

    private fun updateLockUi() {
        btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        btnLock.imageTintList = ColorStateList.valueOf(ctx.getColor(if (locked) R.color.accent else R.color.text))
        root.setBackgroundResource(if (locked) R.drawable.bg_window_locked else R.drawable.bg_window)
        dragHandle.alpha = if (locked) 0.3f else 1f
        val handles = if (locked || videoMaximized) View.GONE else View.VISIBLE
        resizeLeft.visibility = handles
        resizeRight.visibility = handles
    }

    private fun showMenu() {
        val popup = PopupMenu(ctx, btnMenu)
        val menu = popup.menu
        menu.add(0, MENU_FORWARD, 0, R.string.forward).isEnabled = webView.canGoForward()
        menu.add(0, MENU_RELOAD, 1, R.string.reload)
        menu.add(0, MENU_HOME, 2, R.string.menu_home)
        menu.add(0, MENU_BRAVE_FLOATING, 3, R.string.menu_brave_floating)
        menu.add(0, MENU_BRAVE, 4, R.string.menu_brave)
        menu.add(0, MENU_DESKTOP, 5, R.string.menu_desktop).apply {
            isCheckable = true
            isChecked = prefs.desktopMode
        }
        menu.add(0, MENU_OPACITY, 6, ctx.getString(R.string.menu_opacity, (prefs.opacity * 100).roundToInt()))
        menu.add(0, MENU_VIDEO_FULL, 7, R.string.menu_video_full).apply {
            isCheckable = true
            isChecked = prefs.videoFillsScreen
        }
        menu.add(0, MENU_AUTO_FIT, 8, R.string.menu_auto_fit).apply {
            isCheckable = true
            isChecked = prefs.autoFitVideo
        }
        menu.add(0, MENU_BLOCK_ADS, 9, R.string.menu_block_ads).apply {
            isCheckable = true
            isChecked = prefs.blockAds
        }
        menu.add(0, MENU_SET_HOME, 10, R.string.menu_set_home)
        menu.add(0, MENU_SETTINGS, 11, R.string.menu_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FORWARD -> webView.goForward()
                MENU_RELOAD -> webView.reload()
                MENU_HOME -> webView.loadUrl(prefs.homeUrl)
                MENU_BRAVE_FLOATING -> if (Brave.open(ctx, pageUrl(), Rect(bounds))) minimize()
                MENU_BRAVE -> if (Brave.open(ctx, pageUrl())) minimize()
                MENU_DESKTOP -> {
                    prefs.desktopMode = !prefs.desktopMode
                    applyUserAgent(webView)
                    webView.reload()
                }
                MENU_OPACITY -> showOpacityPanel()
                MENU_VIDEO_FULL -> {
                    prefs.videoFillsScreen = !prefs.videoFillsScreen
                    applyWindowLayout()
                    updateLockUi()
                }
                MENU_AUTO_FIT -> {
                    prefs.autoFitVideo = !prefs.autoFitVideo
                    if (prefs.autoFitVideo) autoFitDeclinedUrl = null
                }
                MENU_BLOCK_ADS -> {
                    prefs.blockAds = !prefs.blockAds
                    webView.evaluateJavascript("window.__ovlSetBlockAds && window.__ovlSetBlockAds(${prefs.blockAds})", null)
                    webView.reload()
                }
                MENU_SET_HOME -> {
                    prefs.homeUrl = pageUrl()
                    Toast.makeText(ctx, R.string.home_saved, Toast.LENGTH_SHORT).show()
                }
                MENU_SETTINGS -> {
                    minimize()
                    listener.onOpenSettings()
                }
            }
            true
        }
        popup.show()
    }

    private fun pageUrl() = currentUrl.ifEmpty { prefs.homeUrl }

    /**
     * Opacity slider (10–100%), applied live. It's its own little popup window, so it stays solid
     * while the browser fades behind it. Being focusable, the D-pad can move the slider too.
     */
    private fun showOpacityPanel() {
        val panel = LayoutInflater.from(ctx).inflate(R.layout.opacity_panel, null)
        val value: TextView = panel.findViewById(R.id.opacity_value)
        val seek: SeekBar = panel.findViewById(R.id.opacity_seek)
        val minPct = (Prefs.MIN_OPACITY * 100).roundToInt()

        fun setPercent(pct: Int) {
            val p = pct.coerceIn(minPct, 100)
            prefs.opacity = p / 100f
            value.text = ctx.getString(R.string.percent, p)
            if (seek.progress != p) seek.progress = p
            applyWindowLayout()
        }

        seek.min = minPct
        seek.max = 100
        seek.keyProgressIncrement = 5
        seek.progress = (prefs.opacity * 100).roundToInt()
        value.text = ctx.getString(R.string.percent, seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setPercent(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        // Step to the next multiple of 5 either way.
        panel.findViewById<View>(R.id.opacity_minus).setOnClickListener { setPercent((seek.progress - 1) / 5 * 5) }
        panel.findViewById<View>(R.id.opacity_plus).setOnClickListener { setPercent((seek.progress / 5 + 1) * 5) }
        for ((id, pct) in listOf(R.id.opacity_25 to 25, R.id.opacity_50 to 50, R.id.opacity_75 to 75, R.id.opacity_100 to 100)) {
            panel.findViewById<View>(id).setOnClickListener { setPercent(pct) }
        }

        val popup = PopupWindow(panel, dp(280), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ctx.getDrawable(R.drawable.bg_card) ?: ColorDrawable(Color.DKGRAY))
        popup.elevation = dp(8).toFloat()
        popup.isOutsideTouchable = true
        popup.showAsDropDown(btnMenu, 0, dp(4), Gravity.END)
    }

    // endregion

    // region Keyboard focus

    private fun startTyping() {
        if (typing || isMinimized) return
        typing = true
        applyWindowLayout()
    }

    private fun stopTyping() {
        webEditing = false
        if (!typing) return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        if (urlField.hasFocus()) root.requestFocus()
        typing = false
        keyboardTop = 0
        applyWindowLayout()
    }

    private fun onWebEditStart() {
        webEditing = true
        if (!typing) {
            startTyping()
            webView.requestFocus()
            main.postDelayed({ if (typing && webEditing) imm.showSoftInput(webView, 0) }, 250)
        }
    }

    private fun onWebEditEnd() {
        webEditing = false
        if (!urlField.hasFocus()) stopTyping()
    }

    private fun handleBack() {
        when {
            isFullscreen -> exitFullscreen()
            typing -> stopTyping()
            webView.canGoBack() -> webView.goBack()
        }
    }

    // endregion

    // region WebView

    private fun createWebView(): WebView = object : WebView(ctx) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            // Keep the WebView active and un-throttled even when custom launchers (e.g. Cocoon FE)
            // or full-screen games change window/display visibility states underneath.
            super.onWindowVisibilityChanged(View.VISIBLE)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(true)
        }
    }.apply {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = true
        // Keep the renderer at foreground priority even though our service has no visible Activity
        // and the overlay window uses FLAG_NOT_FOCUSABLE.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        // Present as regular Chrome rather than an embedded WebView; some sites serve WebViews a worse page.
        mobileUserAgent = settings.userAgentString
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        applyUserAgent(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "__ovlBridge")
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun applyUserAgent(view: WebView) {
        view.settings.userAgentString = if (prefs.desktopMode) {
            mobileUserAgent
                .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "")
        } else {
            mobileUserAgent
        }
    }

    /** The renderer was killed (usually low memory while a game runs). Rebuild instead of crashing. */
    private fun recreateWebView() {
        exitFullscreen()
        exitVideoMode(byUser = false)
        val old = webView
        webHolder.removeView(old)
        old.destroy()
        webView = createWebView()
        webHolder.addView(webView, 0, matchParent())
        applyMuteToWebView(webView)
        webView.loadUrl(pageUrl())
    }

    private fun setUrlText(url: String) {
        if (!urlField.hasFocus()) urlField.setText(url)
    }

    private fun inject(view: WebView) {
        view.evaluateJavascript(PAGE_SCRIPT, null)
        view.evaluateJavascript("window.__ovlSetBlockAds && window.__ovlSetBlockAds(${prefs.blockAds})", null)
        applyMuteToWebView(view)
    }

    private fun applyMuteToWebView(view: WebView) {
        view.evaluateJavascript("window.__ovlSetMuted && window.__ovlSetMuted($muted)", null)
        try {
            val providerField = HiddenApiBypass.getInstanceFields(WebView::class.java)
                .filterIsInstance<Field>()
                .firstOrNull { it.name == "mProvider" }
                ?.apply { isAccessible = true }
            val provider = providerField?.get(view) ?: return
            val awContentsField = provider.javaClass.declaredFields
                .firstOrNull { it.name == "mAwContents" }
                ?.apply { isAccessible = true }
            val awContents = awContentsField?.get(provider) ?: return
            awContents.javaClass.getMethod("setAudioMuted", Boolean::class.javaPrimitiveType)
                .invoke(awContents, muted)
        } catch (_: Throwable) {
            // Page script fallback handles HTML5 video/audio muting if internal field names differ.
        }
    }

    private fun openExternally(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            minimize()
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun handleIntentUrl(view: WebView, url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (!openExternally(intent)) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && URLUtil.isNetworkUrl(fallback)) view.loadUrl(fallback)
        }
    }

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            ctx.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(ctx, ctx.getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(App.TAG, "Download failed", e)
            Toast.makeText(ctx, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (prefs.blockAds && AdBlocker.shouldBlock(request)) {
                return AdBlocker.emptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob", "javascript" -> false
                else -> {
                    // Links into other apps only open on a real tap, never from a page redirecting itself.
                    if (request.hasGesture()) {
                        if (uri.scheme.equals("intent", ignoreCase = true)) {
                            handleIntentUrl(view, uri.toString())
                        } else if (!openExternally(Intent(Intent.ACTION_VIEW, uri))) {
                            Toast.makeText(ctx, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            setUrlText(url)
            scriptInjected = false
            pageFullscreen = false
            // A new page: the old video is gone. (YouTube moving between videos doesn't come through here.)
            videoW = 0
            videoH = 0
            exitVideoMode(byUser = false)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            scriptInjected = true
            inject(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            scriptInjected = true
            inject(view)
            updateNavButtons()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // YouTube switches pages without reloading; leaving the video for home or search ends video mode.
            if (videoMode && videoSource != VideoSource.FULLSCREEN && isYouTubeVideoUrl(currentUrl) && !isYouTubeVideoUrl(url)) {
                exitVideoMode(byUser = false)
            }
            currentUrl = url
            prefs.lastUrl = url
            setUrlText(url)
            updateNavButtons()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) main.post { recreateWebView() }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (videoMode) {
                // No progress line in video mode: it would knock the window off the video's shape.
            } else if (newProgress in 1..99) {
                progress.visibility = View.VISIBLE
                progress.scaleX = newProgress / 100f
            } else {
                progress.visibility = View.INVISIBLE
            }
            if (newProgress >= 30 && !scriptInjected) {
                scriptInjected = true
                inject(view)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            val recentHint = System.currentTimeMillis() - fileHintTime < 3000L
            val mode = fileChooserParams?.mode ?: 0
            // FileChooserParams.MODE_OPEN_FOLDER is 2 in Chromium WebView
            val pickFolder = (mode == 2) || (recentHint && fileHintFolder)
            val allowMultiple = (mode == FileChooserParams.MODE_OPEN_MULTIPLE) || (recentHint && fileHintMultiple) || pickFolder
            val acceptTypes = fileChooserParams?.acceptTypes
            showFileChooserDialog(filePathCallback, pickFolder, allowMultiple, acceptTypes)
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            jsDialog(result, AlertDialog.Builder(ctx).setTitle(hostOf(url)).setMessage(message)) { result.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val builder = AlertDialog.Builder(ctx)
                .setTitle(hostOf(url))
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
            jsDialog(result, builder) { result.confirm() }
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            val input = EditText(ctx).apply {
                setText(defaultValue)
                setSingleLine()
            }
            val box = FrameLayout(ctx).apply {
                setPadding(dp(20), dp(4), dp(20), 0)
                addView(input)
            }
            val builder = AlertDialog.Builder(ctx)
                .setTitle(hostOf(url))
                .setMessage(message)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
            jsDialog(result, builder) { result.confirm(input.text.toString()) }
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /** Called from page JavaScript on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun editStart() {
            main.post { onWebEditStart() }
        }

        @JavascriptInterface
        fun editEnd() {
            main.post { onWebEditEnd() }
        }

        @JavascriptInterface
        fun select(json: String, multiple: Boolean) {
            main.post { showSelect(json, multiple) }
        }

        @JavascriptInterface
        fun video(info: String) {
            main.post { onVideoInfo(info) }
        }

        @JavascriptInterface
        fun fullscreen(on: Boolean) {
            main.post { onPageFullscreen(on) }
        }

        @JavascriptInterface
        fun fileHint(isDir: Boolean, multiple: Boolean) {
            fileHintFolder = isDir
            fileHintMultiple = multiple
            fileHintTime = System.currentTimeMillis()
        }
    }

    // endregion

    // region Page fullscreen (e.g. YouTube's fullscreen button)

    private fun onPageFullscreen(on: Boolean) {
        if (pageFullscreen == on) return
        pageFullscreen = on
        if (on) {
            if (!prefs.videoFillsScreen) enterVideoMode(VideoSource.FULLSCREEN)
        } else if (videoMode && videoSource == VideoSource.FULLSCREEN) {
            exitVideoMode(byUser = false)
        }
        applyWindowLayout()
        updateLockUi()
        updateNavButtons()
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        webView.visibility = View.GONE
        // The video fills the browser area; the toolbar stays so the window can still be moved or minimized.
        webHolder.addView(view, matchParent())
        applyWindowLayout()
        updateLockUi()
        updateNavButtons()
        // Fullscreen means "just the video": shape the window to it too.
        if (!prefs.videoFillsScreen) enterVideoMode(VideoSource.FULLSCREEN)
    }

    private fun exitFullscreen() {
        val view = fullscreenView
        val wasPageFs = pageFullscreen
        if (view == null && !wasPageFs) return
        fullscreenView = null
        pageFullscreen = false
        if (view != null) {
            webHolder.removeView(view)
            webView.visibility = View.VISIBLE
        }
        val callback = fullscreenCallback
        fullscreenCallback = null
        callback?.onCustomViewHidden()
        if (wasPageFs) {
            webView.evaluateJavascript("window.__ovlExitFullscreen && window.__ovlExitFullscreen()", null)
        }
        if (videoMode && videoSource == VideoSource.FULLSCREEN) exitVideoMode(byUser = false)
        applyWindowLayout()
        updateLockUi()
        updateNavButtons()
    }

    // endregion

    // region Video mode

    /** Leaves video mode once the video has really gone, not just blinked out while switching source. */
    private val exitWhenVideoGone = Runnable {
        if (videoMode && videoW == 0 && !isFullscreen) exitVideoMode(byUser = false)
    }

    private fun onVideoInfo(info: String) {
        val v = info.split(',').map { it.toIntOrNull() ?: 0 }
        if (v.size < 3) return
        videoW = v[0]
        videoH = v[1]
        videoPlaying = v[2] == 1
        webView.keepScreenOn = videoPlaying
        updateFitButton()
        main.removeCallbacks(exitWhenVideoGone)
        when {
            // Between videos (or a video and an ad) there's briefly no video at all; wait before giving up.
            videoMode && videoW == 0 && !isFullscreen -> main.postDelayed(exitWhenVideoGone, VIDEO_GONE_GRACE_MS)
            // The video changed shape (next video, a Short, an ad): reshape the window to match.
            videoMode && !videoMaximized && fitAspect > 0f && videoAspect > 0f && abs(videoAspect - fitAspect) / fitAspect > 0.03f ->
                placeVideoWindow(initial = false)
            !videoMode && shouldAutoFit() -> enterVideoMode(VideoSource.AUTO)
        }
    }

    private fun shouldAutoFit() = prefs.autoFitVideo && videoPlaying && videoW > 0 &&
        isYouTubeVideoUrl(currentUrl) && currentUrl != autoFitDeclinedUrl

    private fun isYouTubeVideoUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return when {
            host == "youtu.be" -> true
            host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith("youtube-nocookie.com") ->
                path.startsWith("/watch") || path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")
            else -> false
        }
    }

    private fun enterVideoMode(source: VideoSource) {
        if (videoMode || videoModePending) return
        videoModePending = true
        webView.evaluateJavascript("window.__ovlVideoMode ? window.__ovlVideoMode(true) : false") { result ->
            videoModePending = false
            if (videoMode || (result != "true" && !isFullscreen)) return@evaluateJavascript
            stopTyping()
            videoMode = true
            videoSource = source
            normalBounds.set(bounds)
            applyToolbarMode()
            placeVideoWindow(initial = true)
        }
    }

    private fun exitVideoMode(byUser: Boolean) {
        if (!videoMode) return
        if (byUser && videoSource == VideoSource.AUTO) autoFitDeclinedUrl = currentUrl
        videoMode = false
        fitAspect = 0f
        webView.evaluateJavascript("window.__ovlVideoMode && window.__ovlVideoMode(false)", null)
        if (byUser) exitFullscreen()
        applyToolbarMode()
        bounds.set(normalBounds)
        clampBounds()
        applyWindowLayout()
    }

    /**
     * Shapes the window so the area below the bar has exactly the video's aspect ratio. It keeps about
     * the same amount of screen as before (the size from last time, when first entering), so switching
     * between a wide video and a tall Short doesn't make the window grow or shrink.
     */
    private fun placeVideoWindow(initial: Boolean) {
        val aspect = if (videoAspect > 0f) videoAspect else 16f / 9f
        fitAspect = aspect
        val s = screen()
        val area = if (initial && prefs.videoArea > 0) prefs.videoArea.toFloat() else contentArea(bounds)
        val maxCw = (s.width() - chromeWidth()).toFloat()
        val maxCh = (s.height() - chromeHeight() - statusBarHeight).toFloat()
        val cw = min(sqrt(area * aspect), min(maxCw, maxCh * aspect)).coerceAtLeast(minVideoContentWidth(maxCh))
        // A tall video in a short screen can end up narrower than the bar; then the window keeps the
        // bar's width and the video gets slim side bars, rather than cutting buttons off.
        val w = max(cw.roundToInt(), compactBarWidth()) + chromeWidth()
        val h = (cw / aspect).roundToInt() + chromeHeight()
        val x = if (initial && prefs.videoX >= 0) prefs.videoX else bounds.left
        val y = if (initial && prefs.videoY >= 0) prefs.videoY else bounds.top
        bounds.set(x, y, x + w, y + h)
        clampBounds()
        applyWindowLayout()
    }

    /** Corner drag in video mode: the window scales but keeps the video's shape. */
    private fun resizeVideo(start: Rect, dx: Int, dy: Int, fromLeft: Boolean) {
        val s = screen()
        val startCw = (start.width() - chromeWidth()).toFloat()
        val startCh = (start.height() - chromeHeight()).toFloat()
        // Follow whichever direction the finger moved more.
        val byX = startCw + if (fromLeft) -dx else dx
        val byY = (startCh + dy) * fitAspect
        var cw = if (abs(byX - startCw) >= abs(byY - startCw)) byX else byY
        val maxCw = if (fromLeft) start.right - chromeWidth() else s.width() - start.left - chromeWidth()
        val maxByHeight = (s.height() - start.top - chromeHeight()) * fitAspect
        cw = min(cw, min(maxCw.toFloat(), maxByHeight))
            .coerceAtLeast(minVideoContentWidth((s.height() - start.top - chromeHeight()).toFloat()))
        val w = max(cw.roundToInt(), compactBarWidth()) + chromeWidth()
        val h = (cw / fitAspect).roundToInt() + chromeHeight()
        if (fromLeft) {
            bounds.set(start.right - w, start.top, start.right, start.top + h)
        } else {
            bounds.set(start.left, start.top, start.left + w, start.top + h)
        }
        applyWindowLayout()
    }

    /** Video mode gets a slim bar with just the window controls; the page's own UI is hidden anyway. */
    private fun applyToolbarMode() {
        toolbar.layoutParams = toolbar.layoutParams.apply { height = toolbarHeight() }
        dragHandle.layoutParams = dragHandle.layoutParams.apply { width = dp(if (videoMode) COMPACT_GRIP_DP else 28) }
        val size = dp(if (videoMode) COMPACT_BUTTON_DP else 34)
        val pad = dp(if (videoMode) 5 else 7)
        for (b in listOf(btnBack, btnReload, btnFit, btnMenu, btnMute, btnLock, btnMinimize, btnClose)) {
            b.layoutParams = b.layoutParams.apply {
                width = size
                height = size
            }
            b.setPadding(pad, pad, pad, pad)
        }
        urlField.visibility = if (videoMode) View.GONE else View.VISIBLE
        barSpacer.visibility = if (videoMode) View.VISIBLE else View.GONE
        progress.visibility = if (videoMode) View.GONE else View.INVISIBLE
        updateFitButton()
        fitToolbar()
    }

    private fun updateFitButton() {
        val show = videoMode || videoW > 0
        if ((btnFit.visibility == View.VISIBLE) != show) {
            btnFit.visibility = if (show) View.VISIBLE else View.GONE
            fitToolbar()
        }
        btnFit.imageTintList = ColorStateList.valueOf(ctx.getColor(if (videoMode) R.color.accent else R.color.text))
        btnFit.contentDescription = ctx.getString(if (videoMode) R.string.exit_video else R.string.fit_video)
    }

    private fun toolbarHeight() = dp(if (videoMode) COMPACT_BUTTON_DP + 2 else 40)

    /** Everything in the window that isn't page: the bar, the progress line and the 1dp border. */
    private fun chromeHeight() = toolbarHeight() + (if (videoMode) 0 else dp(2)) + root.paddingTop + root.paddingBottom

    private fun chromeWidth() = root.paddingLeft + root.paddingRight

    private fun contentArea(r: Rect) =
        (r.width() - chromeWidth()).coerceAtLeast(1).toFloat() * (r.height() - chromeHeight()).coerceAtLeast(1)

    /** Width the slim video-mode bar needs: grip, then fit, menu, mute, lock, minimize and close buttons. */
    private fun compactBarWidth() = dp(2 + COMPACT_GRIP_DP + 6 * COMPACT_BUTTON_DP + 4)

    /** Smallest video width: as wide as the bar if the video's height allows it, so the shape holds. */
    private fun minVideoContentWidth(maxContentHeight: Float) =
        min(compactBarWidth().toFloat(), maxContentHeight * (if (fitAspect > 0f) fitAspect else 16f / 9f))
            .coerceAtLeast(dp(MIN_VIDEO_WIDTH_DP).toFloat())

    private fun minW() = if (videoMode) compactBarWidth() + chromeWidth() else minWidth

    private fun minH() = if (videoMode) dp(60) + chromeHeight() else minHeight

    // endregion

    // region Dialogs

    /** Our own dialogs can float over other apps; WebView's built-in ones can't. */
    private fun showDialog(builder: AlertDialog.Builder): AlertDialog? {
        if (isMinimized) return null
        activeDialog?.dismiss()
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        return try {
            dialog.show()
            activeDialog = dialog
            dialog
        } catch (e: Exception) {
            Log.w(App.TAG, "Couldn't show dialog", e)
            null
        }
    }

    private fun jsDialog(result: JsResult, builder: AlertDialog.Builder, onOk: () -> Unit) {
        var answered = false
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            answered = true
            onOk()
        }
        builder.setOnDismissListener {
            if (!answered) result.cancel()
            activeDialog = null
        }
        if (showDialog(builder) == null) result.cancel()
    }

    private fun showSelect(json: String, multiple: Boolean) {
        val items = try {
            JSONArray(json)
        } catch (_: Exception) {
            return
        }
        val labels = Array(items.length()) { items.getJSONArray(it).optString(0) }
        val picked = BooleanArray(items.length()) { items.getJSONArray(it).optBoolean(1) }
        val builder = AlertDialog.Builder(ctx)
        if (multiple) {
            builder.setMultiChoiceItems(labels, picked) { _, which, isChecked -> picked[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ -> applySelect(picked.indices.filter { picked[it] }) }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setSingleChoiceItems(labels, picked.indexOfFirst { it }) { dialog, which ->
                applySelect(listOf(which))
                dialog.dismiss()
            }
        }
        builder.setOnDismissListener { activeDialog = null }
        showDialog(builder)
    }

    private fun applySelect(indices: List<Int>) {
        webView.evaluateJavascript("window.__ovlApplySelect && window.__ovlApplySelect(${JSONArray(indices)})", null)
    }

    private fun storageRoots(): List<Pair<String, File>> {
        val roots = mutableListOf<Pair<String, File>>()
        val internal = Environment.getExternalStorageDirectory()
        if (internal.exists()) {
            roots.add("📁 Internal Storage (${internal.name})" to internal)
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads.exists()) {
            roots.add("⬇️ Downloads" to downloads)
        }
        // Discover external SD card volumes via StorageManager and /storage
        val seenPaths = mutableSetOf(internal.canonicalPath)
        try {
            val sm = ctx.getSystemService(StorageManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) {
                for (vol in sm.storageVolumes) {
                    val dir = vol.directory ?: continue
                    val canon = dir.canonicalPath
                    if (seenPaths.add(canon) && dir.exists()) {
                        val desc = vol.getDescription(ctx).ifBlank { dir.name }
                        roots.add("💾 $desc (${dir.name})" to dir)
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            File("/storage").listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "emulated" && dir.name != "self") {
                    val canon = dir.canonicalPath
                    if (seenPaths.add(canon)) {
                        roots.add("💾 SD Card (${dir.name})" to dir)
                    }
                }
            }
        } catch (_: Exception) {}
        return roots
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun showFileChooserDialog(
        callback: ValueCallback<Array<Uri>>,
        pickFolder: Boolean,
        allowMultiple: Boolean,
        acceptTypes: Array<String>?,
    ) {
        val startDir = lastBrowsedDir?.takeIf { it.exists() && it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        browseDirectoryDialog(callback, startDir, pickFolder, allowMultiple, acceptTypes)
    }

    private fun browseDirectoryDialog(
        callback: ValueCallback<Array<Uri>>,
        dir: File,
        pickFolder: Boolean,
        allowMultiple: Boolean,
        acceptTypes: Array<String>?,
    ) {
        lastBrowsedDir = dir
        val entries = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var resolved = false

        fun finishWith(uris: Array<Uri>?) {
            if (resolved) return
            resolved = true
            callback.onReceiveValue(uris)
        }

        // 1. System File/Folder Picker option (always works for Internal + SD Card via Android DocumentsUI)
        entries.add(if (pickFolder) "🗂️ System Folder Picker (Internal / SD Card)…" else "🗂️ System File Picker (Internal / SD Card)…")
        actions.add {
            resolved = true
            FilePickerActivity.launch(ctx, pickFolder, allowMultiple, acceptTypes) { uris ->
                callback.onReceiveValue(uris)
            }
        }

        // 2. If picking a folder (or user wants to pick a whole folder), allow selecting current directory directly
        if (hasAllFilesAccess()) {
            entries.add("✅ Use This Folder: ${dir.name.ifEmpty { dir.path }}")
            actions.add {
                val files = collectLocalFiles(dir, maxFiles = 500)
                val uris = if (files.isNotEmpty()) {
                    files.map { Uri.fromFile(it) }.toTypedArray()
                } else {
                    arrayOf(Uri.fromFile(dir))
                }
                finishWith(uris)
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            entries.add("🔓 Allow Direct Storage Browsing (Internal + SD Card)…")
            actions.add {
                finishWith(null)
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            }
        }

        // 3. Switch storage root (Internal Storage / SD Card / Downloads)
        val roots = storageRoots()
        if (roots.size > 1) {
            for ((label, rootDir) in roots) {
                if (rootDir.absolutePath != dir.absolutePath) {
                    entries.add(label)
                    actions.add {
                        resolved = true
                        browseDirectoryDialog(callback, rootDir, pickFolder, allowMultiple, acceptTypes)
                    }
                }
            }
        }

        // 4. Parent directory navigation
        val parent = dir.parentFile
        if (parent != null && parent.canRead()) {
            entries.add("⬆️ .. (Parent folder)")
            actions.add {
                resolved = true
                browseDirectoryDialog(callback, parent, pickFolder, allowMultiple, acceptTypes)
            }
        }

        // 5. List folders and files in current directory
        val children = try {
            dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                entries.add("📁 ${child.name}/")
                actions.add {
                    resolved = true
                    browseDirectoryDialog(callback, child, pickFolder, allowMultiple, acceptTypes)
                }
            } else if (!pickFolder) {
                entries.add("📄 ${child.name}")
                actions.add {
                    finishWith(arrayOf(Uri.fromFile(child)))
                }
            }
        }

        val title = if (pickFolder) "Select Folder: ${dir.path}" else "Select File: ${dir.path}"
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setItems(entries.toTypedArray()) { dialog, which ->
                val act = actions.getOrNull(which)
                dialog.dismiss()
                act?.invoke()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finishWith(null) }
            .setOnDismissListener {
                activeDialog = null
                if (!resolved) finishWith(null)
            }

        if (showDialog(builder) == null) {
            // If minimized, fall back to System Picker directly
            resolved = true
            FilePickerActivity.launch(ctx, pickFolder, allowMultiple, acceptTypes) { uris ->
                callback.onReceiveValue(uris)
            }
        }
    }

    private fun collectLocalFiles(root: File, maxFiles: Int): List<File> {
        val out = mutableListOf<File>()
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < maxFiles) {
            val current = queue.removeFirst()
            val list = try {
                current.listFiles()
            } catch (_: Exception) {
                null
            } ?: continue
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    queue.add(f)
                } else if (f.isFile) {
                    out.add(f)
                    if (out.size >= maxFiles) break
                }
            }
        }
        return out
    }

    private fun hostOf(url: String?): String = url?.let { Uri.parse(it).host } ?: ctx.getString(R.string.app_name)

    // endregion

    private fun matchParent() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    /** coerceIn that tolerates hi < lo (tiny screens), preferring lo. */
    private fun Int.clamp(lo: Int, hi: Int) = if (hi < lo) lo else coerceIn(lo, hi)

    private companion object {
        const val MENU_FORWARD = 1
        const val MENU_RELOAD = 2
        const val MENU_HOME = 3
        const val MENU_BRAVE_FLOATING = 4
        const val MENU_BRAVE = 5
        const val MENU_DESKTOP = 6
        const val MENU_OPACITY = 7
        const val MENU_VIDEO_FULL = 8
        const val MENU_SET_HOME = 9
        const val MENU_SETTINGS = 10
        const val MENU_AUTO_FIT = 11
        const val MENU_BLOCK_ADS = 12
        const val MIN_VIDEO_WIDTH_DP = 120
        const val VIDEO_GONE_GRACE_MS = 3000L
        const val COMPACT_GRIP_DP = 22
        const val COMPACT_BUTTON_DP = 28
    }

    /** How video mode was switched on, which decides what switches it off again. */
    private enum class VideoSource { MANUAL, AUTO, FULLSCREEN }
}
