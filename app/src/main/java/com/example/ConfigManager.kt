package com.example

import android.content.Context
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Representing the application remote configuration model.
 * Using a clean, decoupled data architecture with self-healing fallback states.
 */
data class AppConfig(
    val websiteUrl: String = "https://rimonsportsacademy.vercel.app/",
    val backupWebsiteUrl: String = "https://rimonsportsacademy.rimonsportsacademy.workers.dev/",
    val themeColor: String = "#040D1A",
    val maintenanceMode: Boolean = false,
    val featureChatEnabled: Boolean = true,
    val appStatus: String = "Active", // "Active" or "Maintenance"
    val latestApkVersion: String = "1.0",
    val maintenanceStartTime: String = "",
    val maintenanceEndTime: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val appName: String = "Rimon Sports",
    val appLogoUrl: String = "",
    val notificationTitle: String = "",
    val notificationBody: String = "",
    val notificationId: String = ""
)

/**
 * ConfigManager acts as the single source of truth for both remote configuration stream
 * synchronization and local persistence with failover error protection.
 */
class ConfigManager private constructor(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("rimon_config_prefs", Context.MODE_PRIVATE)
    private val firebaseConfig = FirebaseRemoteConfig.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)

    // Single reactive state stream representing active configurations
    private val _configState = MutableStateFlow(loadLocalPersistedConfig())
    val configState: StateFlow<AppConfig> = _configState.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        setupRemoteConfigDefaults()
    }

    /**
     * Initializes default values and dynamic settings for Remote Config.
     */
    private fun setupRemoteConfigDefaults() {
        try {
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = 0 // Real-time immediate update syncing
            }
            firebaseConfig.setConfigSettingsAsync(configSettings)

            val defaults = mapOf(
                "website_url" to "https://rimonsportsacademy.vercel.app/",
                "backup_website_url" to "https://rimonsportsacademy.rimonsportsacademy.workers.dev/",
                "app_config_json" to """{
                    "themeColor": "#040D1A",
                    "maintenanceMode": false,
                    "appStatus": "Active",
                    "latestApkVersion": "1.0",
                    "featureChatEnabled": true,
                    "maintenanceStartTime": "",
                    "maintenanceEndTime": "",
                    "lastUpdated": ${System.currentTimeMillis()},
                    "appName": "Rimon Sports",
                    "appLogoUrl": "",
                    "notificationTitle": "",
                    "notificationBody": "",
                    "notificationId": ""
                }""".trimIndent()
            )
            firebaseConfig.setDefaultsAsync(defaults)
        } catch (e: Exception) {
            Log.e("ConfigManager", "Error setting up Remote Config defaults: ${e.message}")
        }
    }

    /**
     * Starts listening for live Remote Config and Cloud Firestore changes in real-time.
     * When remote change is broadcasted, it seamlessly fetches, activates,
     * and triggers merge logic in the background without UI disruption.
     */
    fun startRealTimeSync(onUrlChanged: (String) -> Unit) {
        // 1. Firebase Remote Config Real-Time Setup
        try {
            firebaseConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    Log.d("ConfigManager", "Real-time key changes check: ${configUpdate.updatedKeys}")
                    
                    firebaseConfig.activate().addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("ConfigManager", "Remote Config hot-loaded.")
                            val previousUrl = _configState.value.websiteUrl
                            syncAndMergeRemoteConfig()
                            
                            val currentUrl = _configState.value.websiteUrl
                            if (currentUrl != previousUrl) {
                                onUrlChanged(currentUrl)
                            }
                        }
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    Log.e("ConfigManager", "Remote config live update listener failed: ", error)
                }
            })
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to register real-time update listener: ${e.message}")
        }

        // 2. Cloud Firestore Real-time Snapshot Listener Setup
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("configs").document("app_config")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.w("ConfigManager", "Firestore real-time listener unaccessible. Falling back to cached flow.", e)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        Log.d("ConfigManager", "Firestore config updated: ${snapshot.data}")
                        mergeFirestoreConfig(snapshot, onUrlChanged)
                    }
                }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Firestore setup error, using SharedPreferences fallback: ${e.message}")
        }

        // Run an initial configurations sync
        fetchAndTriggerUpdates(onUrlChanged)
    }

    /**
     * Helper to merge Firestore DocumentSnapshot parameters into current AppConfig state
     */
    private fun mergeFirestoreConfig(snapshot: DocumentSnapshot, onUrlChanged: (String) -> Unit) {
        try {
            val dbUrl = snapshot.getString("website_url")
            val dbBackupUrl = snapshot.getString("backup_website_url")
            val dbStatus = snapshot.getString("app_status") ?: "Active"
            val dbApkVersion = snapshot.getString("latest_apk_version") ?: "1.0"
            val dbTheme = snapshot.getString("theme_color") ?: "#040D1A"
            val dbChat = snapshot.getBoolean("feature_chat_enabled") ?: true
            val dbStartTime = snapshot.getString("maintenance_start_time") ?: ""
            val dbEndTime = snapshot.getString("maintenance_end_time") ?: ""
            val dbUpdated = snapshot.getLong("last_updated") ?: System.currentTimeMillis()

            val dbAppName = snapshot.getString("app_name") ?: "Rimon Sports"
            val dbAppLogoUrl = snapshot.getString("app_logo_url") ?: ""
            val dbNotificationTitle = snapshot.getString("notification_title") ?: ""
            val dbNotificationBody = snapshot.getString("notification_body") ?: ""
            val dbNotificationId = snapshot.getString("notification_id") ?: ""

            val previousUrl = _configState.value.websiteUrl
            val previousBackupUrl = _configState.value.backupWebsiteUrl
            val previousNotificationId = _configState.value.notificationId
            val validatedUrl = if (dbUrl != null && isValidHttpsUrl(dbUrl)) dbUrl else previousUrl
            val validatedBackupUrl = if (dbBackupUrl != null && isValidHttpsUrl(dbBackupUrl)) dbBackupUrl else previousBackupUrl

            val merged = AppConfig(
                websiteUrl = validatedUrl,
                backupWebsiteUrl = validatedBackupUrl,
                themeColor = dbTheme,
                maintenanceMode = dbStatus == "Maintenance",
                appStatus = dbStatus,
                latestApkVersion = dbApkVersion,
                featureChatEnabled = dbChat,
                maintenanceStartTime = dbStartTime,
                maintenanceEndTime = dbEndTime,
                lastUpdated = dbUpdated,
                appName = dbAppName,
                appLogoUrl = dbAppLogoUrl,
                notificationTitle = dbNotificationTitle,
                notificationBody = dbNotificationBody,
                notificationId = dbNotificationId
            )

            persistConfigLocally(merged)
            _configState.value = merged

            // Push Notification Interceptor: trigger a push notification if the event notification ID has updated!
            if (dbNotificationId.isNotEmpty() && dbNotificationId != previousNotificationId) {
                triggerNativePushNotification(context, dbNotificationTitle, dbNotificationBody)
            }

            if (validatedUrl != previousUrl) {
                onUrlChanged(validatedUrl)
            }
            Log.d("ConfigManager", "Firestore live Config merged successfully: $merged")
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to merge Firestore config: ${e.message}")
        }
    }

    /**
     * Pulls the absolute latest version from Remote Config servers.
     */
    fun fetchAndTriggerUpdates(onUrlChanged: (String) -> Unit = {}) {
        val previousUrl = _configState.value.websiteUrl
        firebaseConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("ConfigManager", "Remote Config fetch completed.")
                    syncAndMergeRemoteConfig()
                    
                    val currentUrl = _configState.value.websiteUrl
                    if (currentUrl != previousUrl) {
                        onUrlChanged(currentUrl)
                    }
                } else {
                    Log.w("ConfigManager", "Remote Config fallback triggered.")
                }
            }
    }

    /**
     * Core merge logic combining incoming server config objects with local app states
     * securely to preserve integrity and avoid state loss.
     */
    @Synchronized
    private fun syncAndMergeRemoteConfig() {
        try {
            val remoteUrl = firebaseConfig.getString("website_url")
            val remoteBackupUrl = firebaseConfig.getString("backup_website_url")
            val remoteJsonString = firebaseConfig.getString("app_config_json")

            // Validate remote URL schema before accepting
            val finalUrl = if (isValidHttpsUrl(remoteUrl)) {
                remoteUrl
            } else {
                _configState.value.websiteUrl
            }

            val finalBackupUrl = if (isValidHttpsUrl(remoteBackupUrl)) {
                remoteBackupUrl
            } else {
                _configState.value.backupWebsiteUrl
            }

            val remoteJson = JSONObject(remoteJsonString)

            val mergedConfig = AppConfig(
                websiteUrl = finalUrl,
                backupWebsiteUrl = finalBackupUrl,
                themeColor = remoteJson.optString("themeColor", _configState.value.themeColor),
                maintenanceMode = remoteJson.optBoolean("maintenanceMode", _configState.value.maintenanceMode) || (remoteJson.optString("appStatus", "Active") == "Maintenance"),
                appStatus = remoteJson.optString("appStatus", _configState.value.appStatus),
                latestApkVersion = remoteJson.optString("latestApkVersion", _configState.value.latestApkVersion),
                featureChatEnabled = remoteJson.optBoolean("featureChatEnabled", _configState.value.featureChatEnabled),
                maintenanceStartTime = remoteJson.optString("maintenanceStartTime", _configState.value.maintenanceStartTime),
                maintenanceEndTime = remoteJson.optString("maintenanceEndTime", _configState.value.maintenanceEndTime),
                lastUpdated = remoteJson.optLong("lastUpdated", System.currentTimeMillis()),
                appName = remoteJson.optString("appName", _configState.value.appName),
                appLogoUrl = remoteJson.optString("appLogoUrl", _configState.value.appLogoUrl),
                notificationTitle = remoteJson.optString("notificationTitle", _configState.value.notificationTitle),
                notificationBody = remoteJson.optString("notificationBody", _configState.value.notificationBody),
                notificationId = remoteJson.optString("notificationId", _configState.value.notificationId)
            )

            persistConfigLocally(mergedConfig)
            _configState.value = mergedConfig

            Log.d("ConfigManager", "Remote Config merged seamlessly: $mergedConfig")
        } catch (e: Exception) {
            Log.e("ConfigManager", "Remote Config merge failed: ${e.message}")
        }
    }

    /**
     * Updates and writes new Configurations to Cloud Firestore.
     * Replicates the changes locally in real-time if offline or unauthenticated to ensure zero lag.
     */
    fun saveConfigToFirestore(
        websiteUrl: String,
        backupWebsiteUrl: String = _configState.value.backupWebsiteUrl,
        appStatus: String,
        latestApkVersion: String,
        maintenanceStartTime: String,
        maintenanceEndTime: String,
        appName: String = _configState.value.appName,
        appLogoUrl: String = _configState.value.appLogoUrl,
        notificationTitle: String = _configState.value.notificationTitle,
        notificationBody: String = _configState.value.notificationBody,
        notificationId: String = _configState.value.notificationId,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val data = hashMapOf(
            "website_url" to websiteUrl,
            "backup_website_url" to backupWebsiteUrl,
            "app_status" to appStatus,
            "latest_apk_version" to latestApkVersion,
            "theme_color" to _configState.value.themeColor,
            "feature_chat_enabled" to _configState.value.featureChatEnabled,
            "maintenance_start_time" to maintenanceStartTime,
            "maintenance_end_time" to maintenanceEndTime,
            "last_updated" to System.currentTimeMillis(),
            "app_name" to appName,
            "app_logo_url" to appLogoUrl,
            "notification_title" to notificationTitle,
            "notification_body" to notificationBody,
            "notification_id" to notificationId
        )

        // Always apply change locally first (zero UI latency)
        val localMerged = AppConfig(
            websiteUrl = websiteUrl,
            backupWebsiteUrl = backupWebsiteUrl,
            themeColor = _configState.value.themeColor,
            maintenanceMode = appStatus == "Maintenance",
            appStatus = appStatus,
            latestApkVersion = latestApkVersion,
            featureChatEnabled = _configState.value.featureChatEnabled,
            maintenanceStartTime = maintenanceStartTime,
            maintenanceEndTime = maintenanceEndTime,
            lastUpdated = System.currentTimeMillis(),
            appName = appName,
            appLogoUrl = appLogoUrl,
            notificationTitle = notificationTitle,
            notificationBody = notificationBody,
            notificationId = notificationId
        )
        persistConfigLocally(localMerged)
        _configState.value = localMerged

        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("configs").document("app_config")
                .set(data)
                .addOnSuccessListener {
                    Log.d("ConfigManager", "Firestore update deployed successfully!")
                    onComplete(true, null)
                }
                .addOnFailureListener { e ->
                    Log.w("ConfigManager", "Firestore upload unsuccessful, saved locally: ${e.message}")
                    onComplete(true, "Saved locally (Offline/Config issues: ${e.localizedMessage})")
                }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Firestore not initialized properly: ${e.message}")
            onComplete(true, "Saved locally (Firebase Auth/Service not configured: ${e.localizedMessage})")
        }
    }

    /**
     * Persists configurations directly to SharedPreferences.
     */
    private fun persistConfigLocally(config: AppConfig) {
        sharedPrefs.edit().apply {
            putString("website_url", config.websiteUrl)
            putString("backup_website_url", config.backupWebsiteUrl)
            putString("theme_color", config.themeColor)
            putBoolean("maintenance_mode", config.maintenanceMode)
            putString("app_status", config.appStatus)
            putString("latest_apk_version", config.latestApkVersion)
            putBoolean("feature_chat_enabled", config.featureChatEnabled)
            putString("maintenance_start_time", config.maintenanceStartTime)
            putString("maintenance_end_time", config.maintenanceEndTime)
            putLong("last_updated_timestamp", config.lastUpdated)
            putString("app_name", config.appName)
            putString("app_logo_url", config.appLogoUrl)
            putString("notification_title", config.notificationTitle)
            putString("notification_body", config.notificationBody)
            putString("notification_id", config.notificationId)
            apply()
        }
    }

    /**
     * Loads locally persisted config fast during app startups.
     */
    private fun loadLocalPersistedConfig(): AppConfig {
        val websiteUrl = sharedPrefs.getString("website_url", "https://rimonsportsacademy.vercel.app/")
            ?: "https://rimonsportsacademy.vercel.app/"
        val backupWebsiteUrl = sharedPrefs.getString("backup_website_url", "https://rimonsportsacademy.rimonsportsacademy.workers.dev/")
            ?: "https://rimonsportsacademy.rimonsportsacademy.workers.dev/"
        val themeColor = sharedPrefs.getString("theme_color", "#040D1A") ?: "#040D1A"
        val maintenanceMode = sharedPrefs.getBoolean("maintenance_mode", false)
        val appStatus = sharedPrefs.getString("app_status", "Active") ?: "Active"
        val latestApkVersion = sharedPrefs.getString("latest_apk_version", "1.0") ?: "1.0"
        val featureChatEnabled = sharedPrefs.getBoolean("feature_chat_enabled", true)
        val maintenanceStartTime = sharedPrefs.getString("maintenance_start_time", "") ?: ""
        val maintenanceEndTime = sharedPrefs.getString("maintenance_end_time", "") ?: ""
        val lastUpdated = sharedPrefs.getLong("last_updated_timestamp", System.currentTimeMillis())
        val appName = sharedPrefs.getString("app_name", "Rimon Sports") ?: "Rimon Sports"
        val appLogoUrl = sharedPrefs.getString("app_logo_url", "") ?: ""
        val notificationTitle = sharedPrefs.getString("notification_title", "") ?: ""
        val notificationBody = sharedPrefs.getString("notification_body", "") ?: ""
        val notificationId = sharedPrefs.getString("notification_id", "") ?: ""

        return AppConfig(
            websiteUrl = websiteUrl,
            backupWebsiteUrl = backupWebsiteUrl,
            themeColor = themeColor,
            maintenanceMode = maintenanceMode || (appStatus == "Maintenance"),
            appStatus = appStatus,
            latestApkVersion = latestApkVersion,
            featureChatEnabled = featureChatEnabled,
            maintenanceStartTime = maintenanceStartTime,
            maintenanceEndTime = maintenanceEndTime,
            lastUpdated = lastUpdated,
            appName = appName,
            appLogoUrl = appLogoUrl,
            notificationTitle = notificationTitle,
            notificationBody = notificationBody,
            notificationId = notificationId
        )
    }

    /**
     * Self-healing hook: mark loaded URLs as stable.
     */
    fun markUrlAsStable(url: String) {
        if (isValidHttpsUrl(url)) {
            sharedPrefs.edit().putString("last_stable_url", url).apply()
            Log.i("ConfigManager", "URL bookmarked as stable: $url")
        }
    }

    /**
     * Recover from failures instantly with stable checkpoints.
     */
    fun getFallbackUrl(): String {
        val fallback = sharedPrefs.getString("last_stable_url", "https://rimonsportsacademy.vercel.app/")
            ?: "https://rimonsportsacademy.vercel.app/"
        Log.w("ConfigManager", "Falling back to stable checkpoint: $fallback")
        return fallback
    }

    private fun isValidHttpsUrl(url: String): Boolean {
        return url.isNotEmpty() && (url.startsWith("https://") || url.startsWith("http://"))
    }
}

fun triggerNativePushNotification(context: Context, title: String, body: String) {
    if (title.isEmpty() && body.isEmpty()) return
    try {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "app_updates_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "App Updates and Notifications"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Get notified about latest updates or messages in real time"
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        
        val notificationIdNum = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationIdNum, builder.build())
        Log.d("ConfigManager", "Native push notification triggered successfully: $title - $body")
    } catch (e: Exception) {
        Log.e("ConfigManager", "Failed to show push notification: ${e.message}")
    }
}
