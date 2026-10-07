package com.raptibooks

import android.app.Application
import com.raptibooks.core.data.AppContainer
import com.raptibooks.core.data.AppContainerProvider
import com.raptibooks.core.data.conversion.ConversionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RaptiBooksApp : Application(), AppContainerProvider {

    override val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ConversionWorker.ensureChannel(this)
        // Conversions interrupted by the process being killed continue where they stopped.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.library.resumeInterrupted() }
        }
    }
}
