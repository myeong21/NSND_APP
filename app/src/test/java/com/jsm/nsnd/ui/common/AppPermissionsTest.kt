package com.jsm.nsnd.ui.common

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class AppPermissionsTest {
    @Test fun startupRequestsAllRuntimePermissionsIncludingTheLocationPair() {
        assertEquals(setOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            AppPermissions.missingRuntimePermissions(36) { false }.toSet())
    }

    @Test fun notificationsAreNotRequestedOnAndroidBeforeThirteen() {
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in AppPermissions.missingRuntimePermissions(32) { false })
    }

    @Test fun approximateLocationIsAcceptedWithoutRepeatingTheLocationPrompt() {
        val missing = AppPermissions.missingRuntimePermissions(36) { it == Manifest.permission.ACCESS_COARSE_LOCATION }
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in missing)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in missing)
    }

    @Test fun grantedPermissionsAreNotRequestedAgain() {
        assertTrue(AppPermissions.missingRuntimePermissions(36) { true }.isEmpty())
    }

    @Test fun revokingAPermissionRequiresANewReview() {
        val state = PermissionReviewState()
        state.markReviewed(emptySet())
        assertTrue(state.needsReview(setOf("location"), false))
    }

    @Test fun decliningOptionalPermissionsDoesNotLoopBetweenScreens() {
        val state = PermissionReviewState()
        state.markReviewed(setOf("location"))
        assertFalse(state.needsReview(setOf("location"), false))
        assertTrue(state.needsReview(setOf("location", "notifications"), false))
    }

    @Test fun grantingThenRevokingTheSamePermissionPromptsAgain() {
        val state = PermissionReviewState()
        state.markReviewed(setOf("sms"))
        assertFalse(state.needsReview(emptySet(), false))
        assertTrue(state.needsReview(setOf("sms"), false))
    }

    @Test fun mandatoryPhonePermissionsCannotBeSkipped() {
        val state = PermissionReviewState()
        state.markReviewed(setOf("phone"))
        assertTrue(state.needsReview(setOf("phone"), true))
    }

    @Test fun freshProcessReviewsPreviouslyDeniedPermissionsAgain() {
        assertTrue(PermissionReviewState().needsReview(setOf("location"), false))
        assertFalse(PermissionReviewState().needsReview(emptySet(), false))
    }

    @Test fun revokingFullScreenAlertsAlsoTriggersReview() {
        val state = PermissionReviewState()
        state.markReviewed(emptySet())
        assertTrue(state.needsReview(setOf(Manifest.permission.USE_FULL_SCREEN_INTENT), false))
    }

    @Test fun returningToTheAppRequestsStillMissingPermissionsAgain() {
        val state = PermissionReviewState()
        state.markReviewed(setOf("location"))
        assertFalse(state.needsReview(setOf("location"), false))
        state.reset()
        assertTrue(state.needsReview(setOf("location"), false))
    }
}
