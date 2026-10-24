package com.autobox.navigation.cluster;

import android.graphics.Rect;
import android.view.Surface;
import com.autobox.navigation.cluster.IClusterMapListener;

/**
 * The instrument cluster map. Navigation draws it into a surface that the cluster app owns
 * and lays its gauges over.
 *
 * Bind with the action com.autobox.navigation.action.CLUSTER_MAP; the caller needs the
 * signature permission com.autobox.navigation.permission.CLUSTER_MAP. This file is the one
 * copy of the contract: the cluster app compiles it through the Soong filegroup
 * "autobox-navigation-cluster-aidl".
 */
interface IClusterMap {
    /**
     * Starts drawing into surface, replacing any surface attached before.
     *
     * @param width, height Surface size in pixels.
     * @param densityDpi Density of the display the surface is shown on.
     * @param insets Pixels at each edge of the surface covered by the cluster's own UI.
     *     The car and the maneuver card are placed in the area left free.
     * @param listener Told whether the map made it onto this surface.
     */
    oneway void attachSurface(in Surface surface, int width, int height, int densityDpi,
            in Rect insets, IClusterMapListener listener);

    /** Stops drawing. Called before the surface is destroyed. */
    oneway void detachSurface();
}
