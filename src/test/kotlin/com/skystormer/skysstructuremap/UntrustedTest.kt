package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Structures from other players' chat lines are checked before they are kept. */
class UntrustedTest {

    @Test
    fun anOrdinaryLineIsRead() {
        assertNotNull(StructureShare.readLine("<Steve> Desert Temple: 10 74 20 (Overworld) · box 0 49 10 to 20 73 27"))
    }

    @Test
    fun boxesNoStructureHasAreIgnored() {
        // Bigger than any structure.
        assertNull(StructureShare.readLine("<Steve> Desert Temple: 0 64 0 (Overworld) · box -1000000 0 0 to 1000000 10 10"))
        // Numbers too big to be coordinates.
        assertNull(StructureShare.readLine("<Steve> Desert Temple: 0 64 0 (Overworld) · box 0 0 0 to 99999999999 10 10"))
        // Inside out.
        assertNull(StructureShare.readLine("<Steve> Desert Temple: 0 64 0 (Overworld) · box 10 0 0 to 0 10 10"))
    }

    @Test
    fun aDimensionThatIsNotAnIdIsIgnored() {
        assertNull(StructureShare.readLine("<Steve> Desert Temple: 0 64 0 (no such place!) · box 0 0 0 to 10 10 10"))
    }

    @Test
    fun aVeryLongLineIsTurnedDownQuickly() {
        assertNull(StructureShare.readLine("<Steve> " + "Desert Temple: ".repeat(5000) + "0 64 0 (Overworld) · box 0 0 0 to 10 10 10"))
    }
}
