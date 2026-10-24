/*
Copyright 2026 TomTom International BV.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.autobox.navigation.carapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.autobox.navigation.R
import com.autobox.navigation.common.SdkInitializer
import com.autobox.navigation.location.VehicleLocation
import com.autobox.navigation.map.MapSurfaceCallback
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.init.createRoutePlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AAOS Session managing navigation screen and ViewModels.
 *
 * @param sdkInitialized StateFlow indicating SDK initialization status
 * @param initializationError StateFlow containing SDK initialization error, null if no error
 */
class CarNavigationSession(
    private val sdkInitialized: StateFlow<Boolean>,
    private val initializationError: StateFlow<String?>,
) : Session(), SavedStateRegistryOwner, ViewModelStoreOwner, DefaultLifecycleObserver {
    private val appManager: AppManager by lazy { carContext.getCarService(AppManager::class.java) }
    private var surfaceCallbackRegistered = false
    private var mapSurfaceCallback: MapSurfaceCallback? = null
    private var isMainScreenBuilt = false
    private var surfaceRetryJob: Job? = null

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _viewModelStore = ViewModelStore()

    /**
     * View model for route planning and guidance.
     * Created on first access from [buildMainScreen], which [LoadingScreen] only invokes after
     * [sdkInitialized] is true — so the [TomTomSdk] accessors are safe to call here.
     */
    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    MainViewModel(
                        routePlanner = TomTomSdk.createRoutePlanner(),
                        navigation = TomTomSdk.navigation,
                        locationProvider = VehicleLocation.provider,
                        navigationManager = carContext.getCarService(NavigationManager::class.java),
                        waitingForLocationMessage = carContext.getString(R.string.template_title_waiting_for_location),
                    )
                }
            },
        )[MainViewModel::class.java]
    }

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = _viewModelStore

    init {
        savedStateRegistryController.performRestore(null)
        lifecycle.addObserver(this)
    }

    // The SDK is often up already (the cluster or the home screen card started it)
    override fun onCreateScreen(intent: Intent): Screen = if (sdkInitialized.value) {
        buildMainScreen()
    } else {
        loadingScreen()
    }

    private fun loadingScreen() = LoadingScreen(
        carContext = carContext,
        sdkInitialized = sdkInitialized,
        initializationError = initializationError,
        onRetry = { lifecycleScope.launch { SdkInitializer.initialize(carContext) } },
        buildMainScreen = ::buildMainScreen,
    )

    private fun buildMainScreen(): MainScreen {
        isMainScreenBuilt = true
        ensureSurfaceCallback(mainViewModel)
        return MainScreen(
            carContext = carContext,
            mainViewModel = mainViewModel,
        )
    }

    private fun ensureSurfaceCallback(viewModel: MainViewModel) {
        if (!surfaceCallbackRegistered) {
            try {
                mapSurfaceCallback = MapSurfaceCallback(
                    carContext = carContext,
                    lifecycleOwner = this,
                    savedStateRegistryOwner = this,
                    viewModelStoreOwner = this,
                    mainViewModel = viewModel,
                    isLocationPermissionGranted = {
                        ContextCompat.checkSelfPermission(
                            carContext,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                    },
                )
                appManager.setSurfaceCallback(mapSurfaceCallback)
                surfaceCallbackRegistered = true
                Log.d(TAG, "SurfaceCallback registered")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not register SurfaceCallback, host not yet bound: $e")
                scheduleSurfaceCallbackRetry()
            }
        }
    }

    // The host may bind a moment later; onStart() covers the next session start, this covers
    // a session that is already started (e.g. when the loading screen hands over).
    private fun scheduleSurfaceCallbackRetry() {
        if (surfaceRetryJob?.isActive == true) return
        surfaceRetryJob = lifecycleScope.launch {
            repeat(SURFACE_RETRY_ATTEMPTS) {
                delay(SURFACE_RETRY_DELAY_MS)
                if (surfaceCallbackRegistered) return@launch
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    ensureSurfaceCallback(mainViewModel)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (isMainScreenBuilt) ensureSurfaceCallback(mainViewModel)
    }

    // The session owns its ViewModelStore: clearing it is what runs MainViewModel.onCleared().
    override fun onDestroy(owner: LifecycleOwner) {
        surfaceRetryJob?.cancel()
        if (surfaceCallbackRegistered) {
            runCatching { appManager.setSurfaceCallback(null) }
            surfaceCallbackRegistered = false
        }
        mapSurfaceCallback = null
        _viewModelStore.clear()
    }

    private companion object {
        const val TAG = "CarNavigationSession"
        const val SURFACE_RETRY_ATTEMPTS = 10
        const val SURFACE_RETRY_DELAY_MS = 500L
    }
}
