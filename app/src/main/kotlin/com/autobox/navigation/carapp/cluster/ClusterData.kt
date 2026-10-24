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
package com.autobox.navigation.carapp.cluster

import com.tomtom.sdk.routing.route.Route
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Guidance state shared with the cluster map.
 *
 * A process-wide object: the cluster map is drawn by [ClusterMapService], which lives
 * independently of the car app session that drives guidance.
 */
object ClusterData {
    val isActiveGuidance = MutableStateFlow(false)
    val clusterRoutes = MutableStateFlow<List<Route>>(emptyList())
    val clusterSelectedRoute = MutableStateFlow<Route?>(null)
    val nextManeuver = MutableStateFlow<NextManeuver?>(null)
}

/**
 * The next guidance instruction as the cluster card shows it.
 *
 * @param type One of the androidx.car.app [androidx.car.app.navigation.model.Maneuver] types.
 * @param distanceMeters Distance to the maneuver.
 * @param road Name of the road after the maneuver, or the signpost text.
 */
data class NextManeuver(
    val type: Int,
    val distanceMeters: Double,
    val road: String,
)
