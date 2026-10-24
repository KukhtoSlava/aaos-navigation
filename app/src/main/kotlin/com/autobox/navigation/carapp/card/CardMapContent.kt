package com.autobox.navigation.carapp.card

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autobox.navigation.R
import com.autobox.navigation.carapp.cluster.ClusterData
import com.autobox.navigation.location.VehicleLocation
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.map.display.MapLocationInfrastructure
import com.tomtom.sdk.map.display.camera.CameraTrackingMode
import com.tomtom.sdk.map.display.camera.FollowCameraOperatorConfig
import com.tomtom.sdk.map.display.camera.InitialCameraOptions
import com.tomtom.sdk.map.display.compose.TomTomMap
import com.tomtom.sdk.map.display.compose.model.MapDisplayInfrastructure
import com.tomtom.sdk.map.display.compose.nodes.CurrentLocationMarker
import com.tomtom.sdk.map.display.compose.properties.CurrentLocationMarkerProperties
import com.tomtom.sdk.map.display.compose.state.rememberMapViewState
import com.tomtom.sdk.map.display.location.LocationMarkerOptions
import com.tomtom.sdk.map.display.style.StyleMode
import com.tomtom.sdk.map.display.visualization.navigation.NavigationVisualizationDataProvider
import com.tomtom.sdk.map.display.visualization.navigation.compose.NavigationVisualization
import com.tomtom.sdk.map.display.visualization.navigation.compose.model.NavigationVisualizationInfrastructure
import com.tomtom.sdk.map.display.visualization.routing.RoutingVisualizationDataProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val CARD_ZOOM = 14.0

// Top-down, like the app's own map: the card is a glance at the whole area, not the
// driver's view of the cluster.
private val cardCameraConfig = FollowCameraOperatorConfig(
    defaultZoom = CARD_ZOOM,
    defaultTilt = 0.0,
)

/**
 * Map of the home screen card, styled like the full screen app (MapScreen): top-down, day or
 * night style after the system, TomTom's chevron for the car. North up while idle; during
 * guidance it turns with the route and shows it with its instruction arrows.
 */
@Composable
fun CardMapContent() {
    val mapDisplayInfrastructure = remember {
        MapDisplayInfrastructure(sdkContext = TomTomSdk.sdkContext) {
            locationInfrastructure = MapLocationInfrastructure {
                locationProvider = VehicleLocation.provider
            }
        }
    }
    val navigationInfrastructure = remember {
        NavigationVisualizationInfrastructure(
            routingVisualizationDataProvider = flowOf(
                RoutingVisualizationDataProvider(
                    routes = ClusterData.clusterRoutes,
                    selectedRouteId = ClusterData.clusterSelectedRoute.map { it?.id },
                ),
            ),
            navigationVisualizationDataProvider = flowOf(
                NavigationVisualizationDataProvider(tomtomNavigation = TomTomSdk.navigation),
            ),
        )
    }

    val dark = isSystemInDarkTheme()
    val mapViewState = rememberMapViewState(
        initialCameraOptions = InitialCameraOptions.LocationBased(
            position = VehicleLocation.provider.lastKnownLocation?.position ?: VehicleLocation.SIMULATION_START,
            zoom = CARD_ZOOM,
            tilt = 0.0,
        ),
    ) {
        styleState.styleMode = if (dark) StyleMode.DARK else StyleMode.MAIN
    }
    SideEffect {
        mapViewState.styleState.styleMode = if (dark) StyleMode.DARK else StyleMode.MAIN
    }

    val isGuiding by ClusterData.isActiveGuidance.collectAsStateWithLifecycle()
    LaunchedEffect(isGuiding) {
        mapViewState.cameraState.trackingMode = if (isGuiding) {
            CameraTrackingMode.FollowRouteDirection(cardCameraConfig)
        } else {
            CameraTrackingMode.FollowNorthUp
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colorResource(if (dark) R.color.map_background_dark else R.color.map_background_light)),
    ) {
        TomTomMap(
            infrastructure = mapDisplayInfrastructure,
            state = mapViewState,
        ) {
            CurrentLocationMarker(
                properties = CurrentLocationMarkerProperties {
                    type = LocationMarkerOptions.Type.Chevron
                },
            )
            NavigationVisualization(infrastructure = navigationInfrastructure)
        }
    }
}
