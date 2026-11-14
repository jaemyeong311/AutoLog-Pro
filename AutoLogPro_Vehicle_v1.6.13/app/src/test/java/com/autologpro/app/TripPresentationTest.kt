package com.autologpro.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TripPresentationTest {
    @Test fun underOneMinuteUsesSeconds() {
        assertEquals("20초", TripPresentation.duration(1000L, 21_000L))
        assertEquals("0초", TripPresentation.duration(1000L, 1000L))
    }

    @Test fun overOneMinuteUsesMinutesAndSeconds() {
        assertEquals("1분", TripPresentation.duration(1000L, 61_000L))
        assertEquals("1분 24초", TripPresentation.duration(1000L, 85_000L))
    }

    @Test fun openTripIsNotZeroDuration() {
        assertEquals("진행 중", TripPresentation.duration(1000L, null))
    }
}
