package com.autobox.navigation.carapp

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autobox.navigation.R
import com.autobox.navigation.location.VehicleLocation
import com.tomtom.sdk.common.Cancellable
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.init.createSearch
import com.tomtom.sdk.location.Place
import com.tomtom.sdk.search.SearchCallback
import com.tomtom.sdk.search.SearchOptions
import com.tomtom.sdk.search.SearchResponse
import com.tomtom.sdk.search.common.error.SearchFailure

private const val TAG = "SearchScreen"
private const val MIN_QUERY_LENGTH = 3
private const val RESULT_LIMIT = 10

/**
 * Destination search, biased to the car's position.
 *
 * The way to pick a destination with AOSP's templates host: it implements Car App API level 4,
 * and taps on the map surface (SurfaceCallback.onClick) need level 5.
 */
class SearchScreen(
    carContext: CarContext,
    private val mainViewModel: MainViewModel,
) : Screen(carContext) {
    private val search = TomTomSdk.createSearch()
    private var pending: Cancellable? = null
    private var results: List<Place> = emptyList()
    private var isLoading = false

    // Bumped on every query: a response to an older query is dropped, even if it was already
    // queued when that query was cancelled.
    private var queryGeneration = 0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                queryGeneration++
                pending?.cancel()
                pending = null
                search.close()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        results.forEach { place ->
            items.addItem(
                Row.Builder()
                    .setTitle(place.title())
                    .addText(place.address?.freeformAddress.orEmpty())
                    .setOnClickListener {
                        mainViewModel.selectPlace(place)
                        screenManager.pop()
                    }
                    .build(),
            )
        }
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = runSearch(searchText)

                override fun onSearchSubmitted(searchText: String) = runSearch(searchText)
            },
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.template_search_hint))
            .setShowKeyboardByDefault(true)
            .apply {
                if (isLoading && results.isEmpty()) setLoading(true) else setItemList(items.build())
            }
            .build()
    }

    private fun runSearch(query: String) {
        val generation = ++queryGeneration
        pending?.cancel()
        pending = null
        if (query.trim().length < MIN_QUERY_LENGTH) {
            results = emptyList()
            isLoading = false
            invalidate()
            return
        }
        isLoading = true
        invalidate()
        val options = SearchOptions(
            query = query.trim(),
            geoBias = VehicleLocation.provider.lastKnownLocation?.position,
            limit = RESULT_LIMIT,
        )
        pending = search.search(
            options,
            object : SearchCallback {
                override fun onSuccess(result: SearchResponse) {
                    if (generation != queryGeneration) return
                    results = result.results.map { it.place }
                    isLoading = false
                    invalidate()
                }

                override fun onFailure(failure: SearchFailure) {
                    if (generation != queryGeneration) return
                    Log.w(TAG, "Search failed: $failure")
                    results = emptyList()
                    isLoading = false
                    invalidate()
                }
            },
        )
    }

    private fun Place.title(): String = details?.names?.firstOrNull() ?: address?.freeformAddress ?: name
}
