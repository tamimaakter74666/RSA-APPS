import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

old_page_finished = """                                    val themeJs = \"\"\"
                                        (function() {
                                            function sendThemeColor() {
                                                var metaTheme = document.querySelector('meta[name="theme-color"]');
                                                if (metaTheme) {
                                                    AndroidDownloader.updateThemeColor(metaTheme.getAttribute('content'));
                                                }
                                            }
                                            sendThemeColor();
                                        })();
                                    \"\"\".trimIndent()
                                    view.evaluateJavascript(themeJs, null)"""

new_page_finished = """                                    val themeJs = \"\"\"
                                        (function() {
                                            function sendThemeColor() {
                                                var metaTheme = document.querySelector('meta[name="theme-color"]');
                                                if (metaTheme) {
                                                    AndroidDownloader.updateThemeColor(metaTheme.getAttribute('content'));
                                                }
                                            }
                                            sendThemeColor();
                                            
                                            // Direct Hardware Engine Bindings
                                            window.HardwareMicroEngine = {
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
                                            };
                                        })();
                                    \"\"\".trimIndent()
                                    view.evaluateJavascript(themeJs, null)"""

if old_page_finished in content:
    content = content.replace(old_page_finished, new_page_finished)
    with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
        f.write(content)
    print("Patched Javascript bridge successfully")
else:
    print("Could not find Javascript bridge snippet")
