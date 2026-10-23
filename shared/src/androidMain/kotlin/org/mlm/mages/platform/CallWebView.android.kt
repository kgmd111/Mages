package org.mlm.mages.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import org.koin.compose.koinInject
import org.mlm.mages.ui.components.snackbar.SnackbarManager

// Element-specific actions that the SDK doesn't handle
private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "set_always_on_screen",
    "minimize",
    "im.vector.hangup",
)

private val wantedDeviceTypes = listOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
)

private val audioDeviceComparator = Comparator<AudioDeviceInfo> { a, b ->
    val indexOfA = wantedDeviceTypes.indexOf(a.type).let { if (it == -1) Int.MAX_VALUE else it }
    val indexOfB = wantedDeviceTypes.indexOf(b.type).let { if (it == -1) Int.MAX_VALUE else it }
    indexOfA.compareTo(indexOfB)
}

private fun deviceName(type: Int, name: String): String {
    val typePart = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        else -> "Unknown"
    }
    val isBuiltIn = type in listOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
    )
    return if (isBuiltIn) typePart else "$typePart - $name"
}

private fun widgetOrigin(widgetUrl: String?): String? {
    if (widgetUrl.isNullOrBlank()) return null
    return runCatching {
        val uri = java.net.URI(widgetUrl)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }.getOrNull()
}

private fun listAudioDevices(audioManager: AudioManager): List<AudioDeviceInfo> {
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }.filter { it.type in wantedDeviceTypes && it.isSink }
        .sortedWith(audioDeviceComparator)
}

private fun audioDevicesJson(devices: List<AudioDeviceInfo>): String = JSONArray().apply {
    devices.forEach { device ->
        val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val isEarpiece = device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val isExternalHeadset =
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        put(JSONObject().apply {
            put("id", device.id.toString())
            put("name", deviceName(device.type, device.productName?.toString() ?: ""))
            put("isSpeaker", isSpeaker)
            put("isEarpiece", isEarpiece)
            put("isExternalHeadset", isExternalHeadset)
        })
    }
}.toString()

private val INSTALL_AUDIO_BRIDGE_JS = """
    (function () {
        if (window.__MagesAudioBridgeInstalled) {
            if (window.androidNativeBridge) window.androidNativeBridge.onBridgeReady();
            return;
        }
        var attempts = 0;
        function install() {
            if (typeof controls === 'undefined') {
                if (++attempts > 120) return;
                setTimeout(install, 250);
                return;
            }
            window.__MagesAudioBridgeInstalled = true;
            var handler = function (id) {
                if (window.androidNativeBridge) window.androidNativeBridge.setAudioDevice(String(id));
            };
            controls.onAudioDeviceSelect = handler;
            controls.onOutputDeviceSelect = handler;
            controls.onAudioPlaybackStarted = function () {
                if (window.androidNativeBridge) window.androidNativeBridge.onTrackReady();
            };
            if (window.androidNativeBridge) window.androidNativeBridge.onBridgeReady();
        }
        install();
    })();
""".trimIndent()

private class AndroidWebViewAudioBridge(
    private val onAudioDeviceSelected: (String) -> Unit,
    private val onAudioPlaybackStarted: () -> Unit,
    private val onBridgeReadyCallback: () -> Unit,
) {
    @JavascriptInterface
    fun setAudioDevice(id: String) = onAudioDeviceSelected(id)

    @JavascriptInterface
    fun setOutputDevice(id: String) = onAudioDeviceSelected(id)

    @JavascriptInterface
    fun onTrackReady() = onAudioPlaybackStarted()

    @JavascriptInterface
    fun onBridgeReady() = onBridgeReadyCallback()
}

private class WebViewAudioManager(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val onInvalidAudioDeviceAdded: () -> Unit,
) {
    private val audioManager = webView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val disableBluetoothAudioDevices = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    private val isWebViewAudioEnabled = AtomicBoolean(true)
    private val isInCallMode = AtomicBoolean(false)

    @Volatile
    private var ecRequestedDeviceId: String? = null
    private var hasRegisteredCallbacks = false
    private var commsExecutor: ExecutorService? = null

    private val proximitySensorWakeLock: PowerManager.WakeLock? by lazy {
        webView.context.getSystemService<PowerManager>()
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${webView.context.packageName}:ProximitySensorCallWakeLock"
            )
    }

    private val proximitySensorMutex = Mutex()

    private val bridge: AndroidWebViewAudioBridge = AndroidWebViewAudioBridge(
        onAudioDeviceSelected = { id -> onAudioDeviceSelected(id) },
        onAudioPlaybackStarted = { onAudioPlaybackStarted() },
        onBridgeReadyCallback = { scope.launch(Dispatchers.Main) { setAvailableAudioDevices() } },
    )

    init {
        webView.addJavascriptInterface(bridge, "androidNativeBridge")
    }

    @get:RequiresApi(Build.VERSION_CODES.S)
    private val commsDeviceChangedListener: AudioManager.OnCommunicationDeviceChangedListener by lazy {
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            val wantedDevice = ecRequestedDeviceId
            if (wantedDevice != null && wantedDevice != device?.id?.toString()) {
                selectAudioDevice(wantedDevice)
            }
        }
    }

    private val audioDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val validNewDevices = addedDevices.orEmpty().filter { it.type in wantedDeviceTypes && it.isSink }
            if (validNewDevices.isEmpty()) return

            val audioDevices = (listAudioDevices(audioManager) + validNewDevices)
                .distinctBy { it.id }
                .sortedWith(audioDeviceComparator)
            setAvailableAudioDevices(audioDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            setAvailableAudioDevices()
        }
    }

    fun onCallStarted() {
        if (!isInCallMode.compareAndSet(false, true)) return
        audioManager.mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }
        installBridgeCallbacks()
    }

    fun onCallStopped() {
        if (!isInCallMode.compareAndSet(true, false)) return
        if (proximitySensorWakeLock?.isHeld == true) proximitySensorWakeLock?.release()
        audioManager.mode = AudioManager.MODE_NORMAL
        ecRequestedDeviceId = null
        if (!hasRegisteredCallbacks) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audioManager.removeOnCommunicationDeviceChangedListener(commsDeviceChangedListener)
            }
            runCatching { audioManager.clearCommunicationDevice() }
            commsExecutor?.shutdown()
            commsExecutor = null
        }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        hasRegisteredCallbacks = false
    }

    fun installBridgeCallbacks() {
        webView.evaluateJavascript(INSTALL_AUDIO_BRIDGE_JS, null)
    }

    fun setAvailableAudioDevices(devices: List<AudioDeviceInfo> = listAudioDevices(audioManager)) {
        val devicesJson = audioDevicesJson(devices)
        webView.post {
            webView.evaluateJavascript(
                "if (typeof controls !== 'undefined' && typeof controls.setAvailableAudioDevices === 'function') { controls.setAvailableAudioDevices($devicesJson); }",
                null
            )
        }
    }

    private fun onAudioDeviceSelected(id: String) {
        val device = findAudioDevice(id) ?: return
        ecRequestedDeviceId = id
        selectAudioDevice(device)
    }

    private fun onAudioPlaybackStarted() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isInCallMode.get() || hasRegisteredCallbacks) return@postDelayed
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val executor = Executors.newSingleThreadExecutor()
                commsExecutor = executor
                runCatching {
                    audioManager.addOnCommunicationDeviceChangedListener(executor, commsDeviceChangedListener)
                }.onFailure { e ->
                    Log.w("AudioBridge", "Failed to add communication device listener", e)
                }
            }
            setAvailableAudioDevices()
            hasRegisteredCallbacks = true
        }, 2000)
    }

    private fun findAudioDevice(id: String): AudioDeviceInfo? {
        val deviceId = id.toIntOrNull() ?: return null
        @Suppress("DEPRECATION")
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.find { it.id == deviceId }
    }

    @Suppress("WakelockTimeout")
    private fun selectAudioDevice(device: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) {
                runCatching {
                    Log.d("AudioBridge", "Setting communication device: ${device.id} - ${deviceName(device.type, device.productName?.toString() ?: "")}")
                    if (!audioManager.setCommunicationDevice(device)) {
                        Log.w("AudioBridge", "setCommunicationDevice returned false for id=${device.id}")
                    }
                }.onFailure {
                    Log.e("AudioBridge", "Could not set communication device.", it)
                }
            } else {
                runCatching {
                    audioManager.clearCommunicationDevice()
                }.onFailure {
                    Log.e("AudioBridge", "Could not clear communication device.", it)
                }
            }
        } else {
            if (device != null) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && disableBluetoothAudioDevices) {
                    Log.w("AudioBridge", "Bluetooth audio devices are disabled on this Android version")
                    setAudioEnabled(false)
                    onInvalidAudioDeviceAdded()
                    return
                }
                setAudioEnabled(true)
                audioManager.isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                audioManager.isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                audioManager.isSpeakerphoneOn = false
                audioManager.isBluetoothScoOn = false
            }
        }

        scope.launch {
            proximitySensorMutex.withLock {
                if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    if (proximitySensorWakeLock?.isHeld == false) {
                        proximitySensorWakeLock?.acquire()
                    }
                } else if (proximitySensorWakeLock?.isHeld == true) {
                    proximitySensorWakeLock?.release()
                }
            }
        }
    }

    private fun selectAudioDevice(deviceId: String) {
        selectAudioDevice(findAudioDevice(deviceId))
    }

    private fun setAudioEnabled(enabled: Boolean) {
        webView.post {
            if (isWebViewAudioEnabled.getAndSet(enabled) != enabled) {
                webView.evaluateJavascript(
                    "if (typeof controls !== 'undefined' && typeof controls.setAudioEnabled === 'function') { controls.setAudioEnabled($enabled); }",
                    null
                )
                Log.d("AudioBridge", "Setting audio enabled in Element Call: $enabled")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ComposableNaming")
@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: (widgetEnded: Boolean) -> Unit,
    onMinimizeRequested: () -> Unit,
    minimized: Boolean,
    widgetBaseUrl: String?,
    modifier: Modifier,
    onAttachController: (CallWebViewController) -> Unit,
    onDetachController: (CallWebViewController) -> Unit
): CallWebViewController {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val webViewRef = remember { AtomicReference<WebView?>(null) }
    val audioManagerRef = remember { AtomicReference<WebViewAudioManager?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val widgetOriginRule = remember(widgetUrl) { widgetOrigin(widgetUrl) }
    val widgetHost = remember(widgetUrl) {
        runCatching { java.net.URI(widgetUrl).host }.getOrNull()
    }

    LaunchedEffect(widgetUrl) {
        val wv = webViewRef.get() ?: return@LaunchedEffect
        if (wv.url != null && wv.url != widgetUrl) wv.loadUrl(widgetUrl)
    }

    Log.d("WidgetBridge", "Loading URL: $widgetUrl")

    // Helper to send response back to widget for Element-specific actions
    fun sendElementActionResponse(webView: WebView, originalMessage: String) {
        try {
            val response = JSONObject(originalMessage).apply {
                put("response", JSONObject())
            }
            val origin = "*" // HACK: Same as above
            val script = "postMessage(${response}, '$origin')"
            Log.d("WidgetBridge", "Sending Element response: ${response.toString().take(100)}")
            webView.post { webView.evaluateJavascript(script, null) }
        } catch (e: Exception) {
            Log.e("WidgetBridge", "Failed to send response", e)
        }
    }

    fun handleWidgetMessage(webView: WebView, message: String) {
        try {
            val json = JSONObject(message)
            val api = json.optString("api")
            val action = json.optString("action")

            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                if (!json.has("response")) sendElementActionResponse(webView, message)

                when (action) {
                    "io.element.close", "im.vector.hangup" -> onClosed(true)
                    "minimize" -> onMinimizeRequested()
                    else -> {}
                }
                return
            }

            onMessageFromWidget(message)
        } catch (e: Exception) {
            Log.e("WidgetBridge", "Error parsing message, forwarding anyway", e)
            onMessageFromWidget(message)
        }
    }

    val controller = remember {
        object : CallWebViewController {
            override fun sendToWidget(message: String) {
                val webView = webViewRef.get() ?: run {
                    Log.e("WidgetBridge", "WebView is null!")
                    return
                }

                // echo-suppressed post
                val script = "if (window.__MagesPostFromHost) window.__MagesPostFromHost($message); else postMessage($message, '*');"
                webView.post {
                    Log.d("WidgetBridge", "Sending to widget: $message")
                    webView.evaluateJavascript(script) { result ->
                        Log.d("WidgetBridge", "postMessage result: $result")
                    }
                }
            }

            override fun close() {}
        }
    }

    LaunchedEffect(controller) {
        onAttachController(controller)
    }

    DisposableEffect(Unit) {
        onDispose {
            audioManagerRef.getAndSet(null)?.onCallStopped()
            onDetachController(controller)
            val wv = webViewRef.getAndSet(null)
            wv?.apply {
                stopLoading()
                removeJavascriptInterface("elementX")
                removeJavascriptInterface("androidNativeBridge")
                webChromeClient = null
                destroy()
            }
        }
    }

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                clipToOutline = true
                clipChildren = true

                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val cornerRadius = with(density) { 16.dp.toPx() }
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                    }
                }

                setBackgroundColor(Color.TRANSPARENT)
                val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                WebView.setWebContentsDebuggingEnabled(debuggable)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setGeolocationEnabled(false)
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true

                val webViewInstance = this

                fun addLegacyElementXBridge() {
                    webViewInstance.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(json: String?) {
                            json?.let { handleWidgetMessage(webViewInstance, it) }
                        }
                    }, "elementX")
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    val origins = setOf(widgetOriginRule ?: "*")
                    val added = runCatching {
                        WebViewCompat.addWebMessageListener(
                            this,
                            "elementX",
                            origins
                        ) { _, message, _, _, _ ->
                            val payload = message.data ?: return@addWebMessageListener
                            handleWidgetMessage(webViewInstance, payload)
                        }
                    }.onFailure {
                        Log.w("WidgetBridge", "Web message listener unavailable, using JS interface", it)
                    }.isSuccess
                    if (!added) addLegacyElementXBridge()
                } else {
                    addLegacyElementXBridge()
                }

                audioManagerRef.set(
                    WebViewAudioManager(
                        webView = this,
                        scope = scope,
                        onInvalidAudioDeviceAdded = { Log.w("AudioBridge", "Invalid audio device added") },
                    )
                )

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val requestHost = request.origin?.host
                        if (requestHost == null || widgetHost == null || requestHost == widgetHost) {
                            request.grant(request.resources)
                        } else {
                            request.deny()
                        }
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d("WebViewConsole", "${it.messageLevel()}: ${it.message()}")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        url: String
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(url.toUri())
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)

                        view.evaluateJavascript(
                            """
                            (function () {
                                if (window.__MagesBridgeInstalled) return;
                                window.__MagesBridgeInstalled = true;
                                window.__MagesEchoBlock = new Set();

                                function keyFor(data) {
                                    if (!data || typeof data !== 'object') return null;
                                    return JSON.stringify({
                                        api: data.api || null,
                                        requestId: data.requestId || null,
                                        action: data.action || null,
                                        hasResponse: Object.prototype.hasOwnProperty.call(data, 'response')
                                    });
                                }

                                window.__MagesPostFromHost = function(payload) {
                                    const key = keyFor(payload);
                                    if (key) window.__MagesEchoBlock.add(key);
                                    window.postMessage(payload, '*');
                                };

                                window.addEventListener('message', function(ev) {
                                    const data = ev.data;
                                    if (!data || typeof data !== 'object') return;
                                    const key = keyFor(data);
                                    const hasResponse = Object.prototype.hasOwnProperty.call(data, 'response');
                                    const shouldForward = (!hasResponse && data.api === 'fromWidget') || (hasResponse && data.api === 'toWidget');
                                    if (shouldForward) {
                                        if (key) window.__MagesEchoBlock.delete(key);
                                        if (typeof elementX !== 'undefined' && elementX.postMessage) {
                                            elementX.postMessage(JSON.stringify(data));
                                        }
                                        return;
                                    }
                                    if (key && window.__MagesEchoBlock.delete(key)) return;
                                });
                            })();
                            """.trimIndent(),
                            null
                        )

                        audioManagerRef.get()?.installBridgeCallbacks()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WidgetBridge", "Page finished: $url")

                        view?.evaluateJavascript(
                            """
                            if (typeof controls !== 'undefined') {
                                controls.onBackButtonPressed = function() {
                                    const payload = { api: 'fromWidget', action: 'minimize' };
                                    if (window.__MagesPostFromHost) {
                                        window.__MagesPostFromHost(payload);
                                    } else if (typeof elementX !== 'undefined' && elementX.postMessage) {
                                        elementX.postMessage(JSON.stringify(payload));
                                    }
                                };
                            }
                            """.trimIndent(),
                            null
                        )

                        audioManagerRef.get()?.let {
                            it.onCallStarted()
                            it.installBridgeCallbacks()
                        }
                    }
                }

                webViewRef.set(this)
                loadUrl(widgetUrl)
            }
        },
        update = { webView ->
            webViewRef.set(webView)
            webView.invalidateOutline()
        }
    )

    return controller
}
