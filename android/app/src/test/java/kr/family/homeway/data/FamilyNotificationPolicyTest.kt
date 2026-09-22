package kr.family.homeway.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyNotificationPolicyTest {
    @Test fun openConversationAlwaysClearsEvenWhenNotificationPermissionWasRevoked() {
        for (sound in listOf(false, true)) for (allowed in listOf(false, true)) {
            assertEquals(FamilyNotificationPolicy.Action.CLEAR,
                FamilyNotificationPolicy.action(true, allowed, allowed, allowed, sound))
        }
    }

    @Test fun turningSoundOnCannotBypassAnyExistingOsBlock() {
        for (sound in listOf(false, true)) for (blocked in 0..2) {
            assertEquals(FamilyNotificationPolicy.Action.IGNORE,
                FamilyNotificationPolicy.action(false, blocked != 0, blocked != 1, blocked != 2, sound))
        }
    }

    @Test fun backgroundNotificationsAreSilentUnlessExplicitlyEnabled() {
        assertEquals(FamilyNotificationPolicy.Action.SILENT,
            FamilyNotificationPolicy.action(false, true, true, true, false))
        assertEquals(FamilyNotificationPolicy.Action.AUDIBLE,
            FamilyNotificationPolicy.action(false, true, true, true, true))
    }
}
