package com.autobox.navigation.carapp.cluster

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Shows [ClusterMapContent] on a surface: a private virtual display backed by the surface,
 * with a [Presentation] holding the Compose map. This is how TomTom's AAOS example renders
 * into car app surfaces (ComposePresentationManager), here driven by the cluster app.
 *
 * Each instance draws into one surface; [dismiss] releases everything.
 */
internal class ClusterMapRenderer(private val context: Context) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /**
     * Puts the map on [surface]; [onMapReady] follows once the map itself is up, which is when
     * the cluster may drop its placeholder. On a setup failure everything allocated so far is
     * released again and the reason is returned; null means setup succeeded.
     */
    fun show(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        insets: Rect,
        onMapReady: () -> Unit,
    ): String? = try {
        createPresentation(surface, width, height, densityDpi, insets, onMapReady)
        null
    } catch (e: RuntimeException) {
        // VirtualDisplay and Presentation report a dead surface or a lost window token as
        // various RuntimeExceptions (BadTokenException, IllegalStateException, ...)
        Log.e(TAG, "Failed to show the cluster map", e)
        dismiss()
        e.toString()
    }

    private fun createPresentation(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        insets: Rect,
        onMapReady: () -> Unit,
    ) {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: throw IllegalStateException("No virtual display for the cluster surface")
        virtualDisplay = display

        val pxPerDp = densityDpi / BASELINE_DPI
        val safeArea = PaddingValues(
            start = (insets.left / pxPerDp).dp,
            top = (insets.top / pxPerDp).dp,
            end = (insets.right / pxPerDp).dp,
            bottom = (insets.bottom / pxPerDp).dp,
        )

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ClusterMapRenderer)
            setViewTreeSavedStateRegistryOwner(this@ClusterMapRenderer)
            setViewTreeViewModelStoreOwner(this@ClusterMapRenderer)
            setContent { ClusterMapContent(safeArea = safeArea, onMapReady = onMapReady) }
        }

        presentation = Presentation(context, display.display).apply {
            setContentView(composeView)
            show()
        }
    }

    fun dismiss() {
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        runCatching { presentation?.dismiss() }
        virtualDisplay?.release()
        presentation = null
        virtualDisplay = null
        store.clear()
    }

    private companion object {
        const val TAG = "ClusterMapRenderer"
        const val VIRTUAL_DISPLAY_NAME = "AutoBoxClusterMap"
        const val BASELINE_DPI = 160f
    }
}
