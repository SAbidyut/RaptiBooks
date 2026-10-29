package com.raptibooks

import androidx.activity.compose.setContent
import com.raptibooks.core.model.ReaderTheme
import com.raptibooks.core.ui.RaptiTheme
import androidx.core.view.WindowCompat
import com.raptibooks.feature.library.LibraryRoute
import com.raptibooks.feature.reader.OriginalPdfRoute
import com.raptibooks.feature.reader.ReaderRoute
import com.raptibooks.feature.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Boots the real activity and screens; any crash in composition/layout fails the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiSmokeTest {

    @Test
    fun libraryAndReaderRender() {
        val bookId = TestSupport.seedLibrary()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        TestSupport.pump()
        val activity = controller.get()

        for (theme in ReaderTheme.entries) {
            TestSupport.app.container.settings.update { it.copy(theme = theme, justify = theme != ReaderTheme.DARK) }
            activity.setContent {
                RaptiTheme { ReaderRoute(bookId, jumpToPage = null, onJumpConsumed = {}, onBack = {}, onOpenOriginal = { _, _ -> }) }
            }
            TestSupport.pump()
        }
        // The original pages, full screen, upright and turned for reading across.
        TestSupport.resourcePdf("math_textbook.pdf", TestSupport.app.container.files.source(bookId))
        for (landscape in listOf(false, true)) {
            activity.setContent {
                RaptiTheme { OriginalPdfRoute(bookId, initialPage = 0, onBack = {}, onReflow = { _, _ -> }, initialLandscape = landscape) }
            }
            TestSupport.pump()
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun passageHeldInOneViewIsFoundInTheOther() {
        val bookId = TestSupport.seedLibrary()
        val c = TestSupport.app.container
        val vm = ReaderViewModel(bookId, c.reader, c.settings)
        TestSupport.pumpUntil { vm.load is ReaderViewModel.Load.Ready }
        // a printed line, back in the reflowed text
        val found = runBlocking { vm.findPassage(2, "A quotation that stands") }
        assertTrue(found != null)
        val block = runBlocking { c.reader.blocks(bookId, found!!, found + 1) }.single()
        assertTrue(block.plainText, block.plainText.startsWith("A quotation"))
        assertEquals(null, runBlocking { vm.findPassage(2, "nothing of the kind here") })
        // the printed lines of an original page, placed on it
        TestSupport.resourcePdf("math_textbook.pdf", c.files.source(bookId))
        val lines = runBlocking { c.reader.openOriginal(bookId).use { it.lines(0) } }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.left in 0f..1f && it.top in 0f..1f && it.bottom > it.top })
    }

    @Test
    fun barIconsFollowTheScreenShown() {
        val bookId = TestSupport.seedLibrary()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        TestSupport.pump()
        val activity = controller.get()
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        // a dark reading theme: light icons
        TestSupport.app.container.settings.update { it.copy(theme = ReaderTheme.DARK) }
        activity.setContent { RaptiTheme { ReaderRoute(bookId, jumpToPage = null, onJumpConsumed = {}, onBack = {}, onOpenOriginal = { _, _ -> }) } }
        TestSupport.pumpUntil { !bars.isAppearanceLightStatusBars }
        assertFalse(bars.isAppearanceLightStatusBars)
        // back in the (light) library: its own dark icons, not the reader's
        activity.setContent { RaptiTheme(darkTheme = false) { LibraryRoute(onOpenBook = {}, onOpenOriginal = {}, pendingImport = null, onPendingImportConsumed = {}) } }
        TestSupport.pump()
        assertTrue(bars.isAppearanceLightStatusBars)
        controller.pause().stop().destroy()
    }

    @Test
    fun readerViewModelSearchAndBookmarks() {
        val bookId = TestSupport.seedLibrary()
        val c = TestSupport.app.container
        val vm = ReaderViewModel(bookId, c.reader, c.settings)
        TestSupport.pumpUntil { vm.load is ReaderViewModel.Load.Ready }
        val ready = vm.load as ReaderViewModel.Load.Ready
        assertEquals(21, ready.source.count)
        assertEquals(2, ready.toc.size)
        // The worked-example box covers blocks 4..6 and nothing else.
        assertEquals(4, ready.boxes.find(5)?.startIndex)
        assertEquals(null, ready.boxes.find(7))
        // A printed contents line leads to its chapter.
        assertEquals(9, runBlocking { vm.contentsTarget("1.1 Lists and tables", 2, ready.toc) })

        vm.search("বাংলা")
        TestSupport.pumpUntil { vm.results != null }
        assertTrue(vm.results!!.isNotEmpty())

        assertEquals(true, vm.toggleBookmark(4, 6))
        TestSupport.pump()
        runBlocking { assertEquals(4, c.reader.bookmarks(bookId).first().single().blockIndex) }
    }
}
