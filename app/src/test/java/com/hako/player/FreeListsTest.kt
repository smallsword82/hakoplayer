package com.hako.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeListsTest {
    @Test fun cleansIptvOrgNames() {
        assertEquals("ATV", FreeLists.cleanName("ATV (Turkiye) (1080p)"))
        assertEquals("TRT 1", FreeLists.cleanName("TRT 1 (1440p)"))
        assertEquals("Haberturk TV (aralıklı yayın)", FreeLists.cleanName("Haberturk TV (720p) [Not 24/7]"))
        assertEquals("TRT 2 (bölge kısıtlı)", FreeLists.cleanName("TRT 2 (1440p) [Geo-blocked]"))
        assertEquals("NTV", FreeLists.cleanName("NTV (Turkiye)"))
        assertEquals("Flash Haber TV", FreeLists.cleanName("Flash Haber TV"))
    }

    @Test fun mapsGroups() {
        assertEquals("Genel", FreeLists.group("General"))
        assertEquals("Haber", FreeLists.group("News"))
        assertEquals("Çocuk", FreeLists.group("Animation;Kids"))
        assertEquals("Kültür", FreeLists.group("Culture;General;Religious"))
        assertEquals("Diğer", FreeLists.group("Undefined"))
        assertEquals("Bilinmeyen", FreeLists.group("Bilinmeyen"))
    }

    @Test fun findsSavedTrListByUrl() {
        val other = Playlist("1", "Ev", "xtream", "http://x", "u", "p")
        val tr = Playlist("2", "Benim adım", "m3u", url = FreeLists.TR_URL)
        assertNull(FreeLists.findTr(listOf(other)))
        assertEquals("2", FreeLists.findTr(listOf(other, tr))?.id)
        assertTrue(FreeLists.isIptvOrg(FreeLists.TR_URL))
    }
}
