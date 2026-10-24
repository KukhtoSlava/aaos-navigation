package com.autobox.navigation.location

import android.os.Handler
import android.os.Looper
import com.tomtom.sdk.location.GeoLocation
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.location.LocationProvider
import com.tomtom.sdk.location.OnLocationUpdateListener
import com.tomtom.sdk.location.simulation.SimulationLocationProvider
import com.tomtom.sdk.location.simulation.strategy.InterpolationStrategy
import java.util.concurrent.CopyOnWriteArraySet

private const val PARKED_UPDATE_INTERVAL_MS = 1000L

/**
 * A simulated car for the emulator.
 *
 * Parked, it repeats its last location every second, so a map that starts listening later
 * (the cluster) still gets a position. Driving, it forwards a TomTom [SimulationLocationProvider]
 * that interpolates along the route.
 *
 * enable() and disable() are no-ops: several maps share this provider, and the car must not
 * stop because one of them went away.
 */
internal class SimulatedLocationProvider(
    start: GeoPoint,
    looper: Looper,
) : LocationProvider {
    private val handler = Handler(looper)
    private val listeners = CopyOnWriteArraySet<OnLocationUpdateListener>()

    @Volatile
    private var lastLocation = GeoLocation(position = start)

    private var drive: LocationProvider? = null

    private val forward = OnLocationUpdateListener { location -> publish(location) }

    private val parkedTick = object : Runnable {
        override fun run() {
            if (drive == null) publish(lastLocation)
            handler.postDelayed(this, PARKED_UPDATE_INTERVAL_MS)
        }
    }

    init {
        handler.post(parkedTick)
    }

    override val lastKnownLocation: GeoLocation
        get() = lastLocation

    override fun addOnLocationUpdateListener(listener: OnLocationUpdateListener) {
        listeners.add(listener)
    }

    override fun removeOnLocationUpdateListener(listener: OnLocationUpdateListener) {
        listeners.remove(listener)
    }

    override fun enable() = Unit

    override fun disable() = Unit

    override fun close() {
        stopDriving()
        handler.removeCallbacks(parkedTick)
        listeners.clear()
    }

    fun driveAlong(geometry: List<GeoPoint>) {
        if (geometry.size < 2) return
        stopDriving()
        drive = SimulationLocationProvider.create(
            strategy = InterpolationStrategy(geometry.map { GeoLocation(position = it) }),
        ).also {
            it.addOnLocationUpdateListener(forward)
            it.enable()
        }
    }

    fun stopDriving() {
        drive?.let {
            it.removeOnLocationUpdateListener(forward)
            it.close()
        }
        drive = null
        // Parked: keep the position, drop speed and course of the last drive update.
        lastLocation = GeoLocation(position = lastLocation.position)
    }

    private fun publish(location: GeoLocation) {
        lastLocation = location
        listeners.forEach { it.onLocationUpdate(location) }
    }
}
