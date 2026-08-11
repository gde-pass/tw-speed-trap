package io.github.gdepass.twspeedtrap.service

import io.github.gdepass.twspeedtrap.service.NotificationThrottle.Rendered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationThrottleTest {
    @Test
    fun `first state always notifies`() {
        assertTrue(NotificationThrottle().shouldNotify(Rendered(0, null), 0L))
    }

    @Test
    fun `unchanged state never re-notifies`() {
        val throttle = NotificationThrottle()
        throttle.shouldNotify(Rendered(88, null), 0L)
        for (t in 1..60) {
            assertFalse(throttle.shouldNotify(Rendered(88, null), t * 1000L))
        }
    }

    @Test
    fun `speed changes are rate limited to the refresh floor`() {
        val throttle = NotificationThrottle()
        throttle.shouldNotify(Rendered(88, null), 0L)
        assertFalse(throttle.shouldNotify(Rendered(89, null), 1000L))
        assertFalse(throttle.shouldNotify(Rendered(90, null), 4000L))
        assertTrue(throttle.shouldNotify(Rendered(90, null), 5000L))
    }

    @Test
    fun `distance bucket changes bypass rate limiting`() {
        val throttle = NotificationThrottle()
        throttle.shouldNotify(Rendered(88, 800), 0L)
        assertTrue(throttle.shouldNotify(Rendered(88, 700), 1000L))
        assertTrue(throttle.shouldNotify(Rendered(88, 600), 2000L))
    }

    @Test
    fun `camera appearing and disappearing notify immediately`() {
        val throttle = NotificationThrottle()
        throttle.shouldNotify(Rendered(88, null), 0L)
        assertTrue(throttle.shouldNotify(Rendered(88, 900), 1000L))
        assertTrue(throttle.shouldNotify(Rendered(88, null), 2000L))
    }

    @Test
    fun `highway approach posts every boundary but stays bounded`() {
        // 130 km/h = 36.1 m/s, approaching from 1500 m at 1 Hz.
        val throttle = NotificationThrottle()
        var posts = 0
        val bucketsSeen = mutableSetOf<Int>()
        var distance = 1500.0
        var t = 0L
        while (distance > 0) {
            val bucket = NotificationThrottle.bucket(distance.toInt())
            bucketsSeen.add(bucket)
            if (throttle.shouldNotify(Rendered(130, bucket), t)) posts++
            distance -= 36.1
            t += 1000L
        }
        assertEquals(bucketsSeen.size, posts)
        assertTrue("expected <= 25 posts, got $posts", posts <= 25)
    }

    @Test
    fun `clock regression recovers`() {
        val throttle = NotificationThrottle()
        throttle.shouldNotify(Rendered(88, null), 10_000L)
        assertTrue(throttle.shouldNotify(Rendered(88, null), 500L))
    }

    @Test
    fun `buckets floor and clamp`() {
        assertEquals(1000, NotificationThrottle.bucket(1249))
        assertEquals(1250, NotificationThrottle.bucket(1250))
        assertEquals(900, NotificationThrottle.bucket(999))
        assertEquals(300, NotificationThrottle.bucket(399))
        assertEquals(250, NotificationThrottle.bucket(299))
        assertEquals(50, NotificationThrottle.bucket(49))
        assertEquals(50, NotificationThrottle.bucket(0))
    }
}
