package com.raptibooks.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The status and navigation bar icons drawn to be read on this screen: light on a dark
 * background ([darkBackground]), dark on a light one. Set whenever the screen comes to the
 * front (each screen sets its own, so going back never leaves the last screen's icons), and
 * when its background changes.
 */
@Composable
fun SystemBarIcons(darkBackground: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark by rememberUpdatedState(darkBackground)
    val window = view.context.findActivity()?.window ?: return
    fun apply() {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    DisposableEffect(lifecycle, darkBackground) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) apply()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
