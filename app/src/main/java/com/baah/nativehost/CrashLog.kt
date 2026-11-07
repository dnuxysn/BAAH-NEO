package com.baah.nativehost
import android.content.Context
import java.io.File
object CrashLog {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { record(context, thread.name, failure) }
            if (previous != null) previous.uncaughtException(thread, failure)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(1) }
        }
    }
    fun record(context: Context, thread: String, failure: Throwable): File {
        val file = File(context.filesDir, "logs/neo-crash-${System.currentTimeMillis()}.txt")
        UserData.atomic(file, "BAAH NEO ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\nThread: $thread\n${failure.stackTraceToString()}")
        return file
    }
}
