package com.ronald.chordfinder

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeTest {
    @Test fun parsesLinks() {
        assertEquals("dQw4w9WgXcQ", YouTube.parseId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals("dQw4w9WgXcQ", YouTube.parseId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTube.parseId("https://youtube.com/shorts/dQw4w9WgXcQ"))
    }
}
