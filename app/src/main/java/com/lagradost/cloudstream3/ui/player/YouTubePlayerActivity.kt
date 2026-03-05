package com.lagradost.cloudstream3.ui.player

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.R
import org.json.JSONObject

class YouTubePlayerActivity : AppCompatActivity() {

    private val TAG = "YouTubePlayerActivity"
    private val AUTO_HIDE_DELAY_MS = 4000L
    private val PROGRESS_UPDATE_INTERVAL_MS = 1000L
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var webView: WebView
    private lateinit var overlayRoot: FrameLayout
    private lateinit var btnExit: ImageButton
    private lateinit var textTitle: TextView
    private lateinit var btnCaptions: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var textCurrentTime: TextView
    private lateinit var textDuration: TextView
    private lateinit var btnScale: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var loadingIndicator: ProgressBar

    // State
    private var isPlaying = true
    private var isVisible = true
    private var isScaleCover = false
    private var isSeeking = false
    private var videoDuration = 0.0
    private var videoUrl: String? = null

    private val autoHideRunnable = Runnable { hideOverlay() }
    private val progressUpdateRunnable = Runnable { updateProgress() }
    private var seekDebouncerRunnable: Runnable? = null
    private var isTouching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide System UI and force landscape
        setupWindow()
        setContentView(R.layout.youtube_player_activity)

        videoUrl = intent.getStringExtra("url") ?: return finish()

        bindViews()
        setupListeners()
        initializeWebView()
        
        resetAutoHide()
    }

    private fun setupWindow() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Set immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun bindViews() {
        webView = findViewById(R.id.youtube_webview)
        overlayRoot = findViewById(R.id.overlay_root)
        btnExit = findViewById(R.id.btn_exit)
        textTitle = findViewById(R.id.video_title)
        btnCaptions = findViewById(R.id.btn_captions)
        btnSettings = findViewById(R.id.btn_settings)
        btnRewind = findViewById(R.id.btn_rewind)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnForward = findViewById(R.id.btn_forward)
        textCurrentTime = findViewById(R.id.text_current_time)
        textDuration = findViewById(R.id.text_duration)
        btnScale = findViewById(R.id.btn_scale)
        seekBar = findViewById(R.id.player_seekbar)
        loadingIndicator = findViewById(R.id.loading_indicator)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initializeWebView() {
        loadingIndicator.visibility = View.VISIBLE
        
        webView.apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "onPageFinished: URL loaded $url")
                    loadingIndicator.visibility = View.GONE
                    
                    if (url?.contains("consent.youtube.com") == true) {
                        autoAcceptConsent(view)
                    } else {
                        injectFullscreenCSS()
                        extractVideoTitle()
                        startProgressUpdates()
                    }
                }
            }
        }
        
        webView.loadUrl(videoUrl!!)
    }

    private fun autoAcceptConsent(view: WebView?) {
        val consentJs = """
            (function() {
                var buttons = document.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    var t = buttons[i].innerText.toLowerCase();
                    if (t.includes('accept') || t.includes('agree') || t.includes('mein zustimmen')) {
                        buttons[i].click();
                        return;
                    }
                }
                var form = document.querySelector('form[action*="consent"]');
                if (form) { form.submit(); }
            })();
        """
        view?.evaluateJavascript(consentJs, null)
    }

    private fun injectFullscreenCSS() {
        val injectJs = """
            (function() {
                try {
                    var viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        document.head.appendChild(viewport);
                    }
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                    
                    var css = `
                      * { box-sizing: border-box !important; }
                      html, body {
                        background: #000 !important; 
                        width: 100% !important; height: 100% !important;
                        min-height: 100% !important; margin: 0 !important; padding: 0 !important; 
                        overflow: hidden !important; position: fixed !important;
                        top: 0 !important; left: 0 !important;
                      }
                      #page, #content, ytd-app, ytd-watch-flexy, #player-theater-container,
                      #player, .player-container, #movie_player, .html5-video-player,
                      .html5-video-container {
                        position: fixed !important; 
                        top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important;
                        width: 100% !important; height: 100% !important; min-height: 100% !important;
                        margin: 0 !important; padding: 0 !important; background: #000 !important;
                        z-index: 9999 !important; transform: none !important;
                      }
                      video, .video-stream, .html5-main-video {
                        position: absolute !important; top: 0 !important; left: 0 !important;
                        width: 100% !important; height: 100% !important; min-height: 100% !important;
                        object-fit: contain !important; object-position: center center !important;
                        background: #000 !important; z-index: 1 !important;
                        opacity: 1 !important; visibility: visible !important;
                      }
                      .mobile-topbar-header, .player-controls-top, .watch-below-the-player, 
                      .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom,
                      .ad-showing, .video-ads, .ytp-ad-overlay-container, .ytp-ad-module,
                      .ytp-upnext, .ytp-suggestion-set, .ytp-share-panel, .ytp-watermark,
                      .ytp-title, .ytp-title-link, .ytp-show-cards-title,
                      #secondary, #related, #comments, #masthead, #guide,
                      ytd-watch-next-secondary-results-renderer, ytd-compact-video-renderer { 
                          display: none !important; opacity: 0 !important; visibility: hidden !important;
                          pointer-events: none !important; height: 0 !important; width: 0 !important;
                      }
                      .caption-window, .ytp-caption-window-container, .ytp-caption-segment {
                          display: block !important; opacity: 1 !important; visibility: visible !important; z-index: 20000 !important;
                      }
                    `;
                    var style = document.createElement('style');
                    style.appendChild(document.createTextNode(css));
                    document.head.appendChild(style);
                    
                    var vid = document.querySelector('video');
                    if(vid) { vid.muted = false; vid.play(); }
                } catch(e) { console.error(e.message); }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(injectJs, null)
        handler.postDelayed({ webView.evaluateJavascript(injectJs, null) }, 1500)
    }

    private fun extractVideoTitle() {
        val js = """
            (function() {
                var titleElement = document.querySelector('.ytp-title-link') || 
                                 document.querySelector('.slim-video-metadata-title') || 
                                 document.querySelector('meta[name="title"]');
                                 
                if (titleElement) {
                    return titleElement.innerText || titleElement.content || document.title;
                }
                return document.title;
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            if (res != null && res != "null") {
                val title = res.replace("\\\"", "\"").removeSurrounding("\"").replace("- YouTube", "").trim()
                textTitle.text = title
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        btnExit.setOnClickListener { finish() }
        
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnRewind.setOnClickListener { seekRelative(-10) }
        btnForward.setOnClickListener { seekRelative(10) }
        
        btnScale.setOnClickListener { toggleScaleMode() }
        
        // Settings & Captions mappings (currently mock hooks, to be expanded if needed)
        btnSettings.setOnClickListener { 
            resetAutoHide()
            Toast.makeText(this, "Settings menu", Toast.LENGTH_SHORT).show() 
        }
        btnCaptions.setOnClickListener { 
            resetAutoHide()
            // Toggle CC via JS
            val js = "var btn = document.querySelector('.ytp-subtitles-button'); if(btn) { btn.click(); }"
            webView.evaluateJavascript(js, null)
            Toast.makeText(this, "Toggled Captions", Toast.LENGTH_SHORT).show()
        }

        // Seekbar Dragging
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHide()
                    val time = (progress.toDouble() / 100.0) * videoDuration
                    textCurrentTime.text = formatTime(time.toInt())
                    
                    if (!isTouching) {
                        isSeeking = true
                        seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
                        seekDebouncerRunnable = Runnable {
                            seekTo(time)
                            isSeeking = false
                        }
                        handler.postDelayed(seekDebouncerRunnable!!, 500)
                    }
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                resetAutoHide()
                isSeeking = true
                isTouching = true
                seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                isSeeking = false
                isTouching = false
                seekDebouncerRunnable?.let { handler.removeCallbacks(it) }
                
                val prog = bar?.progress ?: 0
                val time = (prog.toDouble() / 100.0) * videoDuration
                seekTo(time)
            }
        })

        // Overlay Tap to hide/show
        overlayRoot.setOnClickListener {
            if (isVisible) hideOverlay() else showOverlay()
        }
    }

    private fun togglePlayPause() {
        resetAutoHide()
        isPlaying = !isPlaying
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_baseline_pause_24 else R.drawable.ic_baseline_play_arrow_24)
        executeJs("var v = document.querySelector('video'); if (v) { ${if (isPlaying) "v.play()" else "v.pause()"}; }")
    }

    private fun seekRelative(seconds: Int) {
        resetAutoHide()
        executeJs("var v = document.querySelector('video'); if(v) { v.currentTime += $seconds; }")
    }

    private fun seekTo(time: Double) {
        executeJs("var v = document.querySelector('video'); if(v) { v.currentTime = $time; }")
    }

    private fun toggleScaleMode() {
        resetAutoHide()
        isScaleCover = !isScaleCover
        updateScaleMode()
        btnScale.setImageResource(if (isScaleCover) R.drawable.ic_baseline_picture_in_picture_alt_24 else R.drawable.ic_baseline_aspect_ratio_24)
        Toast.makeText(this, if (isScaleCover) "Scale: Fill" else "Scale: Fit", Toast.LENGTH_SHORT).show()
    }

    private fun updateScaleMode() {
        val mode = if (isScaleCover) "cover" else "contain"
        executeJs("""
            var v = document.querySelector('video');
            var v2 = document.querySelector('.html5-main-video');
            if(v) v.style.setProperty('object-fit', '$mode', 'important');
            if(v2) v2.style.setProperty('object-fit', '$mode', 'important');
        """)
    }

    private fun executeJs(script: String) {
        webView.evaluateJavascript("(function() { $script })();", null)
    }

    private fun startProgressUpdates() {
        handler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL_MS)
    }

    private fun stopProgressUpdates() {
        handler.removeCallbacks(progressUpdateRunnable)
    }

    private fun updateProgress() {
        if (!isVisible && !isPlaying) {
            startProgressUpdates()
            return
        }
        
        val js = """
            (function() {
                var skipBtn = document.querySelector('.ytp-ad-skip-button');
                if (skipBtn) { skipBtn.click(); return 'skipped'; }
                var adOverlay = document.querySelector('.ytp-ad-overlay-close-button');
                if (adOverlay) { adOverlay.click(); }
                
                var v = document.querySelector('video');
                if (v) {
                    return JSON.stringify({
                        curr: v.currentTime,
                        dur: v.duration,
                        paused: v.paused
                    });
                }
                return null;
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            if (res != null && res != "null" && res != "\"skipped\"") {
                try {
                    val k = JSONObject(res.replace("\\\"", "\"").removeSurrounding("\""))
                    val curr = k.optDouble("curr")
                    val dur = k.optDouble("dur")
                    
                    if (dur > 0 && curr >= 0) {
                        videoDuration = dur
                        textDuration.text = formatTime(dur.toInt())
                        
                        if (!isSeeking && !isTouching) {
                            textCurrentTime.text = formatTime(curr.toInt())
                            val progress = ((curr / dur) * 100).toInt()
                            seekBar.progress = progress
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing progress JS res", e)
                }
            }
        }
        
        startProgressUpdates()
    }

    private fun showOverlay() {
        if (!isVisible) {
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.animate().alpha(1f).setDuration(200).setListener(null)
            isVisible = true
        }
        resetAutoHide()
    }

    private fun hideOverlay() {
        if (isVisible) {
            val anim = AlphaAnimation(1f, 0f).apply {
                duration = 200
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationEnd(a: Animation?) { overlayRoot.visibility = View.GONE }
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                })
            }
            overlayRoot.startAnimation(anim)
            isVisible = false
            btnPlayPause.clearFocus()
        }
    }

    private fun resetAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun formatTime(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    override fun onDestroy() {
        stopProgressUpdates()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
