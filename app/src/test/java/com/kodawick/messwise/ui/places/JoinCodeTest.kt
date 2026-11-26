package com.kodawick.messwise.ui.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinCodeTest {
    @Test fun typedCodes() {
        assertEquals("ZHFC86", extractJoinCode("ZHFC86"))
        assertEquals("ZHFC86", extractJoinCode("zhf-c86"))
        assertEquals("ZHFC86", extractJoinCode(" zhf c86 "))
    }

    @Test fun linksAndQrCodes() {
        assertEquals("ZHFC86", extractJoinCode("https://messwise-r9qh.onrender.com/join?code=ZHFC86"))
        assertEquals("ZHFC86", extractJoinCode("messwise://join/ZHFC86"))
        assertEquals("ZHFC86", extractJoinCode(joinLink("zhf-c86")))
    }

    @Test fun pastedInvites() {
        assertEquals("ZHFC86", extractJoinCode("Join MSD Mushaf on MessWise to see our tea and kitchen bills. Code: ZHF-C86\nOpen: https://messwise-r9qh.onrender.com/join?code=ZHFC86"))
        assertEquals("ZHFC86", extractJoinCode("MessWise پر MSD Mushaf میں شامل ہوں اور چائے اور کچن کا حساب دیکھیں۔ کوڈ: ZHF-C86\nکھولیں: https://messwise-r9qh.onrender.com/join?code=ZHFC86"))
        // messages from version 1.0.0 (Play link, no join link)
        assertEquals("ZHFC86", extractJoinCode("Join MSD Mushaf on MessWise to see our tea and kitchen bills. Code: ZHF-C86\nGet the app: https://play.google.com/store/apps/details?id=com.kodawick.messwise"))
        assertEquals("ZHFC86", extractJoinCode("MessWise پر MSD Mushaf میں شامل ہوں اور چائے اور کچن کا حساب دیکھیں۔ کوڈ: ZHF-C86\nایپ: https://play.google.com/store/apps/details?id=com.kodawick.messwise"))
    }

    @Test fun notACode() {
        assertNull(extractJoinCode(null))
        assertNull(extractJoinCode(""))
        assertNull(extractJoinCode("hello"))
        assertNull(extractJoinCode("ABC12"))
        assertNull(extractJoinCode("https://example.com"))
    }
}
