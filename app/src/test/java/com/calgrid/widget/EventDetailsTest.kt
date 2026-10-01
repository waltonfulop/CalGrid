package com.calgrid.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDetailsTest {
    private val time = "19:00 – 20:00"

    @Test
    fun multilineAddressKeepsEveryPartOnTheDetailsLine() {
        assertEquals(
            "$time · 2040 Budaörs Példa utca 12. Magyarország",
            eventDetails(time, "2040 Budaörs\nPélda utca 12.\nMagyarország"),
        )
    }

    @Test
    fun normalizesCalendarWhitespaceAndLineSeparators() {
        for (separator in listOf("\n", "\r\n", "\r", "\t", "\u0085", "\u2028", "\u2029", "\u00a0")) {
            assertEquals(
                "Separator ${separator.toCharArray().map { it.code }}",
                "$time · 2040 Budaörs Példa utca 12.",
                eventDetails(time, " 2040 Budaörs${separator}  Példa utca 12. \n"),
            )
        }
    }

    @Test
    fun missingLocationHasNoTrailingSeparator() {
        for (location in listOf(null, "", " \r\n\t\u00a0\u2028")) {
            assertEquals(time, eventDetails(time, location))
        }
    }

    @Test
    fun singleLineAddressIsPreservedWithoutCharacterLimit() {
        val location = "2040 Budaörs, Példa utca 12., második emelet, 5. ajtó, Magyarország"
        assertEquals("$time · $location", eventDetails(time, location))
    }

    @Test
    fun allDayLabelIsPreserved() {
        assertEquals("Egész nap · Budaörs Példa utca 12.", eventDetails("Egész nap", "Budaörs\nPélda utca 12."))
    }
}
