package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import com.example.ui.theme.*
import com.airbnb.lottie.compose.*
import androidx.compose.ui.graphics.drawscope.rotate
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == RESULT_OK) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData
            if (clipData != null) {
                val count = clipData.itemCount
                val uris = Array(count) { i -> clipData.getItemAt(i).uri }
                uris
            } else if (dataString != null) {
                arrayOf(Uri.parse(dataString))
            } else {
                null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onResume() {
        super.onResume()
        try {
            val apkFile = File(cacheDir, "rimon_sports_update.apk")
            if (apkFile.exists() && apkFile.length() > 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (packageManager.canRequestPackageInstalls()) {
                        installApk(this, apkFile)
                    }
                } else {
                    installApk(this, apkFile)
                }
            }
        } catch (e: Exception) {
            Log.e("WebViewApp", "Error in onResume apk installation check: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Clean up stale update APK so the new app instance is fresh and clean
        try {
            val apkFile = File(cacheDir, "rimon_sports_update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }
        } catch (e: Exception) {
            // ignore
        }

        // Pre-emptively create WebView internal cache directories synchronously to guarantee they exist before Chromium starts initializing
        preProvisionWebViewCache(this)
        startWebViewCacheMonitoring(this, lifecycleScope)

        // Initialize Firebase programmatically to avoid crashes if Google Services config is missing
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId("1:1911891122:android:5a5b5c5d5e5f")
                    .setProjectId("portal-webview")
                    .setApiKey(BuildConfig.GEMINI_API_KEY.ifEmpty { "AIzaSyFakeKeyPlaceholder" })
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.d("WebViewApp", "Firebase initialized programmatically.")
            }
        } catch (e: Exception) {
            Log.e("WebViewApp", "Could not initialize Firebase programmatically: ${e.message}")
        }

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val configManager = remember { ConfigManager.getInstance(context) }
                val configState by configManager.configState.collectAsState()
                
                var urlState by remember { mutableStateOf(configState.websiteUrl) }
                LaunchedEffect(configState.websiteUrl) {
                    urlState = configState.websiteUrl
                }

                var isLoadingConfig by remember { mutableStateOf(true) }
                var isSplashScreenActive by remember { mutableStateOf(true) }
                var showUpdateDialog by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                var isDownloadingUpdate by remember { mutableStateOf(false) }
                var updateDownloadProgress by remember { mutableStateOf(0f) }
                var updateErrorMessage by remember { mutableStateOf<String?>(null) }
                var isWebViewExpanded by remember { mutableStateOf(true) }
                var showClearCacheDialog by remember { mutableStateOf(false) }
                var showAdminPanel by remember { mutableStateOf(false) }
                var bypassOfflineCheck by remember { mutableStateOf(false) }
                var connectivityRefreshTrigger by remember { mutableStateOf(0) }
                var showExitConfirmationDialog by remember { mutableStateOf(false) }

                if (showAdminPanel) {
                    SecretAdminPanel(onDismissRequest = { showAdminPanel = false })
                }
                
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    isSplashScreenActive = false
                }
                
                val coroutineScope = rememberCoroutineScope()
                val webViewRef = remember { mutableStateOf<WebView?>(null) }

                // Clean Hardware Back Navigation: Back button navigates back in history. If we can't go back further, we go to the home URL of the web app. If currently on the home URL, show beautiful Exit Confirmation dialog.
                BackHandler(enabled = true) {
                    val webView = webViewRef.value
                    if (webView != null) {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            val currentUrl = webView.url?.trimEnd('/') ?: ""
                            val homeUrl = urlState.trimEnd('/')
                            if (currentUrl.isNotEmpty() && currentUrl != homeUrl && currentUrl != "$homeUrl/") {
                                webView.loadUrl(urlState)
                            } else {
                                showExitConfirmationDialog = true
                            }
                        }
                    } else {
                        showExitConfirmationDialog = true
                    }
                }

                // Initialize Remote Config and Fetch URL
                LaunchedEffect(Unit) {
                    try {
                        configManager.startRealTimeSync { newUrl ->
                            urlState = newUrl
                        }
                        isLoadingConfig = false
                        
                        // Dynamic Update notification check on start
                        checkForUpdates(context) { info ->
                            updateInfo = info
                            showUpdateDialog = true
                        }
                    } catch (e: Exception) {
                        Log.e("WebViewApp", "Remote Config fetch error: ${e.message}")
                        isLoadingConfig = false
                        checkForUpdates(context) { info ->
                            updateInfo = info
                            showUpdateDialog = true
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = BentoBackground,
                    topBar = {
                        if (!isWebViewExpanded) {
                            TopAppBar(
                                title = {
                                    Column(
                                        modifier = Modifier.pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    showClearCacheDialog = true
                                                }
                                            )
                                        }
                                    ) {
                                        Text(
                                            text = configState.appName.ifEmpty { "Rimon Sports" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoDarkText
                                        )
                                        val cleanUrlHost = remember(urlState) {
                                            try {
                                                Uri.parse(urlState).host ?: "portal.internal.net"
                                            } catch (e: Exception) {
                                                "portal.internal.net"
                                            }
                                        }
                                        Text(
                                            text = cleanUrlHost,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BentoMutedText
                                        )
                                    }
                                },
                                actions = {
                                    // Manual Sync check matching topbar layout
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                checkForUpdates(context) { info ->
                                                    updateInfo = info
                                                    showUpdateDialog = true
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Sync",
                                            tint = BentoDarkText
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = BentoBackground
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isWebViewExpanded) PaddingValues(0.dp) else innerPadding)
                    ) {
                        // Hidden Top-Left corner long-press trigger for Secret Admin Console
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            showAdminPanel = true
                                        }
                                    )
                                }
                        )

                        val isMaintenanceActive = configState.maintenanceMode || isInsideMaintenanceWindow(configState.maintenanceStartTime, configState.maintenanceEndTime)
                        val connectivityState by rememberConnectivityState(context = context, refreshTrigger = connectivityRefreshTrigger)
                        val isOffline = !connectivityState && !bypassOfflineCheck

                        if (isLoadingConfig) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Purple40)
                            }
                        } else if (isMaintenanceActive) {
                            MaintenanceScreen(
                                endTime = configState.maintenanceEndTime,
                                isNight = isNightTime(),
                                isOffline = false
                            )
                        } else if (isOffline) {
                            MaintenanceScreen(
                                endTime = "",
                                isNight = isNightTime(),
                                isOffline = true,
                                onRetry = {
                                    bypassOfflineCheck = false
                                    connectivityRefreshTrigger++
                                },
                                onProceedAnyway = {
                                    bypassOfflineCheck = true
                                }
                            )
                        } else {
                            WebViewScreen(
                                url = urlState,
                                modifier = Modifier.fillMaxSize(),
                                onWebViewCreated = { webView ->
                                    webViewRef.value = webView
                                }
                            )
                        }

                        // Beautiful Adaptive M3 Dialog styled with correct overlay elements from Design HTML
                        if (showUpdateDialog && updateInfo != null) {
                            AlertDialog(
                                onDismissRequest = { 
                                    showUpdateDialog = false 
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Update Check",
                                        tint = Purple40
                                    )
                                },
                                shape = RoundedCornerShape(28.dp),
                                containerColor = BentoSecondaryContainer,
                                title = {
                                    Text(
                                        text = "নতুন আপডেট উপলব্ধ আছে! ⚡",
                                        fontWeight = FontWeight.Bold,
                                        color = BentoDarkText
                                    )
                                },
                                text = {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        Text(
                                            text = "অ্যাপটির একটি নতুন সংস্করণ (v${updateInfo!!.versionName}) রিলিজ হয়েছে।",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = BentoDarkText,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "নতুন কি আছে (Release Notes):\n${updateInfo!!.releaseNotes}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BentoMutedText
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "আপডেটটি সরাসরি অ্যাপের ভেতরেই অতি নিরাপদে ও দ্রুত ডাউনলোড হয়ে ইন্সটল হবে। কোনো ব্রাউজারে বা লিংকে গিয়ে খোঁজাখুঁজি করার প্রয়োজন নেই!",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Purple40
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            try {
                                                isDownloadingUpdate = true
                                                updateDownloadProgress = 0f
                                                updateErrorMessage = null
                                                downloadAndInstallApk(
                                                    context = context,
                                                    downloadUrl = updateInfo!!.downloadUrl,
                                                    onProgress = { progress ->
                                                        updateDownloadProgress = progress
                                                    },
                                                    onComplete = { apkFile ->
                                                        isDownloadingUpdate = false
                                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                            if (!context.packageManager.canRequestPackageInstalls()) {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "দয়া করে Rimon Sports কে অটোমেটিক ইন্সটল করার পারমিশন দিন এবং ইন্সটল সম্পন্ন করতে ফিরে আসুন।",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                                try {
                                                                    val settingsIntent = Intent(
                                                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                                        Uri.parse("package:${context.packageName}")
                                                                    )
                                                                    context.startActivity(settingsIntent)
                                                                } catch (settingsEx: Exception) {
                                                                    installApk(context, apkFile)
                                                                }
                                                            } else {
                                                                installApk(context, apkFile)
                                                            }
                                                        } else {
                                                            installApk(context, apkFile)
                                                        }
                                                    },
                                                    onError = { error ->
                                                        updateErrorMessage = error
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                Log.e("WebViewApp", "Failed to open update url: ${e.message}")
                                            }
                                            showUpdateDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Purple40,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text("হ্যাঁ, এখনই আপডেট করুন")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { 
                                            showUpdateDialog = false 
                                        }
                                    ) {
                                        Text(text = "পরে করবো", color = Purple40)
                                    }
                                }
                            )
                        }

                        if (isDownloadingUpdate) {
                            AlertDialog(
                                onDismissRequest = {},
                                shape = RoundedCornerShape(28.dp),
                                containerColor = BentoSecondaryContainer,
                                title = {
                                    Text(
                                        text = "আপডেট ডাউনলোড হচ্ছে... 📥",
                                        fontWeight = FontWeight.Bold,
                                        color = BentoDarkText
                                    )
                                },
                                text = {
                                     Column(
                                         modifier = Modifier
                                             .fillMaxWidth()
                                             .padding(vertical = 8.dp),
                                         horizontalAlignment = Alignment.CenterHorizontally
                                     ) {
                                         Text(
                                             text = "অনুগ্রহ করে কিছুক্ষণ অপেক্ষা করুন। অ্যাপের নতুন সংস্করণটি (v${updateInfo?.versionName}) অতি নিরাপদে ডাউনলোড হচ্ছে...",
                                             style = MaterialTheme.typography.bodyMedium,
                                             color = BentoMutedText,
                                             modifier = Modifier.padding(bottom = 16.dp)
                                         )

                                         LinearProgressIndicator(
                                             progress = { updateDownloadProgress },
                                             modifier = Modifier
                                                 .fillMaxWidth()
                                                 .height(8.dp)
                                                 .clip(RoundedCornerShape(4.dp)),
                                             color = Purple40,
                                             trackColor = Color.LightGray
                                         )

                                         Spacer(modifier = Modifier.height(12.dp))

                                         val percentage = (updateDownloadProgress * 100).toInt()
                                         Text(
                                             text = "$percentage%",
                                             style = MaterialTheme.typography.titleMedium,
                                             fontWeight = FontWeight.Bold,
                                             color = Purple40
                                         )

                                         if (updateErrorMessage != null) {
                                             Spacer(modifier = Modifier.height(8.dp))
                                             Text(
                                                 text = "ত্রুটি: $updateErrorMessage",
                                                 color = MaterialTheme.colorScheme.error,
                                                 style = MaterialTheme.typography.bodySmall
                                             )
                                         }
                                     }
                                },
                                confirmButton = {
                                     if (updateErrorMessage != null) {
                                         Button(
                                             onClick = {
                                                 updateErrorMessage = null
                                                 updateDownloadProgress = 0f
                                                 isDownloadingUpdate = false
                                             },
                                             colors = ButtonDefaults.buttonColors(
                                                 containerColor = Purple40,
                                                 contentColor = Color.White
                                              )
                                         ) {
                                             Text("বন্ধ করুন এবং পুনরায় চেষ্টা করুন")
                                         }
                                     } else {
                                         TextButton(
                                             onClick = {
                                                 isDownloadingUpdate = false
                                                 updateDownloadProgress = 0f
                                             }
                                         ) {
                                             Text("বাতিল করুন", color = Color.Gray)
                                         }
                                     }
                                }
                            )
                        }

                        // Beautiful App Exit Confirmation Dialog
                        if (showExitConfirmationDialog) {
                            AlertDialog(
                                onDismissRequest = { showExitConfirmationDialog = false },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.ExitToApp,
                                        contentDescription = "Exit App",
                                        tint = Purple40
                                    )
                                },
                                shape = RoundedCornerShape(28.dp),
                                containerColor = BentoSecondaryContainer,
                                title = {
                                    Text(
                                        text = "অ্যাপ বন্ধ নিশ্চিতকরণ",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = BentoDarkText
                                    )
                                },
                                text = {
                                    Text(
                                        text = "আপনি কি নিশ্চিতভাবে Rimon Sports অ্যাপ থেকে বের হতে চান?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = BentoMutedText
                                    )
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showExitConfirmationDialog = false }
                                    ) {
                                        Text("না", color = Purple40, fontWeight = FontWeight.Bold)
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { (context as? android.app.Activity)?.finish() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Purple40)
                                    ) {
                                        Text("হ্যাঁ", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }

                        // Beautiful M3 Dialog for Troubleshooting & Clearing cache
                        if (showClearCacheDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearCacheDialog = false },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "ক্যাশ ও সেশন মুছুন",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                shape = RoundedCornerShape(28.dp),
                                containerColor = BentoSecondaryContainer,
                                title = {
                                    Text(
                                        text = "পোর্টাল ট্রাবলশুট ও ডাটা রিসেট",
                                        fontWeight = FontWeight.Bold,
                                        color = BentoDarkText
                                    )
                                },
                                text = {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        Text(
                                            text = "এটি পোর্টালের জমানো ক্যাশ (Cache), কুকিজ (Cookies) এবং লোকাল ডাটাবেজ মেমোরি সম্পূর্ণভাবে মুছে ফেলবে। লোডিং সমস্যা, নতুন আপডেট না পাওয়া অথবা লগইন সমস্যা সমাধান করতে এটি ব্যবহার করুন।",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = BentoMutedText
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "এর পর পোর্টাল পেজটি সম্পূর্ণ নতুন করে ফ্রেশভাবে রিলোড হবে।",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BentoMutedText,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showClearCacheDialog = false
                                            clearWebViewData(context, webViewRef.value)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text("সব ডাটা মুছে দিন")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearCacheDialog = false }) {
                                        Text(text = "বাতিল করুন", color = Purple40)
                                    }
                                }
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isSplashScreenActive,
                    enter = fadeIn(),
                    exit = fadeOut(animationSpec = tween(850, easing = FastOutSlowInEasing))
                ) {
                    RimonSportsSplashScreen(
                        appName = configState.appName,
                        appLogoUrl = configState.appLogoUrl,
                        onLogoLongPress = { showAdminPanel = true }
                    )
                }
            }
        }
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

fun preProvisionWebViewCache(context: android.content.Context) {
    try {
        val cacheDir = context.cacheDir
        val candidatePaths = listOf(
            "WebView/Default/HTTP Cache/Code Cache",
            "WebView/Default/Code Cache",
            "org.chromium.android_webview/Default/HTTP Cache/Code Cache",
            "org.chromium.android_webview/Default/Code Cache"
        )
        
        for (relPath in candidatePaths) {
            val baseCacheDir = File(cacheDir, relPath)
            
            // Provision JS Cache directory safely
            val jsDir = File(baseCacheDir, "js")
            if (!jsDir.exists()) {
                val success = jsDir.mkdirs()
                Log.d("WebViewApp", "Pre-created jsDir at $relPath: $success")
            } else {
                // Delete any corruptive files inside JS directory
                listOf(".keep", ".placeholder").forEach { name ->
                    val corruptiveFile = File(jsDir, name)
                    if (corruptiveFile.exists()) {
                        corruptiveFile.delete()
                    }
                }
            }

            // Provision WASM Cache directory safely
            val wasmDir = File(baseCacheDir, "wasm")
            if (!wasmDir.exists()) {
                val success = wasmDir.mkdirs()
                Log.d("WebViewApp", "Pre-created wasmDir at $relPath: $success")
            } else {
                // Delete any corruptive files inside WASM directory
                listOf(".keep", ".placeholder").forEach { name ->
                    val corruptiveFile = File(wasmDir, name)
                    if (corruptiveFile.exists()) {
                        corruptiveFile.delete()
                    }
                }
            }
        }
        Log.d("WebViewApp", "Pre-provisioned all possible WebView Code Cache paths successfully without placeholders.")
    } catch (e: Exception) {
         Log.e("WebViewApp", "Failed to pre-provision WebView cache directories: ${e.message}")
    }
}

fun injectAntiPwaScript(view: android.webkit.WebView) {
    val js = """
        (function() {
            // 1. Prevent native and customized beforeinstallprompt prompts
            window.addEventListener('beforeinstallprompt', function(e) {
                e.preventDefault();
                return false;
            });
            // 2. Hide common PWA installation banners or prompts dynamically via standard CSS rules
            var style = document.getElementById('anti-pwa-style');
            if (!style) {
                style = document.createElement('style');
                style.id = 'anti-pwa-style';
                style.innerHTML = `
                    .pwa-install-banner, .pwa-install-prompt, [class*="pwa-install"], [id*="pwa-install"], 
                    .install-prompt, .app-install-banner, .pwa-banner, 
                    iframe[src*="pwa"], iframe[id*="pwa"], iframe[class*="pwa"],
                    #pwa-install-container, .pwa-install-container,
                    [id*="install-prompt"], [class*="install-prompt"],
                    [id*="install-banner"], [class*="install-banner"],
                    .pwa-prompt, #pwa-prompt, .pwa-btn, .install-btn,
                    .install-banner-wrapper, .install-pwa-toast {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                        height: 0 !important;
                        width: 0 !important;
                    }
                `;
                document.head.appendChild(style);
            }
            // 3. Spoof the standalone/installed status so on-site scripts think the PWA is already installed
            try {
                if (window.navigator && !('standalone' in window.navigator)) {
                    Object.defineProperty(window.navigator, 'standalone', {
                        get: function() { return true; }
                    });
                }
            } catch(e) {}
            // 4. Dispatch simulated appinstalled event to satisfy custom install button code on the page
            window.dispatchEvent(new Event('appinstalled'));
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

private val activeObservers = java.util.Collections.synchronizedList(mutableListOf<android.os.FileObserver>())

fun startWebViewCacheMonitoring(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    // 1. Pre-emptively create directories first
    try {
        preProvisionWebViewCache(context)
    } catch (e: Exception) {
        // Ignore
    }

    // 2. Register native FileObservers for each potential path to handle deletions in real-time
    try {
        val cacheDir = context.cacheDir
        val candidatePaths = listOf(
            "WebView/Default/HTTP Cache/Code Cache",
            "WebView/Default/Code Cache",
            "org.chromium.android_webview/Default/HTTP Cache/Code Cache",
            "org.chromium.android_webview/Default/Code Cache"
        )

        synchronized(activeObservers) {
            for (obs in activeObservers) {
                try {
                    obs.stopWatching()
                } catch (ex: Exception) {
                    // Ignore
                }
            }
            activeObservers.clear()
        }

        for (relPath in candidatePaths) {
            val baseCacheDir = File(cacheDir, relPath)
            if (!baseCacheDir.exists()) {
                baseCacheDir.mkdirs()
            }
            File(baseCacheDir, "js").mkdirs()
            File(baseCacheDir, "wasm").mkdirs()

            val observer = object : android.os.FileObserver(
                baseCacheDir.absolutePath,
                android.os.FileObserver.DELETE or android.os.FileObserver.DELETE_SELF or android.os.FileObserver.CREATE
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "js" || path == "wasm" || (event and android.os.FileObserver.DELETE_SELF) != 0) {
                        try {
                            if (!baseCacheDir.exists()) {
                                baseCacheDir.mkdirs()
                            }
                            File(baseCacheDir, "js").mkdirs()
                            File(baseCacheDir, "wasm").mkdirs()
                            Log.d("WebViewApp", "FileObserver instantly restored js/wasm at $relPath")
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
            observer.startWatching()
            activeObservers.add(observer)
            Log.d("WebViewApp", "Started robust FileObserver for Code Cache at $relPath")
        }
    } catch (e: Exception) {
        Log.e("WebViewApp", "Failed to register FileObservers: ${e.message}")
    }

    // 3. Keep fallback high-frequency polling loop for double redundancy
    scope.launch(Dispatchers.IO) {
        // Run ultra-high frequency checks (every 5ms) for the first 30 seconds of startup
        // This completely defeats the race condition where Chromium initializes/clears its cache at startup
        for (i in 1..6000) {
            try {
                preProvisionWebViewCache(context)
            } catch (e: Exception) {
                // Ignore
            }
            kotlinx.coroutines.delay(5L)
        }
        // Then check every 200ms continuously to handle deferred or background cleanups safely
        while (true) {
            try {
                preProvisionWebViewCache(context)
            } catch (e: Exception) {
                // Ignore
            }
            kotlinx.coroutines.delay(200L)
        }
    }
}

fun clearWebViewData(context: android.content.Context, webView: android.webkit.WebView?) {
    try {
        // Clear WebView cache
        webView?.clearCache(true)
        
        // Clear system cookies
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies { success ->
            Log.d("WebViewApp", "Cookies cleared: $success")
        }
        cookieManager.flush()
        
        // Delete WebStorage (databases, local storage, etc.)
        android.webkit.WebStorage.getInstance().deleteAllData()

        // Re-provision directories to prevent Chromium Warnings
        preProvisionWebViewCache(context)

        // Force reload active WebView
        webView?.reload()

        android.widget.Toast.makeText(
            context,
            "Cleared all cache, cookies, and web storage. Portal refreshed.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Log.e("WebViewApp", "Error clearing WebView cache & cookies: ${e.message}")
        android.widget.Toast.makeText(
            context,
            "Failed to clear cache: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun WebViewScreen(
    url: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isLargeScreen = screenWidthDp >= 600
    val defaultUA = remember(context) { 
        try { 
            android.webkit.WebSettings.getDefaultUserAgent(context) 
        } catch (e: Exception) { 
            "" 
        } 
    }

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedUrl by remember { mutableStateOf("") }
    var popupMessageState by remember { mutableStateOf<android.os.Message?>(null) }

    // Safely load the URL when the parameter changes (e.g., config sync) without infinite recomposition triggers
    LaunchedEffect(url) {
        webViewInstance?.let { webView ->
            if (lastLoadedUrl != url) {
                lastLoadedUrl = url
                webView.loadUrl(url)
            }
        }
    }

    // Dynamically adjust browser settings and viewport on orientation/resize change
    LaunchedEffect(isLargeScreen, defaultUA) {
        webViewInstance?.settings?.apply {
            if (isLargeScreen) {
                // Large screen / tablet setup -> request iPad style tablet desktop version to adapt perfectly
                userAgentString = "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                // Compact screen phone -> default vertical responsive app with WebView indicators removed to allow Google Sign-In
                userAgentString = defaultUA.replace("; wv", "").replace("Version/[0-9.]+".toRegex(), "")
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!hasError) {
            AndroidView(
                factory = { context ->
                    preProvisionWebViewCache(context)
                    val webView = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // Enable third party cookies and general cookie acceptance
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        // Explicitly enable Autofill support, allowing native Google account autofill indicators to trigger instantly
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                        }

                        // Enable web contents debugging for developers
                        WebView.setWebContentsDebuggingEnabled(true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            javaScriptCanOpenWindowsAutomatically = true
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = true
                            allowContentAccess = true
                            
                            // High performance speed and cache settings
                            loadsImagesAutomatically = true
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = false // Slashes URL security lookup round-trip overhead on start
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                offscreenPreRaster = true // Renders elements ahead of time before drawing
                            }
                            
                            // Enable support multiple windows to handle secure OAuth sign-in popups flawlessly in a popup dialog
                            setSupportMultipleWindows(true)
                            
                            // Enable pinching zoom comfortably
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false

                            // Adaptive layout adaptation - Set User Agent dynamically on screen width!
                            if (isLargeScreen) {
                                userAgentString = "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"
                            } else {
                                userAgentString = defaultUA.replace("; wv", "").replace("Version/[0-9.]+".toRegex(), "")
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                // Clean, zero disk access on UI thread
                                preProvisionWebViewCache(context)
                                
                                // Dynamic User-Agent tuning: Swap user agent specifically when visiting Google Accounts to avoid disallowed_useragent and allow autofill!
                                if (url != null && url.contains("accounts.google.com")) {
                                    val cleanGoogleUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
                                    view?.settings?.userAgentString = cleanGoogleUA
                                } else {
                                    // Reset back to adaptive user agent
                                    view?.settings?.userAgentString = if (isLargeScreen) {
                                        "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"
                                    } else {
                                        defaultUA.replace("; wv", "").replace("Version/[0-9.]+".toRegex(), "")
                                    }
                                }
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (view != null) {
                                    injectAntiPwaScript(view)
                                }
                                // Clean, zero disk access on UI thread
                                if (url != null) {
                                    ConfigManager.getInstance(context).markUrlAsStable(url)
                                }
                            }
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                handler?.proceed()
                            }
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val backupUrl = ConfigManager.getInstance(context).configState.value.backupWebsiteUrl
                                    val fallback = ConfigManager.getInstance(context).getFallbackUrl()
                                    val failingUrlStr = request.url?.toString() ?: ""
                                    if (failingUrlStr.isNotEmpty() && failingUrlStr != backupUrl && !failingUrlStr.contains("rimonsportsacademy.workers.dev")) {
                                        Log.w("WebViewApp", "Main frame failure detected on $failingUrlStr. Trying backup URL: $backupUrl")
                                        view?.loadUrl(backupUrl)
                                    } else if (view?.url != fallback && view?.url != null) {
                                        Log.w("WebViewApp", "Backup URL failure detected, loading fallback: $fallback")
                                        view.loadUrl(fallback)
                                    } else {
                                        hasError = true
                                        isLoading = false
                                    }
                                }
                            }
                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                if (failingUrl == url) {
                                    val backupUrl = ConfigManager.getInstance(context).configState.value.backupWebsiteUrl
                                    val fallback = ConfigManager.getInstance(context).getFallbackUrl()
                                    val failingUrlStr = failingUrl ?: ""
                                    if (failingUrlStr.isNotEmpty() && failingUrlStr != backupUrl && !failingUrlStr.contains("rimonsportsacademy.workers.dev")) {
                                        Log.w("WebViewApp", "Page loading failure detected on $failingUrlStr. Trying backup URL: $backupUrl")
                                        view?.loadUrl(backupUrl)
                                    } else if (view?.url != fallback && view?.url != null) {
                                        Log.w("WebViewApp", "Backup URL failure detected, loading fallback: $fallback")
                                        view.loadUrl(fallback)
                                    } else {
                                        hasError = true
                                        isLoading = false
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                val destinationUrl = request?.url?.toString() ?: return false
                                return handleCustomUrlSchemes(view, destinationUrl)
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                destinationUrl: String?
                            ): Boolean {
                                if (destinationUrl == null) return false
                                return handleCustomUrlSchemes(view, destinationUrl)
                            }
                        }

                        val mainActivity = context as? com.example.MainActivity
                        
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                popupMessageState = resultMsg
                                return true
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                if (newProgress >= 15 && view != null) {
                                    injectAntiPwaScript(view)
                                }
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                mainActivity?.let { activity ->
                                    activity.filePathCallback?.onReceiveValue(null)
                                    activity.filePathCallback = filePathCallback
                                    
                                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                    try {
                                        activity.fileChooserLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        activity.filePathCallback = null
                                        return false
                                    }
                                    return true
                                }
                                return false
                            }
                        }

                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            try {
                                val request = android.app.DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                    setMimeType(mimetype)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading file from Portal...")
                                    setTitle(android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype))
                                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        android.os.Environment.DIRECTORY_DOWNLOADS,
                                        android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                                    )
                                }
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                dm.enqueue(request)
                                android.widget.Toast.makeText(context, "Download started...", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e("WebViewApp", "Download failed: ${e.message}")
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    Log.e("WebViewApp", "No app handled download link: ${ex.message}")
                                }
                            }
                        }

                        // Load the URL initially
                        lastLoadedUrl = url
                        loadUrl(url)
                    }

                    webViewInstance = webView
                    onWebViewCreated(webView)

                    webView
                },
                update = {
                    // Update behavior managed via LaunchedEffect(url) safely to avoid endless loading loop on recomposition
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BentoBackground)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    colors = CardDefaults.cardColors(containerColor = BentoPrimaryContainer),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, BentoBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(BentoOnPrimaryContainer, RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Offline Warning",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = "পোর্টাল কানেকশন ত্রুটি ⚠️",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = BentoOnPrimaryContainer
                        )

                        Text(
                            text = "পোর্টালটি লোড করা সম্ভব হয়নি। অনুগ্রহ করে আপনার মোবাইল ইন্টারনেট অথবা ওয়াইফাই কানেকশন চেক করুন এবং নিচের বাটনে ট্যাপ করে আবার চেষ্টা করুন।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BentoMutedText,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                hasError = false
                                isLoading = true
                                webViewInstance?.let { webView ->
                                    webView.loadUrl(url)
                                } ?: onWebViewCreated(WebView(context).apply {
                                    loadUrl(url)
                                })
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple40,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "পুনরায় চেষ্টা করুন",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("পুনরায় চেষ্টা করুন", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (popupMessageState != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                popupMessageState = null
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure Connection",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Secure Sign-In",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            IconButton(
                                onClick = { popupMessageState = null }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close window"
                                )
                            }
                        }
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
                        
                        AndroidView(
                            factory = { ctx ->
                                preProvisionWebViewCache(ctx)
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)
                                    
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        javaScriptCanOpenWindowsAutomatically = true
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
                                    }
                                    
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            preProvisionWebViewCache(ctx)
                                            if (url != null && url.contains("accounts.google.com")) {
                                                view?.settings?.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
                                            }
                                        }
                                        
                                        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                                            handler?.proceed()
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                            val u = request?.url?.toString() ?: return false
                                            return handleCustomUrlSchemes(view, u)
                                        }

                                        @Suppress("DEPRECATION")
                                        override fun shouldOverrideUrlLoading(view: WebView?, u: String?): Boolean {
                                            if (u == null) return false
                                            return handleCustomUrlSchemes(view, u)
                                        }
                                    }
                                    
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onCloseWindow(window: android.webkit.WebView?) {
                                            popupMessageState = null
                                        }
                                    }
                                    
                                    val transport = popupMessageState?.obj as? WebView.WebViewTransport
                                    if (transport != null) {
                                        transport.webView = this
                                        popupMessageState?.sendToTarget()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private fun handleCustomUrlSchemes(view: android.webkit.WebView?, url: String): Boolean {
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return false // let WebView load standard web protocols
    }
    try {
        val context = view?.context ?: return false
        if (url.startsWith("intent://")) {
            val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
            if (intent != null) {
                // Try to resolve in-app or system app
                val packageManager = context.packageManager
                val info = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                if (info != null) {
                    context.startActivity(intent)
                    return true
                } else {
                    // Fallback to browser link if provided
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (fallbackUrl != null) {
                        view?.loadUrl(fallbackUrl)
                        return true
                    }
                }
            }
        } else {
            // General custom schemes like mailto:, tel:, whatsapp:, fbconnect:, etc.
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            context.startActivity(intent)
            return true
        }
    } catch (e: Exception) {
        android.util.Log.e("WebViewApp", "Error handling custom url scheme: ${e.message}")
    }
    return true
}

private fun checkForUpdates(context: Context, onNewVersionAvailable: (UpdateInfo) -> Unit) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val url = remoteConfig.getString("version_check_url").ifEmpty {
                "https://raw.githubusercontent.com/tamimaakter74666/RSA-APPS/main/version.json"
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString != null) {
                        val json = JSONObject(bodyString)
                        val serverVersionCode = json.getInt("versionCode")
                        val serverVersionName = json.getString("versionName")
                        val downloadUrl = json.getString("downloadUrl")
                        val releaseNotes = json.optString("releaseNotes", "New stability and performance improvements are available.")

                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            packageInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            packageInfo.versionCode
                        }

                        if (serverVersionCode > currentVersionCode) {
                            withContext(Dispatchers.Main) {
                                val prefs = context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
                                val lastNotifiedVersion = prefs.getInt("last_notified_version", 0)
                                if (lastNotifiedVersion < serverVersionCode) {
                                    triggerNativePushNotification(
                                        context,
                                        "Rimon Sports নতুন আপডেট উপলব্ধ! 🚀",
                                        "অ্যাপটির নতুন সংস্করণ ($serverVersionName) এসেছে। এখনই ডাউনলোড এবং আপডেট করতে এখানে চাপুন।"
                                    )
                                    prefs.edit().putInt("last_notified_version", serverVersionCode).apply()
                                }
                                onNewVersionAvailable(
                                    UpdateInfo(
                                        versionCode = serverVersionCode,
                                        versionName = serverVersionName,
                                        downloadUrl = downloadUrl,
                                        releaseNotes = releaseNotes
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebViewApp", "Error checking for updates: ${e.message}")
        }
    }
}

private fun downloadAndInstallApk(
    context: Context,
    downloadUrl: String,
    onProgress: (Float) -> Unit,
    onComplete: (File) -> Unit,
    onError: (String) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Unsuccessful download: $response")
                }
                val body = response.body ?: throw java.io.IOException("Zero response size")
                val totalLength = body.contentLength()

                val updateFile = File(context.cacheDir, "rimon_sports_update.apk")
                if (updateFile.exists()) {
                    updateFile.delete()
                }

                body.byteStream().use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var written = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (totalLength > 0) {
                                val currentProgress = written.toFloat() / totalLength.toFloat()
                                withContext(Dispatchers.Main) {
                                    onProgress(currentProgress)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    onComplete(updateFile)
                }
            }
        } catch (e: Exception) {
            Log.e("WebViewApp", "Download error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Unknown secure download error")
            }
        }
    }
}

private fun installApk(context: Context, apkFile: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    } catch (e: java.lang.Exception) {
        Log.e("WebViewApp", "Install Error: ${e.message}", e)
        android.widget.Toast.makeText(context, "Install failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun RimonSportsSplashScreen(
    appName: String = "RIMON SPORTS ACADEMY",
    appLogoUrl: String = "",
    onLogoLongPress: () -> Unit = {}
) {
    var startAnimation by remember { mutableStateOf(false) }
    
    // Scale animation of the logo
    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LogoScale"
    )
    
    // Alpha animation of the logo and contents
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(
            durationMillis = 1000,
            easing = FastOutSlowInEasing
        ),
        label = "LogoAlpha"
    )

    // Animated value for linear loading progress
    val progressAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(
            durationMillis = 2200,
            easing = LinearOutSlowInEasing
        ),
        label = "ProgressBarAlpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F2038), // Elegant Slate Navy
                        Color(0xFF060D1A)  // Rich Deep Blue
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val maxHeightDp = maxHeight
        val logoSize = if (maxHeightDp < 500.dp) 110.dp else 160.dp
        val spacerAfterLogo = if (maxHeightDp < 500.dp) 16.dp else 32.dp
        val spacerBeforeProgress = if (maxHeightDp < 500.dp) 24.dp else 48.dp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Logo card container with high-contrast glowing shadow border
            Card(
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = alpha
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                onLogoLongPress()
                            }
                        )
                    }
                    .border(
                        border = BorderStroke(
                            width = 2.dp,
                            brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                colors = listOf(
                                    Color(0xFFE53935), // Dark Red
                                    Color(0xFF1E3C72), // Midnight Blue
                                    Color(0xFFE53935)  // Dark Red
                                )
                            )
                        ),
                        shape = RoundedCornerShape(32.dp)
                    ),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                if (appLogoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = appLogoUrl,
                        contentDescription = "Dynamic App Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(32.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_rimon_logo),
                        contentDescription = "Rimon Sports Academy Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(32.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacerAfterLogo))

            // Academy Title
            Text(
                text = appName.ifEmpty { "RIMON SPORTS ACADEMY" }.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.graphicsLayer(alpha = alpha)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = "G  A  B  T  O  L  A",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFCDD2), // light Crimson Red
                modifier = Modifier.graphicsLayer(alpha = alpha)
            )

            Spacer(modifier = Modifier.height(spacerBeforeProgress))

            // Custom elegant smooth horizontal progress line
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(100.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressAnim)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFE53935),
                                    Color(0xFFFF5252)
                                )
                            ),
                            shape = RoundedCornerShape(100.dp)
                        )
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Loading Arena...",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.graphicsLayer(alpha = alpha)
            )
        }
    }
}

private fun downloadAndInstallApk(
    context: Context,
    downloadUrl: String,
    onProgress: (Float) -> Unit,
    onComplete: () -> Unit,
    onError: (String) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder().url(downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Unexpected response code: ${response.code}")
                }
                
                val body = response.body ?: throw java.io.IOException("Response body is empty")
                val totalBytes = body.contentLength()
                
                // Save to app's cache directory
                val apkFile = File(context.cacheDir, "rimon_sports_update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                body.byteStream().use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (totalBytes > 0) {
                                val progress = totalBytesRead.toFloat() / totalBytes
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }

                // Verify file existence and size
                if (!apkFile.exists() || apkFile.length() == 0L) {
                    throw java.io.IOException("File download verification failed")
                }

                withContext(Dispatchers.Main) {
                    onComplete()
                    // Trigger install
                    triggerInstall(context, apkFile)
                }
            }
        } catch (e: Exception) {
            Log.e("WebViewApp", "APK Download failed: ${e.message}")
            withContext(Dispatchers.Main) {
                onError(e.localizedMessage ?: "Unknown error occurred")
            }
        }
    }
}

private fun triggerInstall(context: Context, apkFile: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("WebViewApp", "Failed to start install intent: ${e.message}")
    }
}

fun isInsideMaintenanceWindow(startTimeStr: String, endTimeStr: String): Boolean {
    if (startTimeStr.isEmpty() || endTimeStr.isEmpty()) return false
    val formats = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    )
    val now = System.currentTimeMillis()
    for (format in formats) {
        try {
            format.timeZone = java.util.TimeZone.getDefault()
            val start = format.parse(startTimeStr)?.time ?: 0L
            val end = format.parse(endTimeStr)?.time ?: 0L
            if (start > 0 && end > 0 && now in start..end) {
                return true
            }
        } catch (e: Exception) {
            // Try next format
        }
    }
    // Fallback: If numeric milliseconds represent UNIX epoch
    try {
        val start = startTimeStr.toLong()
        val end = endTimeStr.toLong()
        if (start > 0 && end > 0 && now in start..end) {
            return true
        }
    } catch (e: Exception) {}
    
    return false
}

fun isNightTime(): Boolean {
    val calendar = java.util.Calendar.getInstance()
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    return hour >= 20 || hour < 6 // Night-time (8 PM to 6 AM)
}

fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        ?: return true // If system service is not available, assume online rather than blocking!

    // Modern check
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null) {
            val hasInternet = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasTransport = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            
            if (hasInternet || hasValidated || hasTransport) {
                return true
            }
        }
    }

    // Checking all networks as fallback
    val allNetworks = connectivityManager.allNetworks
    if (allNetworks.isNotEmpty()) {
        for (network in allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null) {
                val hasInternet = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasTransport = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                if (hasInternet || hasTransport) {
                    return true
                }
            }
        }
    }

    // Deprecated fallback checking (extremely reliable on actual devices)
    @Suppress("DEPRECATION")
    val activeNetworkInfo = connectivityManager.activeNetworkInfo
    if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
        return true
    }

    return false
}

@Composable
fun rememberConnectivityState(context: Context, refreshTrigger: Int): State<Boolean> {
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    }
    return produceState(initialValue = isNetworkAvailable(context), keys = arrayOf(context, refreshTrigger)) {
        // Run an active check right away
        value = isNetworkAvailable(context)
        
        if (connectivityManager == null) {
            value = true
            return@produceState
        }
        
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                value = true
            }
            override fun onLost(network: android.net.Network) {
                value = isNetworkAvailable(context)
            }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                value = isNetworkAvailable(context)
            }
        }
        
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            try {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            } catch (ex: Exception) {
                value = true
            }
        }
        
        awaitDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

@Composable
fun LottieAnimationContainer(
    url: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit
) {
    val compositionResult = rememberLottieComposition(
        LottieCompositionSpec.Url(url)
    )
    val composition = compositionResult.value
    if (composition != null) {
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = LottieConstants.IterateForever
        )
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = modifier
        )
    } else {
        fallback()
    }
}

@Composable
fun SleepyMoonFallback(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sleepy_moon")
    
    val moonRotation by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "moon_rotation"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 3.0f

            drawCircle(
                color = Color.White.copy(alpha = glowAlpha * 0.4f),
                radius = 4f,
                center = androidx.compose.ui.geometry.Offset(centerOffset.x - radius * 1.2f, centerOffset.y - radius * 0.7f)
            )
            drawCircle(
                color = Color.White.copy(alpha = (1f - glowAlpha) * 0.4f),
                radius = 3f,
                center = androidx.compose.ui.geometry.Offset(centerOffset.x + radius * 0.9f, centerOffset.y - radius * 1.1f)
            )
            drawCircle(
                color = Color.White.copy(alpha = glowAlpha * 0.6f),
                radius = 5f,
                center = androidx.compose.ui.geometry.Offset(centerOffset.x + radius * 1.1f, centerOffset.y + radius * 0.6f)
            )

            rotate(degrees = moonRotation, pivot = centerOffset) {
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFEB3B).copy(alpha = glowAlpha * 0.3f),
                            Color.Transparent
                        ),
                        center = centerOffset,
                        radius = radius * 1.4f
                    ),
                    radius = radius * 1.4f,
                    center = centerOffset
                )

                val path = androidx.compose.ui.graphics.Path().apply {
                    addArc(
                        oval = androidx.compose.ui.geometry.Rect(
                            centerOffset.x - radius,
                            centerOffset.y - radius,
                            centerOffset.x + radius,
                            centerOffset.y + radius
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 200f
                    )
                    addArc(
                        oval = androidx.compose.ui.geometry.Rect(
                            centerOffset.x - radius * 0.6f,
                            centerOffset.y - radius,
                            centerOffset.x + radius * 1.2f,
                            centerOffset.y + radius
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 200f
                    )
                }
                drawPath(
                    path = path,
                    color = Color(0xFFFFD54F)
                )

                drawArc(
                    color = Color(0xFF5D4037),
                    startAngle = 10f,
                    sweepAngle = 160f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    ),
                    size = androidx.compose.ui.geometry.Size(32f, 32f),
                    topLeft = androidx.compose.ui.geometry.Offset(centerOffset.x - 8f, centerOffset.y - 12f)
                )
            }
        }
    }
}

@Composable
fun LoadingGearFallback(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_gear")
    
    val gearRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gear_rotation"
    )

    Box(
        modifier = modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 4.0f

            rotate(degrees = gearRotation, pivot = centerOffset) {
                drawCircle(
                    color = Color(0xFF78909C),
                    radius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 16f),
                    center = centerOffset
                )

                for (i in 0 until 8) {
                    val angle = (i * 45).toDouble()
                    val radian = Math.toRadians(angle)
                    val startX = centerOffset.x + (radius - 10f) * Math.cos(radian).toFloat()
                    val startY = centerOffset.y + (radius - 10f) * Math.sin(radian).toFloat()
                    val endX = centerOffset.x + (radius + 20f) * Math.cos(radian).toFloat()
                    val endY = centerOffset.y + (radius + 20f) * Math.sin(radian).toFloat()
                    
                    drawLine(
                        color = Color(0xFF78909C),
                        start = androidx.compose.ui.geometry.Offset(startX, startY),
                        end = androidx.compose.ui.geometry.Offset(endX, endY),
                        strokeWidth = 20f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }

                drawCircle(
                    color = Color(0xFF546E7A),
                    radius = radius * 0.4f,
                    center = centerOffset
                )
                drawCircle(
                    color = Color(0xFFECEFF1),
                    radius = radius * 0.15f,
                    center = centerOffset
                )
            }
        }
    }
}

@Composable
fun MaintenanceScreen(
    endTime: String = "",
    isNight: Boolean = false,
    isOffline: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onProceedAnyway: (() -> Unit)? = null
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val lottieSize = if (screenHeightDp < 500) 120.dp else 200.dp

    val titleText = if (isOffline) {
        "নেটওয়ার্ক সংযোগ ত্রুটি ⚠️"
    } else {
        "সার্ভার রক্ষণাবেক্ষণ চলছে 🛠️"
    }

    val messageText = if (isOffline) {
        "মনে হচ্ছে আপনার ইন্টারনেট সংযোগে কোনো সমস্যা রয়েছে। অনুগ্রহ করে আপনার মোবাইল ডাটা বা ওয়াইফাই কানেকশন চেক করুন এবং পুনরায় চেষ্টা করুন।"
    } else {
        "আমরা বর্তমানে সিস্টেম আপগ্রেডেশনের কাজ করছি। খুব শীঘ্রই আমরা ফিরবো। রক্ষণাবেক্ষণ কাজ সম্পন্ন হওয়ার সম্ভাব্য সময়: ${endTime.ifEmpty { "খুব দ্রুত" }}।"
    }

    val animationUrl = if (isNight) {
        "https://lottie.host/e474cbdb-5a1e-450f-bb7e-fca826e7ae3d/I9X6H97e3F.json"
    } else {
        "https://lottie.host/9e49ab3d-b4b0-4dbb-a5d6-d08b3ba96ac4/H40PqL0g4u.json"
    }

    val backgroundBrush = if (isNight) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color(0xFF030914),
                Color(0xFF010307)
            )
        )
    } else {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE8F0FE),
                Color(0xFFF1F3F4)
            )
        )
    }

    val contentColor = if (isNight) Color.White else Color(0xFF202124)
    val secondaryColor = if (isNight) Color(0xFF9EAECA) else Color(0xFF5F6368)
    val cardBg = if (isNight) Color(0xFF0B132B) else Color.White
    val cardBorder = if (isNight) Color(0xFF1C2541) else Color(0xFFDADCE0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(lottieSize)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                LottieAnimationContainer(
                    url = animationUrl,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isNight) {
                        SleepyMoonFallback(modifier = Modifier.fillMaxSize())
                    } else {
                        LoadingGearFallback(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                border = BorderStroke(1.dp, cardBorder),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isOffline) Icons.Default.Warning else Icons.Default.Settings,
                        contentDescription = "Status Icon",
                        tint = if (isNight) Color(0xFFF1C40F) else Color(0xFF1A73E8),
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.25f
                    )

                    if (isOffline && onRetry != null && onProceedAnyway != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = onRetry,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, cardBorder.copy(alpha = 0.8f)),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = contentColor
                                )
                            ) {
                                Text("পুনরায় চেষ্টা করুন", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                            androidx.compose.material3.Button(
                                onClick = onProceedAnyway,
                                modifier = Modifier.weight(1f),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = if (isNight) Color(0xFF1E3A8A) else Color(0xFF1A73E8),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("তাও লোড করুন", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "RIMON SPORTS ACADEMY PORTAL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = secondaryColor
            )
        }
    }
}


