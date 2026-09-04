import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# 1. Create HardwareMicroEngineInterface
hardware_engine = """class HardwareMicroEngineInterface(
    private val context: Context,
    private val onThemeColorReceived: ((String) -> Unit)? = null
) {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
    }

    @android.webkit.JavascriptInterface
    fun updateThemeColor(color: String?) {
        if (color != null) {
            onThemeColorReceived?.invoke(color)
        }
    }
    
    @android.webkit.JavascriptInterface
    fun pulse(durationMs: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val safeAmplitude = if (amplitude in 1..255) amplitude else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, safeAmplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    @android.webkit.JavascriptInterface
    fun getDpi(): Float {
        return context.resources.displayMetrics.density
    }

    @android.webkit.JavascriptInterface
    fun getRefreshRate(): Float {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        return displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
    }
    
    // We can pass dynamic safe area top from Compose, or just calculate it from window insets if we have access to the view.
    // For simplicity, we can fetch it via resources (status bar height)
    @android.webkit.JavascriptInterface
    fun getSafeAreaTop(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val px = context.resources.getDimensionPixelSize(resourceId)
            return (px / context.resources.displayMetrics.density).toInt()
        }
        return 0
    }
}

class WebAppInterface(
    private val context: Context
) {"""

# Replace old WebAppInterface up to getBase64FromBlobData
old_webapp_start = """class WebAppInterface(
    private val context: Context,
    private val onThemeColorReceived: ((String) -> Unit)? = null
) {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
    }

    @android.webkit.JavascriptInterface
    fun updateThemeColor(color: String?) {
        if (color != null) {
            onThemeColorReceived?.invoke(color)
        }
    }
    
    @android.webkit.JavascriptInterface
    fun pulse(durationMs: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val safeAmplitude = if (amplitude in 1..255) amplitude else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, safeAmplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    @android.webkit.JavascriptInterface
    fun getBase64FromBlobData(base64Data: String, mimeType: String) {"""

if old_webapp_start in content:
    content = content.replace(old_webapp_start, hardware_engine + "\n    @android.webkit.JavascriptInterface\n    fun getBase64FromBlobData(base64Data: String, mimeType: String) {")
else:
    print("Could not find old_webapp_start")
    
# 2. Update the bindings in AndroidView factories
old_binding_1 = """                        addJavascriptInterface(WebAppInterface(context, onThemeColorReceived), "AndroidDownloader")"""
new_binding_1 = """                        addJavascriptInterface(WebAppInterface(context), "AndroidDownloader")
                        addJavascriptInterface(HardwareMicroEngineInterface(context, onThemeColorReceived), "AndroidNativeEngine")"""

content = content.replace(old_binding_1, new_binding_1)

old_binding_2 = """                                    addJavascriptInterface(WebAppInterface(ctx, onThemeColorReceived), "AndroidDownloader")"""
new_binding_2 = """                                    addJavascriptInterface(WebAppInterface(ctx), "AndroidDownloader")
                                    addJavascriptInterface(HardwareMicroEngineInterface(ctx, onThemeColorReceived), "AndroidNativeEngine")"""

content = content.replace(old_binding_2, new_binding_2)

# 3. Update the injected Javascript to use the new zero-overhead methods
old_js_1 = """                                            window.HardwareMicroEngine = {
                                                pulse: function(ms, amp) { 
                                                    if(window.AndroidDownloader && window.AndroidDownloader.pulse) {
                                                        window.AndroidDownloader.pulse(ms, amp || 255);
                                                    }
                                                },
                                                telemetry: {
                                                    dpi: window.devicePixelRatio,
                                                    refreshRate: 120,
                                                    safeAreaTop: parseInt(getComputedStyle(document.documentElement).getPropertyValue('env(safe-area-inset-top)') || '0')
                                                }
                                            };"""
                                            
new_js_1 = """                                            window.HardwareMicroEngine = {
                                                pulse: function(ms, amp) { 
                                                    if(window.AndroidNativeEngine) {
                                                        window.AndroidNativeEngine.pulse(ms, amp || 255);
                                                    }
                                                },
                                                telemetry: {
                                                    get dpi() { return window.AndroidNativeEngine ? window.AndroidNativeEngine.getDpi() : window.devicePixelRatio; },
                                                    get refreshRate() { return window.AndroidNativeEngine ? window.AndroidNativeEngine.getRefreshRate() : 120; },
                                                    get safeAreaTop() { return window.AndroidNativeEngine ? window.AndroidNativeEngine.getSafeAreaTop() : parseInt(getComputedStyle(document.documentElement).getPropertyValue('env(safe-area-inset-top)') || '0'); }
                                                }
                                            };"""

content = content.replace(old_js_1, new_js_1)

# same for the second injectAntiPwaScript if it exists? Wait, the theme JS was only injected in the first block. Let's check if the old_js is replaced.

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)

print("Done architecture patch")
