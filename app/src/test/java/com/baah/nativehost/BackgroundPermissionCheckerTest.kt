package com.baah.nativehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPermissionCheckerTest {
    private val packageName = "com.baah.neo.only"
    private val uid = 10346

    private fun parse(output: String) =
        BackgroundPermissionChecker.parseVivoAutoStart(output, packageName, uid)

    private fun row(state: String, name: String = packageName, userId: Int = uid) =
        "Row: 0 setbyuser=1, pkguid=$userId, pkgname=$name, _id=69, currentstate=$state"

    @Test fun allowedAndDeniedUseTheCurrentPackageAndUid() {
        assertEquals(BackgroundPermissionState.Allowed, parse(row("0")))
        assertEquals(BackgroundPermissionState.Denied, parse(row("1")))
        assertEquals(BackgroundPermissionState.Unknown, parse(row("0", name = "com.baah.neo")))
        assertEquals(BackgroundPermissionState.Unknown, parse(row("0", userId = uid + 100000)))
    }

    @Test fun errorsMissingRowsUnknownValuesAndDuplicateRowsStayUnknown() {
        for (output in listOf(
            "",
            "No result found.",
            "SecurityException: Permission Denial",
            row("NULL"),
            row("2"),
            row("-1"),
            row("0") + "\n" + row("1"),
        )) {
            assertEquals(output, BackgroundPermissionState.Unknown, parse(output))
        }
    }

    @Test fun otherPackagesDoNotSupplyOurPermissionState() {
        val others = row("1", name = "com.other.app") + "\n"
        assertEquals(BackgroundPermissionState.Unknown, parse(others))
        assertEquals(BackgroundPermissionState.Allowed, parse(others + row("0")))
    }

    @Test fun onlyConfirmedDenialsTriggerThePermissionReminder() {
        assertFalse(BackgroundPermissionStatus(
            BackgroundPermissionState.Unknown, BackgroundPermissionState.Unknown,
        ).confirmedDenied)
        assertFalse(BackgroundPermissionStatus(
            BackgroundPermissionState.Allowed, BackgroundPermissionState.Unknown,
        ).confirmedDenied)
        assertTrue(BackgroundPermissionStatus(
            BackgroundPermissionState.Denied, BackgroundPermissionState.Unknown,
        ).confirmedDenied)
        assertTrue(BackgroundPermissionStatus(
            BackgroundPermissionState.Allowed, BackgroundPermissionState.Denied,
        ).confirmedDenied)
    }
}
