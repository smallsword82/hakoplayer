package com.hako.player

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultRecentTest {
    private fun live(name: String, cat: String = "5", adult: Boolean = false) =
        Item("1", name, null, cat, Kind.LIVE, "http://x/1.ts", adult = adult)

    @After fun reset() { Session.catNames.clear(); Session.adultCats.clear(); Parental.unlocked = false }

    @Test fun detectsAdultByFlagNameAndCategory() {
        assertTrue(Parental.isAdultItem(live("Kanal", adult = true), "lock"))
        assertTrue(Parental.isAdultItem(live("XXX Night"), "lock"))
        Session.catNames["LIVE:9"] = "TR | YETİŞKİN"
        assertTrue(Parental.isAdultItem(live("Kanal 1", cat = "9"), "lock"))
        Session.adultCats += "LIVE:10"
        assertTrue(Parental.isAdultItem(live("Kanal 2", cat = "10"), "hide"))
    }

    @Test fun pinUnlockDoesNotMakeAdultSafe() {
        Parental.unlocked = true
        assertTrue(Parental.isAdultItem(live("Hot XXX"), "lock"))
    }

    @Test fun normalChannelsAndOffMode() {
        Session.catNames["LIVE:5"] = "TR-ULUSAL"
        assertFalse(Parental.isAdultItem(live("TRT 1"), "lock"))
        assertFalse(Parental.isAdultItem(live("Adult Swim"), "lock"))
        assertFalse(Parental.isAdultItem(live("XXX Night"), "off"))
    }
}
