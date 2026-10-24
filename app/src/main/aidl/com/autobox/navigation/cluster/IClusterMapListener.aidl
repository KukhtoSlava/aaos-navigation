package com.autobox.navigation.cluster;

/** What became of an IClusterMap.attachSurface() call. Called on a binder thread. */
oneway interface IClusterMapListener {
    /** The map is on the surface: the cluster can drop its placeholder. */
    void onMapShown();

    /** Navigation could not draw into the surface; the placeholder should stay. */
    void onMapFailed(String reason);
}
