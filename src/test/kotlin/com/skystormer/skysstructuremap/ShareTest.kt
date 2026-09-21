package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareTest {

    private val temple = Structure("id", StructureType.DESERT_TEMPLE, OVERWORLD, Box(-5424, 49, 832, -5404, 73, 849), 0L)

    @Test
    fun codeCarriesTypeDimensionAndBox() {
        val shared = StructureShare.decode(StructureShare.encode(Marker(temple, null)))!!
        assertEquals(StructureType.DESERT_TEMPLE, shared.type)
        assertEquals(OVERWORLD, shared.dimension)
        assertEquals(temple.box, shared.box)
    }

    @Test
    fun codeIsFoundInAChatLineAndFitsInOne() {
        val code = StructureShare.encode(Marker(temple, null))
        val line = "<Sky_Stormer> Desert Temple: -5414 74 840 (Overworld) · SSM1:$code"
        assertEquals(code, StructureShare.codeIn(line))
        assertTrue(line.length <= StructureShare.MAX_CHAT)
    }

    @Test
    fun ordinaryChatIsLeftAlone() {
        assertNull(StructureShare.codeIn("<Sky_Stormer> Desert Temple: -5414 74 840 (Overworld)"))
        assertNull(StructureShare.decode("not a code"))
    }
}
