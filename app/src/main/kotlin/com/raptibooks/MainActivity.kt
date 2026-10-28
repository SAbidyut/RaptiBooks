package com.raptibooks

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raptibooks.core.ui.RaptiTheme
import com.raptibooks.feature.library.LibraryRoute
import com.raptibooks.feature.reader.OriginalPdfRoute
import com.raptibooks.feature.reader.ReaderRoute

class MainActivity : ComponentActivity() {

    /** A PDF opened with "Open with" / "Share", waiting to be imported by the library screen. */
    private var pendingImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingImport = pdfFrom(intent)
        setContent {
            RaptiTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryRoute(
                            onOpenBook = { id -> nav.navigate("reader/$id") },
                            onOpenOriginal = { id -> nav.navigate("original/$id/0?fromReader=false") },
                            pendingImport = pendingImport,
                            onPendingImportConsumed = { pendingImport = null },
                        )
                    }
                    composable(
                        "reader/{bookId}",
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                    ) { entry ->
                        val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                        val handle = entry.savedStateHandle
                        val jump by handle.getStateFlow<Int?>(JUMP_TO_PAGE, null).collectAsStateWithLifecycle()
                        val passage by handle.getStateFlow<String?>(JUMP_PASSAGE, null).collectAsStateWithLifecycle()
                        ReaderRoute(
                            bookId = bookId,
                            jumpToPage = jump,
                            jumpPassage = passage,
                            onJumpConsumed = {
                                handle[JUMP_TO_PAGE] = null
                                handle[JUMP_PASSAGE] = null
                            },
                            onBack = { nav.popBackStack() },
                            onOpenOriginal = { page, held ->
                                val q = held?.let { "&passage=" + Uri.encode(it) } ?: ""
                                nav.navigate("original/$bookId/$page?fromReader=true$q")
                            },
                        )
                    }
                    composable(
                        "original/{bookId}/{page}?fromReader={fromReader}&passage={passage}",
                        arguments = listOf(
                            navArgument("bookId") { type = NavType.LongType },
                            navArgument("page") { type = NavType.IntType },
                            navArgument("fromReader") { type = NavType.BoolType; defaultValue = false },
                            navArgument("passage") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        val args = entry.arguments ?: return@composable
                        val fromReader = args.getBoolean("fromReader")
                        OriginalPdfRoute(
                            bookId = args.getLong("bookId"),
                            initialPage = args.getInt("page"),
                            onBack = { nav.popBackStack() },
                            onReflow = if (fromReader) { page, line ->
                                nav.previousBackStackEntry?.savedStateHandle?.let {
                                    // (the passage first: the reader moves when the page is set)
                                    it[JUMP_PASSAGE] = line
                                    it[JUMP_TO_PAGE] = page
                                }
                                nav.popBackStack()
                            } else null,
                            passage = args.getString("passage"),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pdfFrom(intent)?.let { pendingImport = it }
    }

    private fun pdfFrom(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    private companion object {
        const val JUMP_TO_PAGE = "jumpToPage"
        const val JUMP_PASSAGE = "jumpPassage"
    }
}
