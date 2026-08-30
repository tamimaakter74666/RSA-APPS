import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.Base64

// Automatically decode debug.keystore.base64 to preserve original June 14 key
val hardcodedKeystoreBase64 = "MIIKZgIBAzCCChAGCSqGSIb3DQEHAaCCCgEEggn9MIIJ+TCCBcAGCSqGSIb3DQEHAaCCBbEEggWtMIIFqTCCBaUGCyqGSIb3DQEMCgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFPt9HgBCU7x1SRiVS7EBRyGAp/+UAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQ4gloZict1VCXD9HZk0yM7gSCBNCQodO3FaZKTuVEOpIlAP5/fiIY//RpUqGpdvjq2Ot93oXYB13mBXTFGsfdmj8nqDj/2CCwpVrr+qfoUz6OiuSnXVgdwiWK045j0qYf0zior4DSeyz4dWEuO2BZucbwW2UzKWbkGW9AlFby1d7qxpnTq9zOZJQ4Zs8Cx1cwrtygkQeAw2OtrMP+Ed1YtOucRRc//dyM5oWE+X1V0W4x/vmZZ6HrF1+i0wkvsNJwejYPCgBwuqARNccwR6FYgCpv3SzzsoZbBJDnI380XVFKKBI/mLodMkRi4Xoy72PxwEQf3809LCJ6SB5YWuBInCuZmzs+586i4kmTOZGYiYNCRXVLveEtO9zxCfa0TydaZmhxR0xFWAk7LSqYJhlvpZMqduf49vEAWlNs+oxjTaEy/PftNSlbp+k4e5f33dq01/bsu7eOB5O8JpvXHX7H41qQg/qhrlcpj7/YMsvCLM+lTdtpDXEMHdp5Az0M5/zrX7dbX6D54rsBZFD+AewZxroLfICR5GCvfl3jCKd9aqof0GFay3ydespD0V8K/i81fVjQar8nUbujJanNRwotyaAgWYqqE4h3yY4AU6adOR5q2ZH9NoVXEtw3hiBDxu23zLkY/i/rjs+161VAnNX2K79V9LlZPh0+tGaaGgBHLYr5eIdFd254hVvkqzgXq47KSmzwiM3Z+uWgcHqtXlsVjsDaleXonpXg3lwWeKjdsNtMjRrkWJYxtnDB6mg3AeKpZprodgvZjtJKy3OoJt+drUeRwKfRxm6nMPX2cdpmNiKViZ6e9ggI0JMhH/cg1R+jB9OHpepAYREqELXhmK2oJyb9/Svjbse3pPhut7LHW4HoeK4xSwdSvE3+1Z4W6i/8y3WAaY1/TINIGeQQUV+EdXlnCdE6eHW06vIUlOjwR1C2fD6Fdn9qZPEYZ0LOHHY/0Kt8gsJiDEZSobOJeROUYUvRfxaqOOVwCvz40r5v7INDYX8F73m52AOcsGL/9oM35mQbs5GxJvZjQOycT+anYclIf/+PqX+DIRnZQzp/ld1cAxIQ2xCTsX8IAAGFJfeHFdCAOfu7fjE/bjwFuaxDt6wNZ2jETWI/mq3mGyy7FC2mBSyCeXgNZqr9G6xYI5+vDDAfNqyzZraJU3gBZ+gt8rNPBOxPs9EnJKkyQx6ISfYesR2XJdVETDiza9CYljlI4NyYEvItaDMseuFBxHEIjLNqAHrj5sbzoOKjFCJCPlYiS1/LR57PExQqbtd69hqjDwuayvt07OQ3DDrShUhBLyq1qbbgmFyQsNd+hv3XUXGkmnrkD1jaHcFNFizw25xXGMHNEjSDKxFNUWgpvwncyns9mGA6vDXiZGeYyZvzAFTk9UuZDX1QEDYF78ZVwfKNOsf5ss0T74m+RikDVnw0oo9TkO7CZaPemub1LSgyTF4sPMuTgKoq9VFJgUU1b078DQlbjMSqOcnmRH5MdDL9K03E8m033WWLs+rKnuRJidSkCTloraAWpSzgi19vj/7AG/3IhpE4d8Pssf3HM0RvrcVPCzrupFuxhZSVg4Tj9Us2PFtMsKP6B64iPYZQRLYhUoC4IQpbUC9fKiHgjyOT0ykdCz8/Pv4wJXuNYo8ZmvoqIh8rSfeP9y5hAwKQSYx+wEcwIjFSMC0GCSqGSIb3DQEJFDEgHh4AYQBuAGQAcgBvAGkAZABkAGUAYgB1AGcAawBlAHkwIQYJKoZIhvcNAQkVMRQEElRpbWUgMTc4MTQ0MjMzMjY1NDCCBDEGCSqGSIb3DQEHBqCCBCIwggQeAgEAMIIEFwYJKoZIhvcNAQcBMGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBSKsSt/1xbq7w5Wo+lyCGgRFaT2CgICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEELjQW/ql5sXkVzy4nT6/I4qAggOgEtcdI4AgmSGCJynoqwNuxbdqmpWnG1KmiHaQoO1ySsQiesd3HhE/70AFMCTeSokdD66T5UdxDwZoCSAO4wH8iMPoZuokHkisoBMELIicj+Mmp0Zb9wnsUKKckUvogrSVIOaLLyIiAxk+eBFb0S6k1z40hRfvrX6FIN2oQmWP/bh1i8SyzJf/IXoeswuYi5yfPavMYjiuf/g1UpkFlgwF880SGNfM3WI8MtdTgzLuCxqydAHzABqiv58/waimhyC2HzyJhd49IP3yR81LZnc1DTr5WvhpYCXQIpp2cS0IOpVB04jeVbvDVOZFNqRx1JHCJXnWVXxfW4iuF5sP0UZ2vprCWAZSBLVRddPPi8QscnLPPz38f1ZTRl0EayLA24nrmQovxAI3FFYQJR9BUplyQ6TkRk+c9vLhOZKWdNfC2yWrB+0wt4O1dFD8QE4X/F8S5yC9mP6RpIykd4g1Mn2RqCHQCY6qDuHNjf5rGjCi75WZ86bFux9PLEkepHQCfAXaJi2xtYUxDMA1Q2jw5JJFYujSY1H/cjFHe5KfP/5e31TQFvSLYtw+ibSTy50+CF8QLB/k3ym+Ar81N3OTAe1o9S/vq6rU6HYsb8C3QIhA9MBmEr0ky7bY8Mi3gLB/rJwg9yMqu3zrdoR8vaBagOR8Qe3uqhzrnzWW6hgHoQFj+8hiVlRJjhWhZeAqeNWJehdpPjOvYyVypOhGEpK+Xi9IuZEyE3osCobWiMAZzI6P7b/ZHHS0o42X0gxVmd2SyBetSjRzW74vwmHR8YaMQN6YXg/PglI3YvyIKOlp3sHpgSgJYqDOOKdepDSU2cezRx9+kZxJFlcYak6mnVgDJdwUSMJjFLi8J9zTb3S974HTg65jQQpZIsqE2Ygmojneq3ViZa1AWFvxqFqp03CmPkrytLoKFKeUIMixYzSRN7ESoAFegxzjqjur8Zg9s57EQElUr3wzCnLkBF4WEHsSYln7hITqpshpXvBxIzzgo2hA57HMtRLDaWiAdaGvGbIMEK2B74PECgWJpOsoRTWWze8dnho4BJw9JboDbrmigXewuXQHSMty7mDJfvDRjmi7vYUBx8vMKZn+SpCoUcwbKYS9tmEsj/eK7ybYImNv95ZZe1dZcAvcJzSOaK1czylVXQChueTv+jth9az3DPIMkds/xvDAfeM4ymg+sJDGULqzL3kdlYs0IW8dNG65xYDrt6hds6xO6gx9cLPzZf0Mx+MoTBNMDEwDQYJYIZIAWUDBAIBBQAEIDgSld7CeMqEJ7I42Ljxkg4Upvt+ut411C/ARjJP2ysGBBT8QI5SVIFguOj+hYk8dU7dDvj7lgICJxA="
val base64File = file("${rootDir}/debug.keystore.base64")
val targetKeystore = file("${rootDir}/debug.keystore")
try {
  val rawBase64 = if (base64File.exists()) base64File.readText() else hardcodedKeystoreBase64
  val base64Content = rawBase64.replace("\\s+".toRegex(), "")
  val decodedBytes = Base64.getDecoder().decode(base64Content)
  targetKeystore.writeBytes(decodedBytes)
  val appKeystore = file("${rootDir}/app/debug.keystore")
  appKeystore.writeBytes(decodedBytes)
  println("Successfully decoded original debug.keystore from persistent key!")
} catch (e: Exception) {
  println("Error decoding debug.keystore: ${e.message}")
}

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
    versionCode = 10
    versionName = "1.2.7"

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
  implementation("androidx.fragment:fragment-ktx:1.6.2")
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

