package com.hako.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultFilterTest {
    @Test fun matchesAdultCategoryNames() {
        listOf("XXX", "TR | XXX", "Adult", "ADULTS 18+", "+18 Kanallar", "Yetişkin", "YETİŞKİNLER",
            "VOD | PORN", "Erotik Filmler", "Playboy TV", "DE: Erwachsene", "FR | Adulte", "ES Adultos",
            "Brazzers", "Dorcel TV", "[18] Night", "Hot XXX").forEach {
            assertTrue("eşleşmeli: $it", Parental.isAdultName(it))
        }
    }

    @Test fun ignoresSafeNames() {
        listOf("Sussex News", "Essex FM", "Adult Swim", "Young Adult Movies", "Sport", "Belgesel",
            "TR | Ulusal", "Formula 1", "2018 Filmleri", "Kids 7+", "Sextet Jazz", "Pornichet Tour").forEach {
            assertFalse("eşleşmemeli: $it", Parental.isAdultName(it))
        }
    }
}
