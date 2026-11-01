package com.ronald.chordfinder.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTextTest {
    @Test fun parsesTag() {
        assertEquals(10, ReleaseText.buildOf("v1.0.10"))
        assertNull(ReleaseText.buildOf("build-3"))
    }

    @Test fun extractsWhatsNew() {
        val body = "Intro paragraph\n\n## ✨ What's new · v1.0.10\n\n- 🎹 **Play in key:** moves chords\n- 🔄 updates\n\n---\nOutro"
        val w = ReleaseText.whatsNew(body)
        assertEquals("- 🎹 **Play in key:** moves chords\n- 🔄 updates", w)
        assertEquals("• 🎹 Play in key: moves chords\n• 🔄 updates", ReleaseText.plain(w))
    }
}
