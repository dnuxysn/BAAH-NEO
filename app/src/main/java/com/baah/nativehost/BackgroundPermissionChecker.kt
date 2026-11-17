package com.baah.nativehost

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class BackgroundPermissionState { Allowed, Denied, Unknown }

data class BackgroundPermissionStatus(
    val autoStart: BackgroundPermissionState,
    val backgroundRun: BackgroundPermissionState,
) {
    val confirmedDenied: Boolean
        get() = autoStart == BackgroundPermissionState.Denied || backgroundRun == BackgroundPermissionState.Denied
}

/** Checks actual permission state. Opening Settings is never treated as granting permission. */
object BackgroundPermissionChecker {
    private const val AUTO_START_URI =
        "content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps"

    suspend fun refresh(context: Context, control: ControlConnection? = null): BackgroundPermissionStatus =
        withContext(Dispatchers.IO) {
            val background = platformBackgroundState(context)
            if (!Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
                return@withContext BackgroundPermissionStatus(BackgroundPermissionState.Unknown, background)
            }
            val direct = readVivoAutoStart(context)
            if (direct != BackgroundPermissionState.Unknown || control?.granted() != true) {
                return@withContext BackgroundPermissionStatus(direct, background)
            }
            val fromShell = try {
                // Binding this already-authorized service does not create a display or start tasks.
                val remote = control.remote ?: withTimeoutOrNull(3_000) { control.connect() }
                if (remote == null || !remote.asBinder().isBinderAlive) BackgroundPermissionState.Unknown
                else {
                    val uid = context.applicationInfo.uid
                    val output = remote.command(
                        "content query --uri $AUTO_START_URI --where 'pkguid=$uid' --projection pkgname:pkguid:currentstate"
                    )
                    parseVivoAutoStart(output, context.packageName, uid)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BackgroundPermissionState.Unknown
            }
            BackgroundPermissionStatus(fromShell, background)
        }

    private fun platformBackgroundState(context: Context): BackgroundPermissionState = try {
        if (context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
            BackgroundPermissionState.Denied
        } else {
            // This API does not expose vivo's separate "allow high background power use" switch.
            BackgroundPermissionState.Unknown
        }
    } catch (_: Exception) {
        BackgroundPermissionState.Unknown
    }

    private fun readVivoAutoStart(context: Context): BackgroundPermissionState = try {
        context.contentResolver.query(
            Uri.parse(AUTO_START_URI),
            arrayOf("pkgname", "pkguid", "currentstate"),
            "pkgname=? AND pkguid=?",
            arrayOf(context.packageName, context.applicationInfo.uid.toString()),
            null,
        )?.use { cursor ->
            val states = mutableListOf<BackgroundPermissionState>()
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(cursor.getColumnIndexOrThrow("pkgname"))
                val uid = cursor.getInt(cursor.getColumnIndexOrThrow("pkguid"))
                val stateColumn = cursor.getColumnIndexOrThrow("currentstate")
                if (packageName == context.packageName && uid == context.applicationInfo.uid && !cursor.isNull(stateColumn)) {
                    states += decodeVivoAutoStart(cursor.getInt(stateColumn))
                }
            }
            states.singleOrNull() ?: BackgroundPermissionState.Unknown
        } ?: BackgroundPermissionState.Unknown
    } catch (_: Exception) {
        BackgroundPermissionState.Unknown
    }

    internal fun parseVivoAutoStart(output: String, packageName: String, uid: Int): BackgroundPermissionState {
        val states = output.lineSequence().filter { it.startsWith("Row: ") }.mapNotNull { line ->
            val fields = line.substringAfter(' ').substringAfter(' ').split(", ")
                .mapNotNull { field ->
                    val separator = field.indexOf('=')
                    if (separator <= 0) null else field.substring(0, separator).trim() to field.substring(separator + 1).trim()
                }.toMap()
            if (fields["pkgname"] != packageName || fields["pkguid"]?.toIntOrNull() != uid) null
            else fields["currentstate"]?.toIntOrNull()?.let(::decodeVivoAutoStart)
                ?: BackgroundPermissionState.Unknown
        }.toList()
        return states.singleOrNull() ?: BackgroundPermissionState.Unknown
    }

    // vivo PermissionManager 14.0.5.4 maps currentstate 0 to grant and 1 to denial.
    internal fun decodeVivoAutoStart(state: Int): BackgroundPermissionState = when (state) {
        0 -> BackgroundPermissionState.Allowed
        1 -> BackgroundPermissionState.Denied
        else -> BackgroundPermissionState.Unknown
    }
}
