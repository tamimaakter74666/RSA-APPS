import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.rimonsports.portal"
    minSdk = 24
    targetSdk = 34
    versionCode = 3
    versionName = "1.2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val path = file(keystorePath)
      if (path.exists()) {
        storeFile = path
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        // Safe fallback to debug keystore for development / AI Studio builds
        val debugKeystore = file("${rootDir}/debug.keystore")
        storeFile = if (debugKeystore.exists()) debugKeystore else file("debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
      enableV4Signing = true
    }
    create("debugConfig") {
      val debugKeystore = file("${rootDir}/debug.keystore")
      storeFile = if (debugKeystore.exists()) debugKeystore else file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
      enableV4Signing = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.config)
  implementation(libs.lottie.compose)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
  implementation("androidx.compose.foundation:foundation")
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

val apkProvider = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
val rootDirFile = rootDir
val landingPageDirFile = file("${rootDir}/landing_page")

tasks.register("copyApkToLandingPages") {
  dependsOn("assembleDebug")
  val inputApk = apkProvider
  val dest1 = rootDirFile.resolve("app-debug.apk")
  val dest2 = landingPageDirFile.resolve("app-debug.apk")
  val dest1Zip = rootDirFile.resolve("app-debug.zip")
  val dest2Zip = landingPageDirFile.resolve("app-debug.zip")
  val dest1Pdf = rootDirFile.resolve("app-debug.pdf")
  val dest2Pdf = landingPageDirFile.resolve("app-debug.pdf")
  
  doLast {
    val apkFile = inputApk.get().asFile
    if (apkFile.exists()) {
      println("Source APK file size: ${apkFile.length()} bytes")
      // Copy files as binary
      apkFile.copyTo(dest1, overwrite = true)
      apkFile.copyTo(dest2, overwrite = true)
      apkFile.copyTo(dest1Pdf, overwrite = true)
      apkFile.copyTo(dest2Pdf, overwrite = true)
      
      // Create structurally valid ZIP archive containing the APK file
      fun createRealZip(sourceFile: File, zipFile: File) {
        FileOutputStream(zipFile).use { fos ->
          ZipOutputStream(fos).use { zos ->
            val entry = ZipEntry("app-debug.apk")
            zos.putNextEntry(entry)
            sourceFile.inputStream().use { fis ->
              fis.copyTo(zos)
            }
            zos.closeEntry()
          }
        }
      }
      
      createRealZip(apkFile, dest1Zip)
      createRealZip(apkFile, dest2Zip)
      
      println("Successfully copied APK, PDF, and created a real ZIP archive at: $dest1 (size: ${dest1.length()}) and $dest1Zip (size: ${dest1Zip.length()})")
    } else {
      println("Error: APK file not found at ${apkFile.absolutePath}")
    }
  }
}

tasks.whenTaskAdded {
  if (name == "assembleDebug") {
    finalizedBy("copyApkToLandingPages")
  }
}

