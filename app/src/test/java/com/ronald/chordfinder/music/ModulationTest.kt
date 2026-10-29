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

class AugAndKeyLabelTest {
    @Test fun augmented() {
        assertEquals("1+", Chord.parse("Aaug")!!.number(9))
        assertEquals("1+", Chord.parse("Auag")!!.number(9))
        assertEquals("Aaug", Chord.parse("Auag")!!.format(false))
        assertEquals("5+", Chord.parse("G+")!!.number(0))
        assertTrue(SongParser.chordItems("          A         Auag") != null)
    }

    @Test fun keyOnNextLine() {
        val raw = "[Chorus]\nKey:\n A\n---\n          A         Aaug\nLirik\n   D       E\nLirik"
        val lines = SongParser.parse(raw)
        assertEquals(9, KeyDetector.detect(raw, lines)!!.keyPc)
        assertTrue(lines.none { it is Line.Chords && it.items.size == 1 && (it.items[0] as? Item.ChordItem)?.chord?.root == 9 })
    }
}

class KeyLineTest {
    @Test fun keyLineIsNotChordLine() {
        val lines = SongParser.parse("Key: A\n   A    D\nLirik")
        assertTrue(lines.first() is Line.Chords)
        assertEquals(2, SongParser.allChords(lines).size)
    }
}

class MidSongKeyTest {
    private val raw = listOf(
        "[Verse]", "     Dm          F/C        Bm7-5  E", "Lirik", "   Am    F    G   C", "Lirik",
        "[Chorus]", "Key:", " A", "---", "          A         Auag", "Lirik",
        "        DM7          Dm/F", "Lirik", "    E        A", "Lirik"
    ).joinToString("\n")

    @Test fun midSongKeyIsModulation() {
        val lines = SongParser.parse(raw)
        val det = KeyDetector.detect(raw, lines)!!
        assertEquals(0, det.keyPc) // awal di C
        val r = Renderer.render(lines, det.keyPc, 0, NumberMode.INLINE)
        assertTrue(r.any { it.text.contains("Modulasi → Do = A") })
        assertTrue(r.any { it.text.contains("A(1)") && it.text.contains("Aaug(1+)") })
        assertTrue(r.any { it.text.contains("DM7(4)") })
        assertTrue(r.any { it.text.contains("E(3M)") })
    }
}

class DimTest {
    private fun num(c: String, k: Int) = Chord.parse(c)!!.number(k)
    @Test fun diminished() {
        assertEquals("7", num("Bdim", 0))
        assertEquals("7", num("Bm7b5", 0))
        assertEquals("#1°", num("C#dim", 0))
        assertEquals("#2°", num("D#dim7", 0))
        assertEquals("#4°", num("F#dim", 0))
        assertEquals("#5°", num("G#dim", 0))
        assertEquals("3°", num("Em7b5", 0))
        assertEquals("#1°", num("A#dim", 9))
    }
}
