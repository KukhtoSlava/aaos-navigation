package com.autobox.navigation.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.location.LocationProvider

/**
 * The single location source of the app. The main map, the cluster map and guidance all follow
 * [provider], so the car is in the same place on both displays.
 *
 * On the emulator the car is simulated: it waits in Warsaw and drives along the route once
 * guidance starts. On a real car it is TomTom's default provider (GNSS from LocationManager).
 */
object VehicleLocation {
    /** Where the simulated car starts: central Warsaw. */
    val SIMULATION_START = GeoPoint(52.2297, 21.0122)

    lateinit var provider: LocationProvider
        private set

    val isSimulated: Boolean
        get() = provider is SimulatedLocationProvider

    fun initialize(context: Context) {
        if (::provider.isInitialized) return
        provider = if (isEmulator()) {
            SimulatedLocationProvider(SIMULATION_START, context.mainLooper)
        } else {
            TomTomSdk.locationProvider
        }
    }

    /**
     * Starts the location source for a map that may come up before the main display's map
     * (which asks for the permission), e.g. the cluster or the home screen card.
     */
    fun enableIfPermitted(context: Context) {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || isSimulated) provider.enable()
    }

    /** Makes the simulated car drive along [geometry]. No-op with a real location source. */
    fun driveAlong(geometry: List<GeoPoint>) {
        (provider as? SimulatedLocationProvider)?.driveAlong(geometry)
    }

    /** Parks the simulated car where it is. No-op with a real location source. */
    fun stopDriving() {
        (provider as? SimulatedLocationProvider)?.stopDriving()
    }

    // Goldfish/ranchu is the emulator's virtual hardware; real cars never report it.
    private fun isEmulator(): Boolean = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
}
