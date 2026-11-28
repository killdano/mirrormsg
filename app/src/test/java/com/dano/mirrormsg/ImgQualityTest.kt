package com.dano.mirrormsg

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the eight old Image quality settings carry over to the one Pictures stop. */
class ImgQualityTest {
    // The old settings, at their old defaults unless a test changes one.
    private fun old(
        thumbCap: Int = 16, fullCap: Int = 16, thumbCoverage: Int = 72, fullCoverage: Int = 94,
        compress: Boolean = true, dither: Boolean = true, ditherCompress: Int = 0,
        ditherIntensity: Int = 100,
    ) = Mirror.imgQualityFromOld(thumbCap, fullCap, thumbCoverage, fullCoverage,
        compress, dither, ditherCompress, ditherIntensity)

    @Test fun oldDefaultsAreBalanced() {
        assertEquals(Mirror.IMG_BALANCED, old())
        assertEquals(Mirror.IMG_BALANCED, old(compress = false))
    }

    @Test fun smallerAndPlainerIsFaster() {
        assertEquals(Mirror.IMG_FASTER, old(dither = false))
        assertEquals(Mirror.IMG_FASTER, old(ditherIntensity = 49))
        assertEquals(Mirror.IMG_FASTER, old(ditherCompress = 30))
        assertEquals(Mirror.IMG_FASTER, old(thumbCap = 4))
        assertEquals(Mirror.IMG_FASTER, old(thumbCap = 2))
        assertEquals(Mirror.IMG_FASTER, old(fullCap = 4))
        // Plainer wins over more colours.
        assertEquals(Mirror.IMG_FASTER, old(dither = false, thumbCoverage = 100))
    }

    @Test fun notFasterAtTheEdges() {
        assertEquals(Mirror.IMG_BALANCED, old(ditherIntensity = 50))
        // "Keep images compressible" did nothing with compression off.
        assertEquals(Mirror.IMG_BALANCED, old(compress = false, ditherCompress = 60))
    }

    @Test fun moreColoursIsSharper() {
        assertEquals(Mirror.IMG_SHARPER, old(thumbCoverage = 73))
        assertEquals(Mirror.IMG_SHARPER, old(fullCoverage = 100))
    }

    @Test fun fewerColoursSlidersStayBalanced() {
        assertEquals(Mirror.IMG_BALANCED, old(thumbCoverage = 40))
        assertEquals(Mirror.IMG_BALANCED, old(fullCoverage = 60))
    }
}
