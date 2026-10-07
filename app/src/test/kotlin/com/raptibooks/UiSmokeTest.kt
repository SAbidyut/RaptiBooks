package com.raptibooks

import androidx.activity.compose.setContent
import com.raptibooks.core.model.ReaderTheme
import com.raptibooks.core.ui.RaptiTheme
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
        controller.pause().stop().destroy()
    }

    @Test
    fun readerViewModelSearchAndBookmarks() {
        val bookId = TestSupport.seedLibrary()
        val c = TestSupport.app.container
        val vm = ReaderViewModel(bookId, c.reader, c.settings)
        TestSupport.pumpUntil { vm.load is ReaderViewModel.Load.Ready }
        val ready = vm.load as ReaderViewModel.Load.Ready
        assertEquals(12, ready.source.count)
        assertEquals(2, ready.toc.size)

        vm.search("বাংলা")
        TestSupport.pumpUntil { vm.results != null }
        assertTrue(vm.results!!.isNotEmpty())

        assertEquals(true, vm.toggleBookmark(4, 6))
        TestSupport.pump()
        runBlocking { assertEquals(4, c.reader.bookmarks(bookId).first().single().blockIndex) }
    }
}
