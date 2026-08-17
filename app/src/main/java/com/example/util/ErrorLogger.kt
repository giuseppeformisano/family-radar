package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object ErrorLogger {

    private const val TAG = "ErrorLogger"
    private const val COLLECTION = "error_logs"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(context, throwable, thread.name, isCrash = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash to Firestore", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(context: Context, throwable: Throwable) {
        try {
            write(context, throwable, Thread.currentThread().name, isCrash = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log exception to Firestore", e)
        }
    }

    private fun write(context: Context, throwable: Throwable, threadName: String, isCrash: Boolean) {
        val firestore = try { FirebaseFirestore.getInstance() } catch (_: Exception) { return }
        val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "unknown" }

        firestore.collection(COLLECTION).add(hashMapOf(
            "timestamp" to System.currentTimeMillis(),
            "isCrash" to isCrash,
            "thread" to threadName,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.SDK_INT,
            "appVersion" to (versionName ?: "unknown"),
            "userId" to (uid ?: "anonymous"),
            "error" to throwable.javaClass.simpleName,
            "message" to (throwable.message ?: ""),
            "stacktrace" to throwable.stackTraceToString().take(8000)
        )).addOnFailureListener { e ->
            Log.e(TAG, "Firestore write failed", e)
        }
    }
}
