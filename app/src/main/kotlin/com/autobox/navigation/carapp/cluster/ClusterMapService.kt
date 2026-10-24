package com.autobox.navigation.carapp.cluster

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.autobox.navigation.cluster.IClusterMap
import com.autobox.navigation.cluster.IClusterMapListener
import com.autobox.navigation.common.SdkInitializer
import com.autobox.navigation.location.VehicleLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draws the instrument cluster map into a surface handed over by the cluster app.
 *
 * Rendering stays in this process, next to [com.tomtom.sdk.navigation.TomTomNavigation]: the
 * cluster map shows the live route, the instruction arrows and the same car position as the
 * main display without copying any of it across processes.
 */
class ClusterMapService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var renderer: ClusterMapRenderer? = null
    private var pendingAttach: Job? = null

    private val binder = object : IClusterMap.Stub() {
        override fun attachSurface(
            surface: Surface,
            width: Int,
            height: Int,
            densityDpi: Int,
            insets: Rect,
            listener: IClusterMapListener,
        ) {
            mainHandler.post { attach(surface, width, height, densityDpi, insets, listener) }
        }

        override fun detachSurface() {
            mainHandler.post { detach() }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The cluster app went away (or crashed): its surface is gone with it.
        detach()
        return false
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        insets: Rect,
        listener: IClusterMapListener,
    ) {
        detach()
        if (!surface.isValid || width <= 0 || height <= 0) {
            listener.report { onMapFailed("Invalid surface ${width}x$height") }
            return
        }
        pendingAttach = lifecycleScope.launch {
            SdkInitializer.initialize(this@ClusterMapService)
            if (!SdkInitializer.initialized.value) {
                listener.report { onMapFailed("TomTom SDK: ${SdkInitializer.error.value}") }
                return@launch
            }
            VehicleLocation.enableIfPermitted(this@ClusterMapService)
            val newRenderer = ClusterMapRenderer(this@ClusterMapService)
            val mapReady = CompletableDeferred<Unit>()
            val failure = newRenderer.show(surface, width, height, densityDpi, insets) {
                mapReady.complete(Unit)
            }
            if (failure != null) {
                listener.report { onMapFailed(failure) }
                return@launch
            }
            renderer = newRenderer
            // Report success only once the map is up: until then the cluster keeps its
            // placeholder rather than showing an empty surface.
            if (withTimeoutOrNull(MAP_READY_TIMEOUT_MS) { mapReady.await() } != null) {
                Log.d(TAG, "Cluster map attached: ${width}x$height@$densityDpi, insets $insets")
                listener.report { onMapShown() }
            } else {
                // Not detach(): that would cancel this very coroutine
                renderer?.dismiss()
                renderer = null
                pendingAttach = null
                listener.report { onMapFailed("The map did not come up in $MAP_READY_TIMEOUT_MS ms") }
            }
        }
    }

    // The cluster may be gone by now; that is what onUnbind() is for.
    private fun IClusterMapListener.report(call: IClusterMapListener.() -> Unit) {
        try {
            call()
        } catch (e: RemoteException) {
            Log.w(TAG, "Cluster did not take the map status", e)
        }
    }

    private fun detach() {
        pendingAttach?.cancel()
        pendingAttach = null
        renderer?.dismiss()
        renderer = null
    }

    private companion object {
        const val TAG = "ClusterMapService"
        const val MAP_READY_TIMEOUT_MS = 15_000L
    }
}
