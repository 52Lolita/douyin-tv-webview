package com.douyin.tv.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.douyin.tv.webview.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var mouseMode = false
    private var menuVisible = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressMenuFired = false
    private val showMenuAfterLongPressOk = Runnable {
        longPressMenuFired = true
        showMenu()
    }

    /** 与系统“长按”阈值一致，避免部分设备 500ms 仍偏短。 */
    private var okLongPressTimeoutMs: Long = OK_LONG_PRESS_FALLBACK_MS

    private var cursorX = 0f
    private var cursorY = 0f

    /**
     * 短按（repeatCount==0）保持较小步长，便于精细对准；
     * 长按连发时随 [KeyEvent.repeatCount] 增大步长并封顶，便于快速扫屏。
     */
    private fun cursorStepPx(event: KeyEvent): Float {
        val d = resources.displayMetrics.density
        val dp =
            if (event.repeatCount == 0) {
                CURSOR_STEP_INITIAL_DP
            } else {
                (CURSOR_STEP_INITIAL_DP + event.repeatCount * CURSOR_STEP_ACCEL_PER_REPEAT_DP)
                    .coerceAtMost(CURSOR_STEP_MAX_DP)
            }
        return dp * d
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUi()
        setupWebView()
        setupMenuButtons()
        setupBackPressed()
        okLongPressTimeoutMs =
            ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(OK_LONG_PRESS_FALLBACK_MS)
        binding.webView.requestFocus()
    }

    /**
     * 必须在 [dispatchKeyEvent] 里先于 [WebView] 处理：否则部分设备上 WebView 会吞掉事件，
     * [View.setOnKeyListener] 也收不到完整的 DOWN/UP，导致长按无法触发。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuVisible) {
            return super.dispatchKeyEvent(event)
        }
        if (mouseMode) {
            if (handleMouseModeKey(event.keyCode, event)) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (handleBrowseModeKey(event.keyCode, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.requestFocus()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(showMenuAfterLongPressOk)
        super.onDestroy()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    injectScrollbarHideCss()
                }
            }
            webChromeClient = WebChromeClient()

            loadUrl(DEFAULT_URL)
        }
    }

    private fun injectScrollbarHideCss() {
        val css =
            "html,body{overflow:hidden!important;-ms-overflow-style:none;scrollbar-width:none;}" +
                "::-webkit-scrollbar{display:none;width:0;height:0;}"
        val js =
            "(function(){var s=document.createElement('style');s.textContent='" +
                escapeJsForSingleQuoted(css) +
                "';document.head.appendChild(s);})();"
        binding.webView.evaluateJavascript(js, null)
    }

    private fun setupMenuButtons() {
        binding.btnMouseMode.setOnClickListener {
            hideMenu()
            enterMouseMode()
        }
        binding.btnPlayerFullscreen.setOnClickListener {
            hideMenu()
            dispatchKeyboardShortcut(key = "h", code = "KeyH")
        }
        binding.btnWebImmersive.setOnClickListener {
            hideMenu()
            dispatchKeyboardShortcut(key = "y", code = "KeyY")
        }
        binding.btnPlayPause.setOnClickListener {
            hideMenu()
            dispatchKeyboardShortcut(key = " ", code = "Space")
        }
        binding.btnRefresh.setOnClickListener {
            hideMenu()
            binding.webView.reload()
        }
        binding.btnAutoplay.setOnClickListener {
            hideMenu()
            dispatchKeyboardShortcut(
                key = "k",
                code = "KeyK",
                legacyKeyCode = DOM_KEYCODE_K
            )
        }
        binding.btnClearScreen.setOnClickListener {
            hideMenu()
            dispatchKeyboardShortcut(
                key = "j",
                code = "KeyJ",
                legacyKeyCode = DOM_KEYCODE_J
            )
        }
        binding.btnMenuClose.setOnClickListener { hideMenu() }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        menuVisible -> hideMenu()
                        mouseMode -> exitMouseMode()
                        binding.webView.canGoBack() -> binding.webView.goBack()
                        else -> finish()
                    }
                }
            }
        )
    }

    private fun showMenu() {
        menuVisible = true
        binding.menuOverlay.visibility = View.VISIBLE
        binding.menuOverlay.isFocusable = true
        binding.menuOverlay.isFocusableInTouchMode = true

        // 避免 WebView 在菜单可见时抢走焦点，导致 DPAD 上下选不到按钮
        binding.webView.clearFocus()
        binding.webView.isFocusable = false
        binding.webView.isFocusableInTouchMode = false
        binding.menuOverlay.requestFocus()
        binding.btnMouseMode.requestFocus()
    }

    private fun hideMenu() {
        menuVisible = false
        binding.menuOverlay.visibility = View.GONE
        // 恢复 WebView 焦点能力，退出菜单后可以继续键盘注入
        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true
        binding.webView.requestFocus()
    }

    private fun enterMouseMode() {
        mouseMode = true
        binding.cursorView.visibility = View.VISIBLE
        binding.mouseModeHint.visibility = View.VISIBLE
        binding.webView.post {
            cursorX = (binding.root.width - binding.cursorView.width) / 2f
            cursorY = (binding.root.height - binding.cursorView.height) / 2f
            binding.cursorView.translationX = cursorX
            binding.cursorView.translationY = cursorY
        }
        binding.webView.requestFocus()
    }

    private fun exitMouseMode() {
        mouseMode = false
        binding.cursorView.visibility = View.GONE
        binding.mouseModeHint.visibility = View.GONE
        binding.webView.requestFocus()
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val maxX = (binding.root.width - binding.cursorView.width).toFloat().coerceAtLeast(0f)
        val maxY = (binding.root.height - binding.cursorView.height).toFloat().coerceAtLeast(0f)
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        binding.cursorView.translationX = cursorX
        binding.cursorView.translationY = cursorY
    }

    /**
     * 在 WebView 视图坐标系下注入真实 [MotionEvent]（DOWN → UP），与实体鼠标/触控同属系统可信输入；
     * 仅用 JS `dispatchEvent` 的 MouseEvent 多为 `isTrusted=false`，抖音等站点会忽略。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun injectClickAtCursor() {
        binding.webView.post {
            val w = binding.webView.width
            val h = binding.webView.height
            if (w <= 0 || h <= 0) return@post

            val centerXInRoot = cursorX + binding.cursorView.width / 2f
            val centerYInRoot = cursorY + binding.cursorView.height / 2f

            val rootLoc = IntArray(2)
            val webLoc = IntArray(2)
            binding.root.getLocationOnScreen(rootLoc)
            binding.webView.getLocationOnScreen(webLoc)
            val webLeftInRoot = (webLoc[0] - rootLoc[0]).toFloat()
            val webTopInRoot = (webLoc[1] - rootLoc[1]).toFloat()

            val x = (centerXInRoot - webLeftInRoot).coerceIn(0f, w.toFloat() - 1f)
            val y = (centerYInRoot - webTopInRoot).coerceIn(0f, h.toFloat() - 1f)

            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )
            binding.webView.dispatchTouchEvent(down)
            down.recycle()

            val upTime = downTime + TAP_UP_DELAY_MS
            val up = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            binding.webView.dispatchTouchEvent(up)
            up.recycle()
        }
    }

    /**
     * 鼠标模式：在 [dispatchKeyEvent] 中拦截，避免 WebView 抢走方向键/确认键。
     */
    private fun handleMouseModeKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val step = cursorStepPx(event)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursor(0f, -step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursor(0f, step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursor(-step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursor(step, 0f)
                        true
                    }
                    else -> {
                        if (isOkKey(keyCode)) {
                            injectClickAtCursor()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            else -> false
        }
    }

    /**
     * 普通浏览：长按 OK 打开菜单；短按 OK 发 X；左右键映射 R/Z（含 legacy keyCode 以兼容网页监听）。
     */
    private fun handleBrowseModeKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        dispatchKeyboardShortcut(
                            key = "w",
                            code = "KeyW",
                            legacyKeyCode = DOM_KEYCODE_W
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        dispatchKeyboardShortcut(
                            key = "s",
                            code = "KeyS",
                            legacyKeyCode = DOM_KEYCODE_S
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        dispatchKeyboardShortcut(
                            key = "r",
                            code = "KeyR",
                            legacyKeyCode = DOM_KEYCODE_R
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        dispatchKeyboardShortcut(
                            key = "z",
                            code = "KeyZ",
                            legacyKeyCode = DOM_KEYCODE_Z
                        )
                        true
                    }
                    else -> {
                        if (isOkKey(keyCode)) {
                            if (event.repeatCount == 0) {
                                longPressMenuFired = false
                                mainHandler.removeCallbacks(showMenuAfterLongPressOk)
                                mainHandler.postDelayed(
                                    showMenuAfterLongPressOk,
                                    okLongPressTimeoutMs
                                )
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (isOkKey(keyCode)) {
                    mainHandler.removeCallbacks(showMenuAfterLongPressOk)
                    val canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0
                    if (!longPressMenuFired && !canceled) {
                        dispatchKeyboardShortcut(
                            key = "x",
                            code = "KeyX",
                            legacyKeyCode = DOM_KEYCODE_X
                        )
                    }
                    longPressMenuFired = false
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * 不同遥控器/盒子对“确认”上报的键值不一致，尽量都当作 OK。
     */
    private fun isOkKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT -> true
            else -> false
        }

    /**
     * 通过 [KeyboardEvent] 向页面派发按键（与需求文档一致）。
     * [legacyKeyCode]：部分页面仍监听 `keyCode`/`which`，与 PC 行为对齐并同时派发到 `document` 与 `window`。
     */
    private fun dispatchKeyboardShortcut(
        key: String,
        code: String,
        ctrlKey: Boolean = false,
        legacyKeyCode: Int? = null
    ) {
        val k = escapeJsForSingleQuoted(key)
        val c = escapeJsForSingleQuoted(code)
        val ctrl = if (ctrlKey) "true" else "false"
        val js =
            if (legacyKeyCode == null) {
                "(function(){" +
                    "var o={key:'$k',code:'$c',bubbles:true,cancelable:true,ctrlKey:$ctrl};" +
                    "document.dispatchEvent(new KeyboardEvent('keydown',o));" +
                    "document.dispatchEvent(new KeyboardEvent('keyup',o));" +
                    "})();"
            } else {
                val kc = legacyKeyCode
                "(function(){" +
                    "var kc=$kc;" +
                    "function patch(ev){" +
                    "try{" +
                    "Object.defineProperty(ev,'keyCode',{get:function(){return kc;}});" +
                    "Object.defineProperty(ev,'which',{get:function(){return kc;}});" +
                    "}catch(e){}" +
                    "}" +
                    "var o={key:'$k',code:'$c',bubbles:true,cancelable:true,composed:true,ctrlKey:$ctrl};" +
                    "function blip(t){" +
                    "var ts=[document,window];" +
                    "if(document.body)ts.push(document.body);" +
                    "if(document.documentElement)ts.push(document.documentElement);" +
                    "var ae=document.activeElement;if(ae)ts.push(ae);" +
                    "for(var i=0;i<ts.length;i++){" +
                    "try{" +
                    "if(!ts[i]||!ts[i].dispatchEvent)continue;" +
                    "var ev=new KeyboardEvent(t,o);patch(ev);" +
                    "ts[i].dispatchEvent(ev);" +
                    "}catch(e){}" +
                    "}" +
                    "}" +
                    "blip('keydown');blip('keyup');" +
                    "})();"
            }
        binding.webView.evaluateJavascript(js, null)
    }

    private fun escapeJsForSingleQuoted(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    companion object {
        private const val DEFAULT_URL = "https://www.douyin.com/?recommend=1"
        /** 若系统长按阈值异常偏小时，保证菜单仍能较容易触发。 */
        private const val OK_LONG_PRESS_FALLBACK_MS = 500L
        /** 与 PC 网页 KeyboardEvent.keyCode/which 对齐（非 Android [KeyEvent] 常量）。 */
        private const val DOM_KEYCODE_W = 87
        private const val DOM_KEYCODE_S = 83
        private const val DOM_KEYCODE_R = 82
        private const val DOM_KEYCODE_Z = 90
        private const val DOM_KEYCODE_X = 88
        private const val DOM_KEYCODE_K = 75
        private const val DOM_KEYCODE_J = 74
        /** 短按单次移动（dp） */
        private const val CURSOR_STEP_INITIAL_DP = 2.2f
        /** 长按连发时，每多一次 repeat 增加的 dp（越大加速越快） */
        private const val CURSOR_STEP_ACCEL_PER_REPEAT_DP = 0.85f
        /** 长按连发步长上限（dp），避免过快飞出屏幕 */
        private const val CURSOR_STEP_MAX_DP = 12f
        /** 虚拟点击 DOWN→UP 间隔（毫秒），过短可能被页面判定为无效 */
        private const val TAP_UP_DELAY_MS = 50L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
