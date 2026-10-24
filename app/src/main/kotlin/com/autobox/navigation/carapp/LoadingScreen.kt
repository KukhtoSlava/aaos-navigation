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

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autobox.navigation.R
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Initial loading screen for AAOS application.
 *
 * Displays a loading spinner until the SDK initializes, then replaces itself with the screen
 * from [buildMainScreen], so Back from the main screen never returns here. [MainViewModel] is
 * therefore never created before the SDK is ready. On failure it shows the error with a Retry
 * action, and it keeps watching the state: initialization started elsewhere (the cluster or the
 * home screen card) also lets it through.
 *
 * @param carContext CarContext from the Car App Library
 * @param sdkInitialized StateFlow indicating SDK initialization status
 * @param initializationError StateFlow containing SDK initialization error, null if no error
 * @param onRetry Starts SDK initialization again after a failure
 * @param buildMainScreen Factory lambda that constructs the [MainScreen]; must only be invoked
 *   once [sdkInitialized] is true
 */
class LoadingScreen(
    carContext: CarContext,
    private val sdkInitialized: StateFlow<Boolean>,
    private val initializationError: StateFlow<String?>,
    private val onRetry: () -> Unit,
    private val buildMainScreen: () -> MainScreen,
) : Screen(carContext) {
    private var errorMessage: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    // Only while started: finish() on a screen the host is still pushing
                    // breaks the car app lifecycle ("no event down from INITIALIZED").
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        combine(sdkInitialized, initializationError) { initialized, error ->
                            initialized to error
                        }.collect { (initialized, error) ->
                            if (initialized) {
                                screenManager.push(buildMainScreen())
                                finish()
                            } else if (error != errorMessage) {
                                errorMessage = error
                                invalidate()
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val error = errorMessage
        return if (error != null) {
            MessageTemplate.Builder(
                carContext.getString(R.string.template_title_sdk_initialization_error, error),
            )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.template_action_title_retry))
                        .setOnClickListener {
                            errorMessage = null
                            invalidate()
                            onRetry()
                        }
                        .build(),
                )
                .build()
        } else {
            MessageTemplate.Builder(carContext.getString(R.string.template_title_sdk_initializing))
                .setLoading(true)
                .build()
        }
    }
}
