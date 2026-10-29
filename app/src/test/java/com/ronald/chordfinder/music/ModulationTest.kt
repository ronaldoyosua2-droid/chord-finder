package com.ronald.chordfinder.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityMarkTest {
    private fun num(c: String, k: Int) = Chord.parse(c)!!.number(k)

    @Test fun majorMinorMarks() {
        assertEquals("2", num("Dm", 0))
        assertEquals("2M", num("D", 0))
        assertEquals("3M", num("E7", 0))
        assertEquals("4m", num("Fm", 0))
        assertEquals("6M", num("A", 0))
        assertEquals("b7", num("Bb", 0))
        assertEquals("7", num("Bm7b5", 0))
        assertEquals("2", num("Dsus4", 0))
        assertEquals("2M/#4", num("D/F#", 0))
    }
}

class ModulationTest {
    private val song = listOf(
        "Do = C", "Reff :", "C      Am      F     G", "Lirik", "",
        "Modulasi ke D", "Reff :", "D      Bm      G     A", "Lirik", "D  Bm  G  A  D", "Lirik"
    ).joinToString("\n")

    @Test fun markerRenumbers() {
        val r = Renderer.render(SongParser.parse(song), 0, 0, NumberMode.INLINE)
        assertTrue(r.any { it.text.startsWith("D(1)") && it.text.contains("A(5)") })
        assertTrue(r.any { it.text.contains("Modulasi → Do = D") })
    }

    @Test fun parsesMarkers() {
        assertEquals(1, SongParser.modulation("Modulasi +1")!!.delta)
        assertEquals(1, SongParser.modulation("Modulasi naik ½")!!.delta)
        assertEquals(2, SongParser.modulation("naik 1 nada")!!.delta)
        assertNull(SongParser.modulation("Ku mau naik ke gunung Tuhan dan bersorak"))
    }

    @Test fun suggestsWithoutMarker() {
        val lines = SongParser.parse(song.replace("Modulasi ke D\n", "").replace("Reff :\nD", "Reff 2 :\nD"))
        val s = Modulation.suggest(lines, Modulation.keys(lines, 0), emptyMap(), emptySet())
        assertEquals(2, s!!.newKey)
    }

    @Test fun noFalseSuggestion() {
        val raw = "Do = C\nVerse :\nC  E7  Am  F\nx\nD  G  C\nx\nReff :\nF  G  Em  Am\nx\nDm  G  C\nx\nBridge :\nAm  F  C  G\nx\nE  Am  D  G\nx"
        val lines = SongParser.parse(raw)
        assertNull(Modulation.suggest(lines, Modulation.keys(lines, 0), emptyMap(), emptySet()))
    }
}
