import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

old_webview_1 = """                    val webView = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )"""

new_webview_1 = """                    val webView = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // Maximize hardware acceleration & zero-copy rendering hints
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                        }"""

if old_webview_1 in content:
    content = content.replace(old_webview_1, new_webview_1)
    print("Patched webview 1 successfully")

old_webview_2 = """                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )"""
                                    
new_webview_2 = """                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                                    }"""

if old_webview_2 in content:
    content = content.replace(old_webview_2, new_webview_2)
    print("Patched webview 2 successfully")

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
