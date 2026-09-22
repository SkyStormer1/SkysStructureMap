package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareTest {

    private val temple = Structure("id", StructureType.DESERT_TEMPLE, OVERWORLD, Box(-5424, 49, 832, -5404, 73, 849), 0L)

    @Test
    fun lineIsPlainAndCarriesTypeDimensionAndBox() {
        val line = StructureShare.line(temple.name, temple.dimension, temple.box, 74)
        assertTrue(line.startsWith("Desert Temple: -5414 "), line)
        assertTrue(line.endsWith("(Overworld) · box -5424 49 832 to -5404 73 849"), line)
        assertTrue(line.length <= StructureShare.MAX_CHAT)
        val (said, shared) = StructureShare.readLine("<Sky_Stormer> $line")!!
        assertEquals(StructureType.DESERT_TEMPLE, shared.type)
        assertEquals(OVERWORLD, shared.dimension)
        assertEquals(temple.box, shared.box)
        assertTrue(said.startsWith("<Sky_Stormer> Desert Temple: -5414"), said)
    }

    @Test
    fun whisperedAndOtherDimensionsAreRead() {
        val (_, shared) = StructureShare.readLine("Sky_Stormer whispers to you: End City: 10 80 -20 (End) · box 0 60 -30 to 20 100 -10")!!
        assertEquals(StructureType.END_CITY, shared.type)
        assertEquals(END, shared.dimension)
    }

    @Test
    fun codeForTheButtonCarriesItAll() {
        val shared = StructureShare.Shared(StructureType.DESERT_TEMPLE, OVERWORLD, temple.box, "desert_pyramid")
        val back = StructureShare.decode(StructureShare.encode(shared))!!
        assertEquals(temple.box, back.box)
        assertEquals("desert_pyramid", back.variant)
    }

    @Test
    fun oldCodedLinesAreStillFound() {
        val code = StructureShare.encode(StructureShare.Shared(StructureType.DESERT_TEMPLE, OVERWORLD, temple.box, null))
        assertEquals(code, StructureShare.codeIn("<Sky_Stormer> Desert Temple: -5414 74 840 (Overworld) · SSM1:$code"))
    }

    @Test
    fun ordinaryChatIsLeftAlone() {
        assertNull(StructureShare.readLine("<Sky_Stormer> Desert Temple: -5414 74 840 (Overworld)"))
        assertNull(StructureShare.readLine("<Sky_Stormer> meet me at 10 64 20 (Overworld) · box 1 2 3 to 4 5 6"))
        assertNull(StructureShare.codeIn("<Sky_Stormer> Desert Temple: -5414 74 840 (Overworld)"))
        assertNull(StructureShare.decode("not a code"))
    }
}
