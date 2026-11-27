package com.will.ambienthome.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Безопасный неблокирующий клиент опционального root-моста `files/remote.fifo`.
 * Открывает FIFO строго с флагом O_WRONLY | O_NONBLOCK: при отсутствии активного читателя
 * мгновенно возвращает ENXIO (0 зависших потоков, 0 утечек).
 */
object RootBridgeClient {

    private const val TAG = "AmbientHome"
    private const val FIFO_NAME = "remote.fifo"
    private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._]{3,128}$")
    private val SAFE_VOL_REGEX = Regex("^[a-zA-Z0-9:._,-]{1,64}$")
    private val ALLOWED_AFR_FPS = intArrayOf(0, 24, 25, 30, 50, 60)
    private val PROTECTED_PACKAGES = setOf(
        "android",
        "system",
        "com.android.systemui",
        "com.will.ambienthome",
        "com.android.bluetooth",
        "com.android.shell",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.providers.settings",
        "com.android.wifi.resources",
        "com.android.keychain",
        "com.android.bootfix",
        "com.android.inputmethod.latin",
        "com.android.tv.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller"
    )

    data class BridgeResult(
        val ok: Boolean,
        val error: String = ""
    )

    fun isAvailable(ctx: Context): Boolean {
        val fifo = resolveSafeFifo(ctx) ?: return false
        return try {
            val st = Os.lstat(fifo.absolutePath)
            OsConstants.S_ISFIFO(st.st_mode)
        } catch (_: Throwable) {
            false
        }
    }

    fun sendKey(ctx: Context, keyCode: Int): BridgeResult {
        if (keyCode !in 1..300) return BridgeResult(false, "invalid_keycode")
        return writeLine(ctx, "KEY $keyCode")
    }

    fun sendSimple(ctx: Context, verb: String): BridgeResult {
        if (verb != "PASTE" && verb != "SLEEP" && verb != "WAKE") {
            return BridgeResult(false, "invalid_verb")
        }
        return writeLine(ctx, verb)
    }

    fun sendForceStop(ctx: Context, packageName: String): BridgeResult {
        val pkg = packageName.trim()
        if (!isAllowedForceStopPackage(pkg)) return BridgeResult(false, "forbidden_package")
        return writeLine(ctx, "FORCESTOP $pkg")
    }

    /** Соответствует контракту #36: `AFR 0` (сброс) или `AFR <24|25|30|50|60>`. */
    fun sendAfr(ctx: Context, refreshRate: Int): BridgeResult {
        if (refreshRate !in ALLOWED_AFR_FPS) return BridgeResult(false, "invalid_afr_mode")
        return writeLine(ctx, "AFR $refreshRate")
    }

    /** Соответствует контракту #36: `EJECT <vol>` (например `public:8,1`). */
    fun sendEject(ctx: Context, volumeId: String): BridgeResult {
        val vol = volumeId.trim()
        if (!isValidVolumeId(vol)) {
            return BridgeResult(false, "invalid_volume")
        }
        return writeLine(ctx, "EJECT $vol")
    }

    internal fun isValidVolumeId(vol: String): Boolean =
        SAFE_VOL_REGEX.matches(vol) &&
            !vol.startsWith("-") &&
            !vol.startsWith(".") &&
            !vol.startsWith(",") &&
            !vol.contains("..")

    fun isAllowedForceStopPackage(pkg: String): Boolean {
        if (!SAFE_PKG_REGEX.matches(pkg) || !pkg.contains('.')) return false
        if (pkg.startsWith('.') || pkg.endsWith('.') || pkg.contains("..")) return false
        return pkg !in PROTECTED_PACKAGES
    }

    private fun writeLine(ctx: Context, line: String): BridgeResult {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            return BridgeResult(false, "invalid_line")
        }
        val fifo = resolveSafeFifo(ctx) ?: return BridgeResult(false, "bridge_fifo_missing")
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        return try {
            val st = Os.lstat(fifo.absolutePath)
            if (!OsConstants.S_ISFIFO(st.st_mode)) return BridgeResult(false, "bridge_not_fifo")
            val fd = Os.open(fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            try {
                val written = Os.write(fd, bytes, 0, bytes.size)
                if (written == bytes.size) BridgeResult(true) else BridgeResult(false, "bridge_short_write")
            } finally {
                try {
                    Os.close(fd)
                } catch (_: Throwable) {
                }
            }
        } catch (e: ErrnoException) {
            val err = if (e.errno == OsConstants.ENXIO) "bridge_not_running" else "bridge_errno_${e.errno}"
            BridgeResult(false, err)
        } catch (t: Throwable) {
            Log.w(TAG, "RootBridgeClient write failed", t)
            BridgeResult(false, "bridge_io_error")
        }
    }

    private fun resolveSafeFifo(ctx: Context): File? {
        return try {
            val dir = ctx.filesDir ?: return null
            val fifo = File(dir, FIFO_NAME)
            val parentCanon = dir.canonicalPath
            val fifoParentCanon = fifo.parentFile?.canonicalPath ?: return null
            if (fifoParentCanon != parentCanon) null else fifo
        } catch (_: Throwable) {
            null
        }
    }
}
