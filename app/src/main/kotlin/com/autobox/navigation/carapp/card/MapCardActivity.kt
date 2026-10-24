package com.autobox.navigation.carapp.card

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.car.app.activity.CarAppActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.autobox.navigation.R
import com.autobox.navigation.common.SdkInitializer
import com.autobox.navigation.location.VehicleLocation
import kotlinx.coroutines.launch

/**
 * The map card of CarLauncher's home screen (the APP_MAPS activity it embeds in a TaskView).
 *
 * A plain activity in its own task, next to the car app's CarAppActivity:
 * - with one activity for both, the full screen app and the card fought over the same
 *   singleTask task: opening the app pulled it out of the card, CarLauncher launched it back
 *   into the card, and the full screen app closed again;
 * - not a car app session: AOSP's templates host (API level 4) ignores session ids, so a
 *   second car app activity got a second full navigator instead of a card.
 *
 * It shows the shared navigation state top-down, the way the app itself does ([CardMapContent]);
 * the cluster has its own driver's view. A tap opens the full screen app.
 *
 * Only the APP_MAPS intent belongs here. CarLauncher's dock remembers the components of the
 * tasks it sees, so tapping the app in the dock may land here too: that opens the full screen
 * app instead.
 */
class MapCardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isMapCardIntent(intent)) {
            openFullScreen()
            finish()
            return
        }
        lifecycleScope.launch {
            SdkInitializer.initialize(this@MapCardActivity)
            if (SdkInitializer.initialized.value) VehicleLocation.enableIfPermitted(this@MapCardActivity)
        }
        setContent {
            val ready by SdkInitializer.initialized.collectAsStateWithLifecycle()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colorResource(R.color.map_background_light)),
            ) {
                if (ready) CardMapContent()
                // On top of the map, which would otherwise take the taps as pan gestures
                Box(Modifier.matchParentSize().clickable { openFullScreen() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isMapCardIntent(intent)) openFullScreen()
    }

    private fun isMapCardIntent(intent: Intent?) =
        intent?.hasCategory(Intent.CATEGORY_APP_MAPS) == true

    private fun openFullScreen() {
        Log.d(TAG, "Opening the full screen app")
        startActivity(Intent(this, CarAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val TAG = "MapCardActivity"
    }
}
