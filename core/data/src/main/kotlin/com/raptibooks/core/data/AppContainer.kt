package com.raptibooks.core.data

import android.content.Context
import com.raptibooks.core.data.conversion.ConversionPipeline
import com.raptibooks.core.data.conversion.ConversionScheduler
import com.raptibooks.core.database.BookDao
import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.database.RaptiDatabase

/** Manual dependency injection root, created once by the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = RaptiDatabase(appContext)
    val bookDao = BookDao(database)
    val contentDao = ContentDao(database)
    val files = BookFiles(appContext)
    val settings = SettingsRepository(appContext)
    private val scheduler = ConversionScheduler(appContext)
    val pipeline = ConversionPipeline(appContext, bookDao, contentDao, files)
    val library = LibraryRepository(appContext, bookDao, contentDao, files, settings, scheduler)
    val reader = ReaderRepository(appContext, bookDao, contentDao, files)
}

/** Implemented by the Application so that workers and screens can reach the container. */
interface AppContainerProvider {
    val container: AppContainer
}

fun Context.appContainer(): AppContainer = (applicationContext as AppContainerProvider).container
