package com.jsm.nsnd.ui.common

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Shared startup checks; accepting limited operation must not loop between login and main. */
object AppPermissions {
    private val reviewState = PermissionReviewState()

    fun missingRuntimePermissions(context: Context): List<String> =
        missingRuntimePermissions(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    internal fun missingRuntimePermissions(sdk: Int, granted: (String) -> Boolean): List<String> = buildList {
        for (permission in listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS)) {
            if (!granted(permission)) add(permission)
        }
        if (sdk >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        // Request the pair together, while respecting a user's approximate-location choice.
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun canUseFullScreenAlert(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun missingPermissions(context: Context): Set<String> =
        missingRuntimePermissions(context).toSet() +
            if (canUseFullScreenAlert(context)) emptySet() else setOf(Manifest.permission.USE_FULL_SCREEN_INTENT)

    fun markReviewed(context: Context) = reviewState.markReviewed(missingPermissions(context))

    fun resetReview() = reviewState.reset()

    fun needsReview(context: Context): Boolean {
        val missing = missingPermissions(context)
        return reviewState.needsReview(missing,
            Manifest.permission.CALL_PHONE in missing || Manifest.permission.READ_PHONE_STATE in missing)
    }
}

internal class PermissionReviewState {
    private var reviewed: Set<String>? = null

    fun markReviewed(missing: Set<String>) { reviewed = missing.toSet() }

    fun reset() { reviewed = null }

    fun needsReview(missing: Set<String>, mandatoryMissing: Boolean): Boolean {
        val previous = reviewed
        if (mandatoryMissing || missing.any { previous == null || it !in previous }) return true
        // Observe grants too, so revoking the same permission later triggers a fresh review.
        markReviewed(missing)
        return false
    }
}
