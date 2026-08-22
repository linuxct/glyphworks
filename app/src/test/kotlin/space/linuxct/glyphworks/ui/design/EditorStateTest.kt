package space.linuxct.glyphworks.ui.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

class EditorStateTest {
    private val home = PokemonCodename.BELLSPROUT

    private fun design(kind: DesignKind = DesignKind.DYNAMIC, frames: Int = 1): Design = Design(
        id = "0123456789abcdef0123456789abcdef",
        name = "Test",
        kind = kind,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            home.codename to DesignVariant(
                frames = List(frames) { DesignFrame(durationMs = 100 + it, cells = DesignFrames.blank(home)) },
            ),
            PokemonCodename.ARBOK.codename to DesignVariant(),
        ),
    )

    private fun state(kind: DesignKind = DesignKind.DYNAMIC, frames: Int = 1) =
        EditorState(design(kind, frames), home)

    private fun EditorState.stroke(x: Int, y: Int) {
        beginStroke()
        paint(x, y)
        endStroke()
    }

    private fun EditorState.cells(index: Int): IntArray = frames[index].frame.copyOfCells()

    @Test
    fun everyStoredFrameIsLoadedWithItsOwnTiming() {
        val state = state(frames = 4)
        assertEquals(4, state.frames.size)
        assertEquals(listOf(100, 101, 102, 103), state.frames.map { it.durationMs })
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun anEmptyVariantOpensOnOneBlankFrame() {
        val state = state()
        assertTrue(state.switchTo(PokemonCodename.ARBOK))
        assertEquals(1, state.frames.size)
        assertEquals(PokemonCodename.ARBOK.size, state.selected.frame.size)
        assertTrue(state.cells(0).all { it == 0 })
    }

    @Test
    fun addingAFrameInsertsAfterTheSelectedOneAndSelectsIt() {
        val state = state(frames = 3)
        state.select(1)
        assertTrue(state.addFrame())
        assertEquals(4, state.frames.size)
        assertEquals(2, state.selectedIndex)
        assertTrue("a new frame starts blank", state.cells(2).all { it == 0 })
        assertEquals(101, state.frames[2].durationMs)
    }

    @Test
    fun duplicatingCopiesThePixelsIntoANewFrame() {
        val state = state(frames = 2)
        state.select(0)
        state.stroke(6, 6)
        state.setSelectedDuration(250)

        assertTrue(state.duplicateFrame())
        assertEquals(3, state.frames.size)
        assertEquals(1, state.selectedIndex)
        assertArrayEquals("the copy is not a copy", state.cells(0), state.cells(1))
        assertEquals(250, state.frames[1].durationMs)

        assertTrue(state.frames[0].id != state.frames[1].id)
        state.stroke(2, 2)
        assertEquals(0, state.cells(0)[2 * home.size + 2])
        assertEquals(DEFAULT_LEVELS.last(), state.cells(1)[2 * home.size + 2])
    }

    @Test
    fun theLastFrameCannotBeDeleted() {
        val state = state(frames = 2)
        assertTrue(state.deleteFrame())
        assertEquals(1, state.frames.size)
        assertFalse(state.deleteFrame())
        assertEquals(1, state.frames.size)
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
    }

    @Test
    fun deletingMovesTheSelectionToWhateverTookItsPlace() {
        val state = state(frames = 4)
        state.select(1)
        state.deleteFrame()
        assertEquals(1, state.selectedIndex)
        assertEquals(102, state.selected.durationMs)
        state.select(2)
        state.deleteFrame()
        assertEquals(1, state.selectedIndex)
    }

    @Test
    fun theFormatsFrameCeilingIsRespected() {
        val state = state(frames = DesignCodec.MAX_FRAMES)
        assertTrue(state.atFrameLimit)
        assertFalse(state.addFrame())
        assertFalse(state.duplicateFrame())
        assertEquals(DesignCodec.MAX_FRAMES, state.frames.size)
    }

    @Test
    fun movingAFrameCarriesTheSelectionWithIt() {
        val state = state(frames = 5)
        state.select(3)
        val moved = state.selected.id
        assertTrue(state.moveFrame(3, 0))
        assertEquals(0, state.selectedIndex)
        assertEquals(moved, state.frames[0].id)
        assertEquals(moved, state.selected.id)
        assertEquals(listOf(103, 100, 101, 102, 104), state.frames.map { it.durationMs })
    }

    @Test
    fun undoHistoryBelongsToTheFrameItWasMadeOn() {
        val state = state(frames = 2)
        state.select(0)
        state.stroke(6, 6)
        assertTrue(state.canUndo)

        state.select(1)
        assertFalse("frame 1 inherited frame 0's history", state.canUndo)
        assertFalse("undo would have edited a frame nobody is looking at", state.undo())
        assertTrue("frame 0 was altered from frame 1", state.cells(1).all { it == 0 })
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])

        state.select(0)
        assertTrue(state.canUndo)
        assertTrue(state.undo())
        assertTrue(state.cells(0).all { it == 0 })
        assertTrue(state.canRedo)
        assertTrue(state.redo())
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
    }

    @Test
    fun clearAndFillActOnTheSelectedFrameOnly() {
        val state = state(frames = 3)
        state.select(1)
        assertTrue(state.fillAll(state.brushValue()))
        assertTrue(state.cells(1).all { it == DEFAULT_LEVELS.last() })
        assertTrue(state.cells(0).all { it == 0 })
        assertTrue(state.cells(2).all { it == 0 })
        assertTrue(state.undo())
        assertTrue(state.cells(1).all { it == 0 })
    }

    @Test
    fun durationsCannotLeaveTheRangeTheCodecAccepts() {
        val state = state(frames = 2)
        state.setSelectedDuration(-1)
        assertEquals(DesignCodec.MIN_DURATION_MS, state.selected.durationMs)
        state.setSelectedDuration(Int.MAX_VALUE)
        assertEquals(DesignCodec.MAX_DURATION_MS, state.selected.durationMs)
        assertFalse(state.setSelectedDuration(DesignCodec.MAX_DURATION_MS))
        assertEquals(101, state.frames[1].durationMs)
    }

    @Test
    fun theTotalIsTheSumOfTheFrames() {
        val state = state(frames = 3)
        assertEquals(100 + 101 + 102, state.totalDurationMs)
        state.select(2)
        state.setSelectedDuration(1_000)
        assertEquals(100 + 101 + 1_000, state.totalDurationMs)
    }

    @Test
    fun loopAndKeyModeLandOnTheDesign() {
        val state = state()
        val before = state.design.loop
        assertTrue(state.setLoop(!before))
        assertEquals(!before, state.design.loop)
        assertFalse(state.setLoop(!before))
    }

    @Test
    fun onionSkinNeedsAPreviousFrameAndAnAnimation() {
        val single = state(frames = 1)
        assertFalse(single.canOnionSkin)
        single.onionSkin = true
        assertNull(single.onionCellsForDraw())

        val still = state(kind = DesignKind.STATIC, frames = 3)
        assertFalse("a still image has no previous frame", still.canOnionSkin)

        val animation = state(frames = 3)
        assertTrue(animation.canOnionSkin)
        animation.onionSkin = true
        assertNull(animation.onionCellsForDraw())
        animation.select(1)
        animation.stroke(5, 5)
        animation.select(2)
        assertEquals(DEFAULT_LEVELS.last(), animation.onionCellsForDraw()!![5 * home.size + 5])
        animation.select(0)
        assertNull(animation.onionCellsForDraw())
        animation.setLoop(true)
        assertArrayEquals(animation.cells(2), animation.onionCellsForDraw())
        animation.onionSkin = false
        assertNull(animation.onionCellsForDraw())
    }

    @Test
    fun eachVariantKeepsItsOwnTimeline() {
        val state = state(frames = 2)
        state.stroke(6, 6)
        state.addFrame()
        assertEquals(3, state.frames.size)

        assertTrue(state.switchTo(PokemonCodename.ARBOK))
        assertEquals("arbok inherited bellsprout's timeline", 1, state.frames.size)
        state.addFrame()
        state.addFrame()

        assertTrue(state.switchTo(home))
        assertEquals(3, state.frames.size)
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
        assertEquals(3, state.design.variantFor(PokemonCodename.ARBOK)?.frames?.size)
        assertEquals(
            PokemonCodename.ARBOK.cellCount,
            state.design.variantFor(PokemonCodename.ARBOK)?.frames?.first()?.cells?.length,
        )
    }

    private fun singleVariant(codename: PokemonCodename) = EditorState(
        Design(
            id = "0123456789abcdef0123456789abcdef",
            name = "One size",
            levels = DEFAULT_LEVELS,
            variants = mapOf(
                codename.codename to DesignVariant(
                    frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))),
                ),
            ),
        ),
        codename,
    )

    @Test
    fun aDesignWithBothVariantsGetsTheSwitcher() {
        val state = state()
        assertEquals(PokemonCodename.entries.toList(), state.variantsPresent)
        assertNull("nothing is missing", state.missingVariant)
    }

    @Test
    fun theEditorOpensOnAVariantTheDesignActuallyHas() {
        val phone3Only = singleVariant(PokemonCodename.ARBOK).design
        assertEquals(PokemonCodename.ARBOK, openingCodename(phone3Only, home = home))

        val phone4aOnly = singleVariant(home).design
        assertEquals(home, openingCodename(phone4aOnly, home = home))

        val both = state().design
        assertEquals(home, openingCodename(both, home = home))
        assertEquals(PokemonCodename.ARBOK, openingCodename(both, home = PokemonCodename.ARBOK))
    }

    private fun solidFrame(codename: PokemonCodename, index: Int, levels: List<Int>) = DesignFrame(
        durationMs = 90,
        cells = DesignFrames.encode(
            IntArray(codename.cellCount) { levels[index] },
            levels,
            codename.size,
        )!!,
    )

    @Test
    fun replacingTheDocumentPutsTheNewArtOnTheOpenCanvas() {
        val state = state()
        val before = state.design

        val applied = state.replaceDesign(
            before.copy(
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = listOf(solidFrame(home, 2, DEFAULT_LEVELS)),
                    )
                    ),
            ),
        )

        assertNotNull("the apply was refused", applied)
        assertEquals(1, state.frames.size)
        assertTrue(state.cells(0).all { it == DEFAULT_LEVELS[2] })
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun aVariantThatIsNotOpenIsWrittenStraightIntoTheDesign() {
        val state = state()
        val arbok = PokemonCodename.ARBOK
        val before = state.design

        state.replaceDesign(
            before.copy(
                variants = before.variants + (
                    arbok.codename to DesignVariant(
                        frames = listOf(solidFrame(arbok, 2, DEFAULT_LEVELS)),
                    )
                    ),
            ),
        )

        assertEquals(home, state.codename)
        assertEquals(1, state.design.variantFor(arbok)?.frames?.size)

        assertTrue(state.switchTo(arbok))
        assertTrue(state.cells(0).all { it == DEFAULT_LEVELS[2] })
    }

    @Test
    fun aStaticDesignCanBecomeAnAnimationAndBack() {
        val state = state(kind = DesignKind.STATIC, frames = 1)
        val before = state.composed()

        state.replaceDesign(
            before.copy(
                kind = DesignKind.DYNAMIC,
                loop = true,
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = List(3) { solidFrame(home, it % 3, DEFAULT_LEVELS) },
                    )
                    ),
            ),
        )

        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertTrue(state.design.loop)
        assertEquals(3, state.frames.size)
        assertTrue(state.canOnionSkin)

        state.replaceDesign(before)
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(1, state.frames.size)
        assertFalse(state.canOnionSkin)
    }

    @Test
    fun aShorterPaletteRe_clampsTheBrush() {
        val state = state()
        state.brushIndex = 2
        val before = state.composed()

        val twoLevels = listOf(0, 4095)
        state.replaceDesign(
            before.copy(
                levels = twoLevels,
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = listOf(solidFrame(home, 1, twoLevels)),
                    )
                    ),
            ),
        )

        assertEquals(twoLevels, state.design.levels)
        assertEquals(listOf(0, 1), state.brushIndices)
        assertEquals(1, state.brushIndex)
        assertEquals(4095, state.brushValue())
    }

    @Test
    fun theDocumentCannotRenameOrRe_attributeTheDesign() {
        val state = state()
        val before = state.composed()

        state.replaceDesign(
            before.copy(
                id = "ffffffffffffffffffffffffffffffff",
                author = "Somebody else",
                createdAt = "1999-01-01T00:00:00Z",
                name = "A new name",
            ),
        )

        assertEquals(before.id, state.design.id)
        assertEquals(before.author, state.design.author)
        assertEquals(before.createdAt, state.design.createdAt)
        assertEquals("A new name", state.design.name)
    }

    @Test
    fun revertingRestoresTheWholeDocumentIncludingKindAndLevels() {
        val state = state(kind = DesignKind.STATIC, frames = 1)
        state.stroke(6, 6)
        val before = state.composed()

        val twoLevels = listOf(0, 4095)
        val previous = state.replaceDesign(
            before.copy(
                kind = DesignKind.DYNAMIC,
                levels = twoLevels,
                name = "Assistant's version",
                variants = mapOf(
                    home.codename to DesignVariant(
                        frames = List(4) { solidFrame(home, 1, twoLevels) },
                    ),
                    PokemonCodename.ARBOK.codename to DesignVariant(
                        frames = listOf(solidFrame(PokemonCodename.ARBOK, 1, twoLevels)),
                    ),
                ),
            ),
        )
        assertNotNull(previous)
        assertEquals(4, state.frames.size)

        state.replaceDesign(previous!!)

        val after = state.composed()
        assertEquals(before.copy(modifiedAt = ""), after.copy(modifiedAt = ""))
        assertEquals(1, state.frames.size)
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(DEFAULT_LEVELS, state.design.levels)
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
    }

    @Test
    fun aDocumentWithNoArtworkIsRefusedAndChangesNothing() {
        val state = state()
        state.stroke(2, 2)
        val before = state.composed()

        assertNull(state.replaceDesign(Design(id = before.id)))

        assertEquals(before.copy(modifiedAt = ""), state.composed().copy(modifiedAt = ""))
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[2 * home.size + 2])
    }
}
