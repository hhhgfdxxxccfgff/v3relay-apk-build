package com.example.relay.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Runtime tamper / debugger detection for release builds.
 *
 * The expected value is the SHA-256 of the release signing certificate (X.509 DER).
 * Debug builds are exempt so development installs keep working.
 */
object IntegrityGuard {
    private const val TAG = "IntegrityGuard"

    // SHA-256 of the V3 Relay Upload certificate (release keystore).
    private const val EXPECTED_CERT_SHA256 =
        "FF001D9A2EC1182A8E0E1F5120F17F5393CB0761062E413F4E847012CCA67EF7"

    fun isEnvironmentTrusted(context: Context, isDebugBuild: Boolean): Boolean {
        if (isDebugBuild) return true
        return try {
            signatureMatches(context) && !debuggerAttached()
        } catch (t: Throwable) {
            Log.e(TAG, "Integrity check failed to run", t)
            false
        }
    }

    private fun signatureMatches(context: Context): Boolean {
        val pm = context.packageManager
        val packageName = context.packageName
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        if (certs.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        return certs.any { cert ->
            digest.reset()
            val hash = digest.digest(cert.toByteArray())
            hash.joinToString("") { "%02X".format(it) } == EXPECTED_CERT_SHA256
        }
    }

    private fun debuggerAttached(): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        return try {
            val tracer = File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
            }
            tracer != null && tracer != 0
        } catch (_: Throwable) {
            false
        }
    }
}
