package com.raptibooks

import androidx.activity.compose.setContent
import com.raptibooks.core.model.ReaderTheme
import com.raptibooks.core.ui.RaptiTheme
import com.raptibooks.feature.reader.OriginalPdfRoute
import com.raptibooks.feature.reader.ReaderRoute
import com.raptibooks.feature.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
                RaptiTheme { ReaderRoute(bookId, jumpToPage = null, onJumpConsumed = {}, onBack = {}, onOpenOriginal = {}) }
            }
            TestSupport.pump()
        }
        // The original pages, full screen, upright and turned for reading across.
        TestSupport.resourcePdf("math_textbook.pdf", TestSupport.app.container.files.source(bookId))
        for (landscape in listOf(false, true)) {
            activity.setContent {
                RaptiTheme { OriginalPdfRoute(bookId, initialPage = 0, onBack = {}, onReflow = {}, initialLandscape = landscape) }
            }
            TestSupport.pump()
        }
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
