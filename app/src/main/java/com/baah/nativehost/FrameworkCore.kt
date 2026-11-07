package com.baah.nativehost

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import android.os.ParcelFileDescriptor
import android.os.SystemClock

object FrameworkCore {
    interface Api : Library {
        fun MaaVersion(): String
        fun MaaCustomControllerCreate(callbacks: Pointer, arg: Pointer?): Pointer?
        fun MaaControllerDestroy(controller: Pointer)
        fun MaaControllerPostConnection(controller: Pointer): Long
        fun MaaControllerPostScreencap(controller: Pointer): Long
        fun MaaControllerPostClick(controller: Pointer, x: Int, y: Int): Long
        fun MaaControllerPostSwipe(controller: Pointer, x: Int, y: Int, x2: Int, y2: Int, ms: Int): Long
        fun MaaControllerPostClickKey(controller: Pointer, keycode: Int): Long
        fun MaaControllerWait(controller: Pointer, id: Long): Int
        fun MaaControllerCachedImage(controller: Pointer, image: Pointer): Byte
        fun MaaImageBufferCreate(): Pointer
        fun MaaImageBufferDestroy(image: Pointer)
        fun MaaImageBufferSetEncoded(image: Pointer, bytes: ByteArray, size: Long): Byte
        fun MaaImageBufferGetEncoded(image: Pointer): Pointer?
        fun MaaImageBufferGetEncodedSize(image: Pointer): Long
        fun MaaImageBufferSetRawData(image: Pointer, bytes: Pointer, width: Int, height: Int, type: Int): Byte
        fun MaaStringBufferSet(buffer: Pointer, value: String): Byte
        fun MaaControllerSetOption(controller: Pointer, key: Int, value: Pointer, size: Long): Byte
    }
    val api: Api by lazy { Native.load("MaaFramework", Api::class.java) }
    fun version(): Result<String> = runCatching {
        api.MaaVersion()
    }
}

/** Official v5.15.0 callback ABI; strong references survive until controller destruction. */
class FrameworkController(private val remote: IControlService, private val onPoint: (Float, Float) -> Unit = { _, _ -> }) : AutoCloseable {
    fun interface Basic : Callback { fun invoke(arg: Pointer?): Byte }
    fun interface Buffer : Callback { fun invoke(arg: Pointer?, buffer: Pointer): Byte }
    fun interface Features : Callback { fun invoke(arg: Pointer?): Long }
    fun interface Text : Callback { fun invoke(text: String, arg: Pointer?): Byte }
    fun interface Click : Callback { fun invoke(x: Int, y: Int, arg: Pointer?): Byte }
    fun interface Swipe : Callback { fun invoke(x: Int, y: Int, x2: Int, y2: Int, ms: Int, arg: Pointer?): Byte }
    fun interface Touch : Callback { fun invoke(contact: Int, x: Int, y: Int, pressure: Int, arg: Pointer?): Byte }
    fun interface Key : Callback { fun invoke(key: Int, arg: Pointer?): Byte }
    fun interface Shell : Callback { fun invoke(cmd: String, timeout: Long, arg: Pointer?, buffer: Pointer): Byte }
    private val api = FrameworkCore.api
    private var failure: Throwable? = null
    private var closed = false
    private fun checked(block: () -> Boolean): Byte = try { if (block()) 1 else 0 } catch (e: Throwable) { failure = e; 0 }
    private val callbacks: List<Callback> = listOf(
        Basic { checked { remote.asBinder().isBinderAlive } },
        Basic { checked { remote.asBinder().isBinderAlive } },
        Buffer { _, buffer -> api.MaaStringBufferSet(buffer, "baah-neo-private-display") },
        Features { 4L },
        Text { text, _ -> checked { remote.command("am start --display ${org.json.JSONObject(remote.stats()).getInt("display")} --activity-exclude-from-recents -n ${component(text)}"); true } },
        Text { text, _ -> checked { remote.command("am force-stop ${component(text).substringBefore('/')}"); true } },
        Buffer { _, buffer -> checked {
            val bytes = readCaptureBgr()
            Memory((bytes.size - 16).toLong()).use { raw ->
                raw.write(0, bytes, 16, bytes.size - 16)
                api.MaaImageBufferSetRawData(buffer, raw, 1280, 720, 16).toInt() != 0
            }
        } },
        Click { x, y, _ -> checked { gesture(x, y, x, y, 45) } },
        Swipe { x, y, x2, y2, ms, _ -> checked { gesture(x, y, x2, y2, ms) } },
        Touch { contact, x, y, _, _ -> checked { contact == 0 && remote.touch(0, x.toFloat(), y.toFloat()) } },
        Touch { contact, x, y, _, _ -> checked { contact == 0 && remote.touch(2, x.toFloat(), y.toFloat()) } },
        Key { _, _ -> checked { remote.touch(1, lastX, lastY) } },
        Key { code, _ -> checked { remote.command("input -d ${org.json.JSONObject(remote.stats()).getInt("display")} keyevent $code"); true } },
        Text { _, _ -> 0 }, Key { _, _ -> 0 }, Key { _, _ -> 0 },
        Click { _, _, _ -> 0 }, Click { _, _, _ -> 0 },
        Shell { cmd, _, _, buffer -> checked { api.MaaStringBufferSet(buffer, remote.command(cmd)).toInt() != 0 } },
        Basic { 1 }, Buffer { _, buffer -> api.MaaStringBufferSet(buffer, remote.stats()) }
    )
    private var lastX = 0f
    private var lastY = 0f
    private val table = Memory((callbacks.size * Native.POINTER_SIZE).toLong()).apply {
        callbacks.forEachIndexed { index, callback -> setPointer((index * Native.POINTER_SIZE).toLong(), CallbackReference.getFunctionPointer(callback)) }
    }
    private val handle = api.MaaCustomControllerCreate(table, null) ?: error("MaaFramework 控制器创建失败")
    internal val piControllerHandle: Pointer get() = handle
    init {
        val raw = Memory(1).apply { setByte(0, 1) }
        // Preserve BAAH's 1280x720 coordinates.
        check(api.MaaControllerSetOption(handle, 3, raw, 1).toInt() != 0)
        waitFor(api.MaaControllerPostConnection(handle))
    }
    @Synchronized fun capture(): ByteArray {
        check(!closed) { "控制器已关闭" }
        waitFor(api.MaaControllerPostScreencap(handle))
        val image = api.MaaImageBufferCreate()
        try {
            check(api.MaaControllerCachedImage(handle, image).toInt() != 0)
            val pointer = api.MaaImageBufferGetEncoded(image) ?: error("MaaFramework 截图为空")
            return pointer.getByteArray(0, api.MaaImageBufferGetEncodedSize(image).toInt())
        } finally { api.MaaImageBufferDestroy(image) }
    }
    @Synchronized fun click(x: Int, y: Int) { check(!closed); waitFor(api.MaaControllerPostClick(handle, x, y)) }
    @Synchronized fun key(code: Int) { check(!closed); waitFor(api.MaaControllerPostClickKey(handle, code)) }
    @Synchronized fun swipe(x: Int, y: Int, x2: Int, y2: Int, ms: Int) { check(!closed); waitFor(api.MaaControllerPostSwipe(handle, x, y, x2, y2, ms)) }
    @Synchronized fun captureBgr(): ByteArray {
        check(!closed) { "控制器已关闭" }
        // Python consumes the same packed frame already produced by the capture service.
        return readCaptureBgr()
    }

    private fun readCaptureBgr(): ByteArray {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(remote.captureBgr()).use { it.readBytes() }
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        check(bytes.size == 16 + 1280 * 720 * 3 &&
            bytes.copyOfRange(0, 8).contentEquals("BAAHBGR1".toByteArray()) &&
            header.getInt(8) == 1280 && header.getInt(12) == 720) { "BGR 截图格式错误" }
        return bytes
    }

    private fun waitFor(id: Long) {
        check(id != 0L && api.MaaControllerWait(handle, id) == 3000) { "MaaFramework 操作失败：${failure?.message ?: "控制请求被拒绝"}" }
    }
    private fun gesture(x: Int, y: Int, x2: Int, y2: Int, ms: Int): Boolean {
        require(ms in 0..60_000)
        if (!remote.touch(0, x.toFloat(), y.toFloat())) return false
        onPoint(x.toFloat(), y.toFloat())
        lastX = x.toFloat(); lastY = y.toFloat()
        try {
            val begin = SystemClock.uptimeMillis()
            do {
                val fraction = if (ms == 0) 1f else ((SystemClock.uptimeMillis() - begin).toFloat() / ms).coerceAtMost(1f)
                lastX = x + (x2 - x) * fraction; lastY = y + (y2 - y) * fraction
                check(remote.touch(2, lastX, lastY)) { "触控移动失败：($lastX, $lastY)" }
                onPoint(lastX, lastY)
                if (fraction >= 1f) break
                Thread.sleep(16)
            } while (true)
            return true
        } finally { check(remote.touch(1, lastX, lastY)) { "触控释放失败" } }
    }
    @Synchronized override fun close() { if (!closed) { closed = true; api.MaaControllerDestroy(handle); table.close() } }
    private fun component(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.$]+(?:/[A-Za-z0-9_.$]+)?")))
        return value
    }
}
