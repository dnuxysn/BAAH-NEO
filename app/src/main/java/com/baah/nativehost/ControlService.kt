package com.baah.nativehost

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import android.view.InputEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Runs as shell via Shizuku; owns only NEO's display and dies with its client. */
class ControlService : IControlService.Stub() {
    private val worker = HandlerThread("neo-capture", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(worker.looper)
    private val frameLock = Any()
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bitmap: Bitmap? = null
    private var latestImage: android.media.Image? = null
    private var convertedFrame = -1L
    private var conversions = 0L
    private var frames = 0L
    private var lastFrame = 0L
    private var downTime = 0L
    private var owner: IBinder? = null
    private val death = IBinder.DeathRecipient { destroy() }
    private val audioModes = mutableMapOf<String, String>()
    @Volatile private var screenOff = false
    @Volatile private var powerCalls = 0L
    @Volatile private var powerError = ""
    private val keepScreenOff = object : Runnable {
        override fun run() {
            if (!screenOff) return
            // A physical power-key sleep relinquishes NEO's hardware blanking.
            if (!(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { screenOff = false; return }
            runCatching { applyScreenPower(false) }.onFailure { powerError = it.toString() }
            handler.postDelayed(this, 1000)
        }
    }
    private val context: Context by lazy {
        val klass = Class.forName("android.app.ActivityThread")
        val activityThread = klass.getMethod("systemMain").invoke(null)
        val system = klass.getMethod("getSystemContext").invoke(activityThread) as Context
        system.createPackageContext("com.android.shell", 0)
    }
    override fun identity() = "${Build.MANUFACTURER} ${Build.MODEL}; uid=${Process.myUid()}; SDK=${Build.VERSION.SDK_INT}"
    override fun watch(binder: IBinder) {
        owner?.unlinkToDeath(death, 0); owner = binder; binder.linkToDeath(death, 0)
    }
    @SuppressLint("WrongConstant") // Shell-only touch/trusted/focus flags are hidden from the public SDK IntDef.
    override fun createDisplay(): Int = onWorker {
        display?.display?.displayId ?: run {
            val images = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
            reader = images
            images.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.let { image ->
                    latestImage?.close(); latestImage = image
                    synchronized(frameLock) {
                        frames++; lastFrame = SystemClock.elapsedRealtime()
                    }
                }
            }, handler)
            try {
                val flags = 1 or 8 or 64 or 256 or 1024 or 2048 or 4096 or 16384 or 65536
                val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val created = manager.createVirtualDisplay("BAAH NEO", 1280, 720, 240, images.surface, flags)
                    ?: error("系统拒绝创建虚拟屏")
                display = created; created.display.displayId
            } catch (e: Throwable) { images.close(); reader = null; throw e }
        }
    }
    override fun releaseDisplay() { onWorker {
        display?.release(); display = null; latestImage?.close(); latestImage = null; reader?.close(); reader = null
        synchronized(frameLock) { bitmap?.recycle(); bitmap = null; frames = 0; lastFrame = 0; convertedFrame = -1; conversions = 0 }
    } }
    private fun snapshot(): Bitmap = onWorker {
        // Keep one latest hardware image; read pixels only for a real screenshot/visible preview request.
        val image = latestImage ?: error("虚拟屏尚未出画")
        if (convertedFrame != frames) {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer.apply { rewind() })
            val frame = if (paddedWidth == image.width) padded else
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            bitmap?.recycle(); bitmap = frame; convertedFrame = frames
            synchronized(frameLock) { conversions++ }
        }
        bitmap!!.copy(Bitmap.Config.ARGB_8888, false)
    }
    override fun capture(): ParcelFileDescriptor {
        val copy = snapshot()
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "neo-frame-pipe", isDaemon = true) {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { copy.recycle() }
        }
        return pipe[0]
    }
    override fun captureBgr(): ParcelFileDescriptor {
        val copy = snapshot()
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "neo-bgr-pipe", isDaemon = true) {
            try {
                val pixels = IntArray(copy.width * copy.height)
                copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
                val bytes = ByteBuffer.allocate(16 + pixels.size * 3).order(ByteOrder.LITTLE_ENDIAN)
                bytes.put("BAAHBGR1".toByteArray()).putInt(copy.width).putInt(copy.height)
                pixels.forEach { bytes.put(it.toByte()).put((it shr 8).toByte()).put((it shr 16).toByte()) }
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes.array()) }
            } finally { copy.recycle(); runCatching { pipe[1].close() } }
        }
        return pipe[0]
    }
    @Synchronized override fun touch(action: Int, x: Float, y: Float): Boolean {
        val id = display?.display?.displayId ?: error("虚拟屏未创建")
        require(x in 0f..1279f && y in 0f..719f); require(action in listOf(0, 1, 2, 3))
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        return try {
            event.source = 0x1002
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id)
            val klass = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = klass.getMethod("getInstance").invoke(null)
            // Confirm DOWN delivery; queue MOVE/UP without waiting for a window that may change during a swipe.
            val mode = if (action == MotionEvent.ACTION_DOWN) 2 else 0
            val accepted = klass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, event, mode) as Boolean
            if (!accepted) android.util.Log.w("NeoInput", "Rejected action=$action display=$id x=$x y=$y mode=$mode")
            accepted
        } finally { event.recycle() }
    }
    override fun command(command: String): String {
        require(command.length <= 2000)
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val collector = thread(isDaemon = true) { process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { if (output.length < 2_000_000) output.appendLine(it) }
        } }
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); error("系统命令超时") }
        collector.join(2000); check(process.exitValue() == 0) { output.toString().takeLast(2000) }
        return output.toString()
    }
    override fun stats() = synchronized(frameLock) {
        JSONObject().put("display", display?.display?.displayId ?: -1).put("frames", frames)
            .put("pixelConversions", conversions)
            .put("screenOffRequested", screenOff).put("powerCalls", powerCalls).put("powerError", powerError)
            .put("interactive", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
            .put("timeMs", SystemClock.elapsedRealtime())
            .put("lastFrameAgeMs", if (lastFrame == 0L) -1 else SystemClock.elapsedRealtime() - lastFrame).toString()
    }
    override fun setScreenPower(on: Boolean) { onWorker {
        val identity = Binder.clearCallingIdentity()
        try {
            handler.removeCallbacks(keepScreenOff)
            applyScreenPower(on)
            powerError = ""; screenOff = !on
            if (!on) handler.postDelayed(keepScreenOff, 500)
        } catch (error: Exception) {
            powerError = (error.cause ?: error).toString()
            throw IllegalStateException("屏幕电源操作失败：$powerError", error)
        } finally { Binder.restoreCallingIdentity(identity) }
    } }
    private fun applyScreenPower(on: Boolean) {
        val surface = Class.forName("android.view.SurfaceControl")
        val tokens = if (Build.VERSION.SDK_INT >= 29) {
            val api = physicalDisplayApi
            val ids = api.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            check(ids.isNotEmpty()) { "没有物理显示屏" }
            ids.map { api.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, it) }
        } else listOf(surface.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0))
        val method = surface.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        tokens.forEach { method.invoke(null, it, if (on) 2 else 0) }
        powerCalls++
    }
    // Android 14 moved physical display enumeration into system_server's DisplayControl.
    private val physicalDisplayApi: Class<*> by lazy { loadPhysicalDisplayApi() }
    @SuppressLint("BlockedPrivateApi") // Shizuku shell process; independent of the app target-SDK restrictions.
    private fun loadPhysicalDisplayApi(): Class<*> {
        val surface = Class.forName("android.view.SurfaceControl")
        return if (runCatching { surface.getMethod("getPhysicalDisplayIds") }.isSuccess) surface else {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = factory.getDeclaredMethod("createClassLoader", String::class.java, String::class.java,
                String::class.java, ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            val loader = create.invoke(null, android.system.Os.getenv("SYSTEMSERVERCLASSPATH"), null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            val api = loader.loadClass("com.android.server.display.DisplayControl")
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java).apply { isAccessible = true }
                .invoke(Runtime.getRuntime(), api, "android_servers")
            api
        }
    }
    @Synchronized override fun mutePackage(packageName: String, muted: Boolean) {
        require(packageName in setOf("com.YostarJP.BlueArchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili", "com.nexon.bluearchive"))
        if (muted) {
            if (packageName !in audioModes) {
                val old = command("cmd appops get $packageName PLAY_AUDIO")
                audioModes[packageName] = Regex("PLAY_AUDIO: (allow|ignore|deny|default|foreground)").find(old)?.groupValues?.get(1) ?: "default"
            }
            command("cmd appops set $packageName PLAY_AUDIO ignore")
        } else audioModes.remove(packageName)?.let { command("cmd appops set $packageName PLAY_AUDIO $it") }
    }
    override fun destroy() {
        if (screenOff) runCatching { setScreenPower(true) }
        audioModes.keys.toList().forEach { runCatching { mutePackage(it, false) } }
        runCatching { releaseDisplay() }; worker.quitSafely(); exitProcess(0)
    }
    private fun <T> onWorker(block: () -> T): T {
        if (Looper.myLooper() == worker.looper) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post { result = runCatching(block); latch.countDown() }
        check(latch.await(15, TimeUnit.SECONDS)) { "虚拟屏操作超时" }
        return result!!.getOrThrow()
    }
}
