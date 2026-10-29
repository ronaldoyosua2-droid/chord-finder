package com.ronald.chordfinder.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroTest {
    @Test fun countInThenAccent() {
        val tl = Timeline(MetroConfig(bpm = 60))
        assertEquals(listOf(1, 2, 3, 4), tl.eventsForBar(-1).filterIsInstance<Ev.Count>().map { it.n })
        val first = tl.eventsForBar(0).filterIsInstance<Ev.Click>().first()
        assertEquals(4.0, first.t, 1e-9)
        assertTrue(first.accent)
    }

    @Test fun cueOneBarBefore() {
        val seq = listOf(SeqItem("Verse", 2, "verse"), SeqItem("Reff", 2, "chorus"))
        val tl = Timeline(MetroConfig(bpm = 60, sequence = seq, useSequence = true))
        assertEquals(listOf("chorus"), tl.eventsForBar(1).filterIsInstance<Ev.Cue>().map { it.word })
        assertTrue(tl.eventsForBar(4).any { it is Ev.End })
    }

    @Test fun tempoFromText() {
        assertEquals(72, TempoText.bpm("Tempo : 72"))
        assertEquals(6 to 8, TempoText.signature("Time: 6/8"))
    }

    @Test fun autoSequence() {
        val song = "Intro :\nC G Am F\nVerse 1 :\nC G\nx\nReff 2x :\nF G Em Am\nx\nModulasi ke D\nReff :\nG A F#m Bm\nx"
        val sq = SequenceBuilder.auto(SongParser.parse(song))
        assertEquals(listOf("intro", "verse", "chorus", "up"), sq.map { it.cue })
        assertEquals(8, sq[2].bars)
    }

    @Test fun detectsTempo() {
        for (bpm in listOf(72, 128)) {
            val sr = 22050
            val a = FloatArray(sr * 12)
            var t = 0.0
            var k = 0
            while (t < 11.8) {
                val st = (t * sr).toInt()
                val amp = if (k % 4 == 0) 0.9 else 0.5
                for (j in 0 until 1500) if (st + j < a.size) a[st + j] += (amp * Math.exp(-j / 200.0) * Math.sin(j * 0.3)).toFloat()
                t += 60.0 / bpm; k++
            }
            val est = BeatDetector.estimate(a, sr)!!
            assertTrue("bpm $bpm -> $est", Math.abs(est - bpm) <= 2)
        }
    }

    @Test fun bridgeNote() {
        val lines = SongParser.parse("Verse :\nF      G\nLirik")
        val r = Renderer.render(lines, 0, 0, NumberMode.INLINE, emptyMap(), mapOf(2 to listOf(Note(true, "G/B C#dim"))))
        assertTrue(r.any { it.text == "✎ G/B(5/7) → C#dim(#1°)" })
    }
}
