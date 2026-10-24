package com.autobox.navigation.common

import android.content.Context
import android.util.Log
import com.autobox.navigation.BuildConfig
import com.autobox.navigation.location.VehicleLocation
import com.tomtom.sdk.common.configuration.buildSdkConfiguration
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.logging.common.LogLevel
import com.tomtom.sdk.telemetry.UserConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Initializes the TomTom SDK once per process.
 *
 * Both the car app session (main display) and the cluster map service can be the first to
 * start the process, so each of them calls [initialize] and waits for [initialized].
 * The SDK runs online only: no onboard map, tiles and routes come from TomTom's services.
 */
object SdkInitializer {
    private const val TAG = "SdkInitializer"

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Must be called on the main thread. */
    suspend fun initialize(context: Context) = mutex.withLock {
        if (_initialized.value) return@withLock
        // A new attempt: a repeated failure with the same message must still be an emission,
        // or a screen waiting on it (LoadingScreen after Retry) never hears about it.
        _error.value = null
        val appContext = context.applicationContext
        try {
            if (!TomTomSdk.isInitialized) {
                TomTomSdk.initialize(
                    appContext,
                    buildSdkConfiguration(
                        context = appContext,
                        apiKey = BuildConfig.TOMTOM_API_KEY,
                        telemetryUserConsent = { UserConsent.TelemetryOff },
                        coreConfiguration = {
                            loggingConfiguration = {
                                level = LogLevel.WARN
                            }
                        },
                    ),
                )
                scope.launch {
                    TomTomSdk.failures.collect { Log.e(TAG, "TomTom SDK failure: $it") }
                }
            }
            VehicleLocation.initialize(appContext)
            TomTomSdk.navigation.locationProvider = VehicleLocation.provider
            _error.value = null
            _initialized.value = true
            Log.i(TAG, "SDK initialized, simulated location: ${VehicleLocation.isSimulated}")
        } catch (e: IllegalStateException) {
            fail(e)
        } catch (e: SecurityException) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        Log.e(TAG, "Failed to initialize SDK", e)
        _error.value = e.message ?: e.javaClass.simpleName
    }
}
