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
                        ReaderRoute(
                            bookId = bookId,
                            jumpToPage = jump,
                            onJumpConsumed = { handle[JUMP_TO_PAGE] = null },
                            onBack = { nav.popBackStack() },
                            onOpenOriginal = { page -> nav.navigate("original/$bookId/$page?fromReader=true") },
                        )
                    }
                    composable(
                        "original/{bookId}/{page}?fromReader={fromReader}",
                        arguments = listOf(
                            navArgument("bookId") { type = NavType.LongType },
                            navArgument("page") { type = NavType.IntType },
                            navArgument("fromReader") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val args = entry.arguments ?: return@composable
                        val fromReader = args.getBoolean("fromReader")
                        OriginalPdfRoute(
                            bookId = args.getLong("bookId"),
                            initialPage = args.getInt("page"),
                            onBack = { nav.popBackStack() },
                            onReflow = if (fromReader) { page ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(JUMP_TO_PAGE, page)
                                nav.popBackStack()
                            } else null,
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
    }
}
