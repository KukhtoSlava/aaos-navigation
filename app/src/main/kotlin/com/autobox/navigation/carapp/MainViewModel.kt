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

import android.util.Log
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.ViewModel
import com.autobox.navigation.carapp.Scenario.DESTINATION_ARRIVAL
import com.autobox.navigation.carapp.Scenario.GUIDANCE
import com.autobox.navigation.carapp.Scenario.HOME
import com.autobox.navigation.carapp.Scenario.ROUTE_PREVIEW
import com.autobox.navigation.carapp.cluster.ClusterData
import com.autobox.navigation.carapp.cluster.NextManeuver
import com.autobox.navigation.common.PlaceDetails
import com.autobox.navigation.common.name
import com.autobox.navigation.common.extension.toCarManeuver
import com.autobox.navigation.location.VehicleLocation
import com.autobox.navigation.map.camera.CameraController
import com.tomtom.quantity.Distance
import com.tomtom.sdk.common.Cancellable
import com.tomtom.sdk.location.Place
import com.tomtom.sdk.location.LocationProvider
import com.tomtom.sdk.navigation.DestinationArrivalListener
import com.tomtom.sdk.navigation.GuidanceUpdatedListener
import com.tomtom.sdk.navigation.NavigationOptions
import com.tomtom.sdk.navigation.ProgressUpdatedListener
import com.tomtom.sdk.navigation.RoutePlan
import com.tomtom.sdk.navigation.TomTomNavigation
import com.tomtom.sdk.navigation.guidance.GuidanceAnnouncement
import com.tomtom.sdk.navigation.guidance.InstructionPhase
import com.tomtom.sdk.navigation.guidance.instruction.GuidanceInstruction
import com.tomtom.sdk.navigation.progress.RouteProgress
import com.tomtom.sdk.routing.RoutePlanner
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.buildRoutePlanningOptions
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.TimeZone
import androidx.car.app.model.Distance as CarDistance

/**
 * Manages route planning state and operations, and acts as the single source of truth
 * for all state needed to render MainScreen.
 *
 * Must only be created after the TomTom SDK has been initialized: all SDK components are
 * required at construction time.
 *
 * @param routePlanner The instance used for route planning operations.
 * @param navigation The navigation instance driving guidance.
 * @param locationProvider Provider of the current location; enabled once the location
 *   permission is confirmed via [setPermissionsGranted].
 * @param navigationManager Car app service used to report navigation state to the host.
 * @param waitingForLocationMessage Shown when a route is requested before the first location fix.
 */
class MainViewModel(
    private val routePlanner: RoutePlanner,
    private val navigation: TomTomNavigation,
    private val locationProvider: LocationProvider,
    private val navigationManager: NavigationManager,
    private val waitingForLocationMessage: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainScreenUIState())
    val uiState: StateFlow<MainScreenUIState> = _uiState.asStateFlow()

    private val _routeProgress = MutableStateFlow<RouteProgress?>(null)
    val routeProgress: StateFlow<RouteProgress?> = _routeProgress.asStateFlow()

    private var distanceToNextInstruction: Distance? = null

    // True between NavigationManager.navigationStarted() and navigationEnded(). TomTom may
    // still deliver an instruction already queued when guidance stops (e.g. on arrival), and
    // updateTrip() outside a started trip throws and takes the whole app down.
    private var isTripActive = false

    private val progressUpdatedListener = ProgressUpdatedListener { routeProgress ->
        _routeProgress.tryEmit(routeProgress)
    }

    private val guidanceUpdatedListener = object : GuidanceUpdatedListener {
        override fun onDistanceToNextInstructionChanged(
            distance: Distance,
            instructions: List<GuidanceInstruction>,
            currentPhase: InstructionPhase,
        ) {
            distanceToNextInstruction = distance
            instructions.firstOrNull()?.let {
                updateTrip(it)
                publishNextManeuver(it)
            }
        }

        override fun onInstructionsChanged(instructions: List<GuidanceInstruction>) {
            val firstInstruction = instructions.firstOrNull()
            distanceToNextInstruction = firstInstruction?.routeOffset
            firstInstruction?.let {
                updateTrip(it)
                publishNextManeuver(it)
            }
        }

        override fun onAnnouncementGenerated(
            announcement: GuidanceAnnouncement,
            shouldPlay: Boolean,
        ) {
            Log.d(TAG, "Guidance announcement: $announcement")
        }
    }

    private val destinationArrivalListener = DestinationArrivalListener { _ ->
        stopGuidance()
        _uiState.update { it.copy(scenario = DESTINATION_ARRIVAL) }
        recenterCamera()
    }

    private var currentRequestCallback: RoutePlanningCallback? = null

    private var routePlannerCancellable: Cancellable? = null

    private var currentRoutePlanningOptions: RoutePlanningOptions? = null
    private var destinationName: String = ""

    private var cameraController: CameraController? = null

    init {
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                super.onStopNavigation()
                stopGuidance()
            }
        })
    }

    /**
     * Sets permissions granted state from the UI layer and enables the location provider on the first grant.
     */
    fun setPermissionsGranted(granted: Boolean) {
        if (granted && !uiState.value.permissionsGranted) {
            locationProvider.enable()
        }
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    /** Sets location enabled state from the UI layer. */
    fun setLocationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(locationEnabled = enabled) }
    }

    fun setCameraController(cameraController: CameraController) {
        this.cameraController = cameraController
    }

    /** Focuses a place picked outside the map, e.g. in [SearchScreen]. */
    fun selectPlace(place: Place) {
        stopGuidance()
        cancelRoutePlanning()
        _uiState.update {
            it.copy(
                scenario = Scenario.POI_FOCUS,
                placeDetails = PlaceDetails(place),
                routes = emptyList(),
                selectedRoute = null,
                isLoading = false,
            )
        }
        recenterCamera()
    }

    /** Plans a route from the current location to the given place details location. */
    fun planRoute(placeDetails: PlaceDetails) {
        // No fallback origin here: a route from anywhere but the car would be wrong, e.g.
        // right after boot before the first GNSS fix.
        val origin = locationProvider.lastKnownLocation?.position
        if (origin == null) {
            _uiState.update { it.copy(failureMessage = waitingForLocationMessage) }
            return
        }
        _uiState.update { it.copy(isLoading = true) }

        destinationName = placeDetails.name
        val destination = placeDetails.place.coordinate
        val routePlanningOptions = buildRoutePlanningOptions(Itinerary(origin, destination))

        val callback = object : RoutePlanningCallback {
            override fun onSuccess(result: RoutePlanningResponse) {
                if (currentRequestCallback == this) {
                    _uiState.update {
                        it.copy(
                            scenario = ROUTE_PREVIEW,
                            routes = result.routes,
                            selectedRoute = result.routes.firstOrNull(),
                            isLoading = false,
                            placeDetails = null,
                        )
                    }
                    currentRequestCallback = null
                }
            }

            override fun onFailure(failure: RoutingFailure) {
                if (currentRequestCallback == this) {
                    Log.e(TAG, "Route planning failed: $failure")
                    _uiState.update {
                        it.copy(
                            routes = emptyList(),
                            selectedRoute = null,
                            isLoading = false,
                        )
                    }
                    currentRequestCallback = null
                }
            }
        }

        currentRequestCallback = callback
        currentRoutePlanningOptions = routePlanningOptions

        routePlannerCancellable?.cancel()
        routePlannerCancellable = routePlanner.planRoute(routePlanningOptions, callback)
    }

    /** Dismisses every panel. */
    fun clearClicked() {
        stopGuidance()
        cancelRoutePlanning()
        _uiState.update {
            it.copy(
                scenario = HOME,
                placeDetails = null,
                routes = emptyList(),
                selectedRoute = null,
                isLoading = false,
            )
        }
        recenterCamera()
    }

    // A response to an abandoned request must not bring its route back: the callback identity
    // check in planRoute() drops it once currentRequestCallback no longer matches.
    private fun cancelRoutePlanning() {
        currentRequestCallback = null
        routePlannerCancellable?.cancel()
        routePlannerCancellable = null
    }

    /** Clears the failure message after it has been shown. */
    fun dismissFailureMessage() {
        _uiState.update { it.copy(failureMessage = null) }
    }

    /** Starts guidance for the currently selected route. */
    fun startGuidance() {
        stopGuidance()
        uiState.value.selectedRoute?.let { selectedRoute ->
            currentRoutePlanningOptions?.let { planningOptions ->
                val navigationOptions = NavigationOptions(RoutePlan(selectedRoute, planningOptions))
                navigation.addProgressUpdatedListener(progressUpdatedListener)
                navigation.addGuidanceUpdatedListener(guidanceUpdatedListener)
                navigation.addDestinationArrivalListener(destinationArrivalListener)
                // Before navigation.start(): its first instructions update the trip
                navigationManager.navigationStarted()
                isTripActive = true
                navigation.start(navigationOptions)
                VehicleLocation.driveAlong(selectedRoute.geometry)
                ClusterData.isActiveGuidance.value = true
                ClusterData.clusterRoutes.value = listOf(selectedRoute)
                ClusterData.clusterSelectedRoute.value = selectedRoute
                _uiState.update { it.copy(scenario = GUIDANCE) }
                recenterCamera()
            }
        }
    }

    /** Stops guidance and clears the navigation listeners. */
    private fun stopGuidance() {
        navigation.removeProgressUpdatedListener(progressUpdatedListener)
        navigation.removeGuidanceUpdatedListener(guidanceUpdatedListener)
        navigation.removeDestinationArrivalListener(destinationArrivalListener)
        navigation.stop()
        VehicleLocation.stopDriving()
        if (isTripActive) {
            isTripActive = false
            navigationManager.navigationEnded()
        }
        ClusterData.isActiveGuidance.value = false
        ClusterData.clusterRoutes.value = emptyList()
        ClusterData.clusterSelectedRoute.value = null
        ClusterData.nextManeuver.value = null
        distanceToNextInstruction = null
    }

    private fun updateTrip(instruction: GuidanceInstruction) {
        if (isTripActive) navigationManager.updateTrip(buildTrip(instruction))
    }

    private fun publishNextManeuver(instruction: GuidanceInstruction) {
        if (!isTripActive) return
        val meters = distanceToNextInstruction?.inMeters() ?: return
        ClusterData.nextManeuver.value = NextManeuver(
            type = instruction.toCarManeuver().type,
            distanceMeters = meters,
            road = instruction.roadText(),
        )
    }

    private fun buildTrip(instruction: GuidanceInstruction): Trip {
        val stepMeters = distanceToNextInstruction?.inMeters()
        val progress = _routeProgress.value
        if (stepMeters == null || progress == null) {
            return Trip.Builder().setLoading(true).build()
        }

        val now = System.currentTimeMillis()
        val remainingMs = progress.remainingTime.inWholeMilliseconds
        val remainingMeters = progress.remainingDistance.inMeters()
        // TomTom reports time to the destination only; the time to the maneuver is that time
        // pro rata to the distance, so a step never carries the destination's arrival time.
        val stepMs = if (remainingMeters > 0) (remainingMs * stepMeters / remainingMeters).toLong() else 0L

        val step = Step.Builder(instruction.roadText()).setManeuver(instruction.toCarManeuver()).build()
        return Trip.Builder()
            .addStep(step, travelEstimate(stepMeters, now + stepMs))
            .addDestination(
                Destination.Builder().setName(destinationName).build(),
                travelEstimate(remainingMeters, now + remainingMs),
            )
            .setLoading(false)
            .build()
    }

    private fun travelEstimate(meters: Double, arrivalMillis: Long) = TravelEstimate.Builder(
        CarDistance.create(meters, CarDistance.UNIT_METERS),
        DateTimeWithZone.create(arrivalMillis, TimeZone.getDefault()),
    ).build()

    /** Animates the camera back to the center depending on [com.autobox.navigation.carapp.Scenario]. */
    fun recenterCamera() {
        val trackingMode = uiState.value.scenario.defaultTrackingMode()
        Log.d(TAG, "Camera tracking mode for Scenario ${uiState.value.scenario}: $trackingMode")
        cameraController?.setCameraTracking(trackingMode)

        uiState.value.scenario.defaultCameraOptions(
            place = uiState.value.placeDetails,
            locationProvider = locationProvider,
        )?.let { cameraOptions ->
            Log.d(TAG, "Camera options: $cameraOptions")
            cameraController?.animateCamera(cameraOptions)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isTripActive) stopGuidance()
        cancelRoutePlanning()
        navigationManager.clearNavigationManagerCallback()
        cameraController = null
        // The location provider stays on: the cluster and the home screen card follow it too.
        currentRoutePlanningOptions = null
    }

    companion object {
        private const val TAG = "MainViewModel"

        private fun GuidanceInstruction.roadText(): String =
            nextSignificantRoad?.name ?: signpost?.towardName ?: signpost?.exitName ?: ""
    }
}
