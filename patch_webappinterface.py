import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

old_interface = """class WebAppInterface(
    private val context: Context,
    private val onThemeColorReceived: ((String) -> Unit)? = null
) {
    @android.webkit.JavascriptInterface
    fun updateThemeColor(color: String?) {
        if (color != null) {
            onThemeColorReceived?.invoke(color)
        }
    }
    @android.webkit.JavascriptInterface
    fun getBase64FromBlobData(base64Data: String, mimeType: String) {"""

new_interface = """class WebAppInterface(
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

if old_interface in content:
    content = content.replace(old_interface, new_interface)
    with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
        f.write(content)
    print("Patched WebAppInterface successfully")
else:
    print("Could not find old_interface snippet")
