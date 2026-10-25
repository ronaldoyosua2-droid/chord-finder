package com.ronald.chordfinder.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TheoryTest {
    @Test fun numbersInKeyA() {
        assertEquals("2", Chord.parse("Bm")!!.number(9))
        assertEquals("5", Chord.parse("E")!!.number(9))
        assertEquals("1/3", Chord.parse("A/C#")!!.number(9))
        assertEquals("b2", Chord.parse("Bb")!!.number(9))
    }

    @Test fun wordsAreNotChords() {
        assertNull(Chord.parse("Ada"))
        assertNull(Chord.parse("Bagai"))
        assertNull(SongParser.chordItems("Ku tahu Kau Tuhan"))
        assertNull(SongParser.chordItems("A love that"))
    }

    @Test fun inlineRendering() {
        val lines = SongParser.parse("A          Bm        E\nLirik")
        val r = Renderer.render(lines, 9, 0, NumberMode.INLINE)
        assertEquals("A(1)       Bm(2)     E(5)", r[0].text)
    }

    @Test fun keyDetection() {
        assertEquals(0, KeyDetector.explicit("Key: Am"))
        assertEquals(10, KeyDetector.explicit("Kunci: Bb"))
        assertEquals(9, KeyDetector.explicit("Do = A"))
        assertNull(KeyDetector.explicit("Ku do A rumah"))
        val c = "C Em Am\nlirik\nF Em G\nlirik\nF G C"
        assertEquals(0, KeyDetector.detect(c, SongParser.parse(c))!!.keyPc)
    }

    @Test fun transposeKeepsNumbers() {
        val lines = SongParser.parse("A  Bm  E")
        val r = Renderer.render(lines, 9, 1, NumberMode.INLINE)
        assertEquals("Bb(1) Cm(2) F(5)", r[0].text)
    }
}

class CleanupTest {
    @Test fun dropsSiteKeyPicker() {
        val raw = (listOf("A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#") +
            listOf("Intro :", "C  Am  F  C", "Lirik")).joinToString("\n")
        val lines = SongParser.parse(raw)
        val chords = SongParser.allChords(lines)
        assertEquals(4, chords.size)
    }

    @Test fun hidesKeyLine() {
        val lines = SongParser.parse("Do = A\n\nA  Bm\nLirik")
        assertEquals(false, lines.any { it is Line.Lyric && it.text.startsWith("Do") })
        assertEquals(9, KeyDetector.detect("Do = A\n\nA  Bm\nLirik", lines)!!.keyPc)
    }
}
