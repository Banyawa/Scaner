package com.banyawa.sitescanner.ui.projects

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.project.GeoPin
import com.banyawa.sitescanner.ui.projects.SiteLocation.toPin
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The site's map pin in the project dialog: the pinned coordinates, a button to pin where
 * the phone is (pressed by itself when [autoLocate]) and one to pick the spot on a map.
 * [onAddress] gets the street address of each new pin, for the location field.
 */
@Composable
fun SitePinField(pin: GeoPin?, onPinChange: (GeoPin?) -> Unit, autoLocate: Boolean, onAddress: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setPin by rememberUpdatedState(onPinChange)
    val setAddress by rememberUpdatedState(onAddress)

    var locating by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }

    fun startLocating() {
        problem = null
        locating = true
        run++
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) startLocating() else problem = R.string.pin_permission_denied
    }
    fun locate() {
        if (SiteLocation.hasPermission(context)) startLocating() else permissions.launch(SiteLocation.PERMISSIONS)
    }

    LaunchedEffect(Unit) {
        if (autoLocate && !autoStarted) {
            autoStarted = true
            locate()
        }
    }

    // Cancelled when a pin is picked on the map meanwhile.
    LaunchedEffect(run, locating) {
        if (!locating) return@LaunchedEffect
        var last: GeoPin? = null
        try {
            SiteLocation.fixes(context).collect { fix -> last = fix.toPin().also(setPin) }
            if (last == null) problem = R.string.pin_no_fix
        } catch (e: SiteLocation.LocationOffException) {
            problem = R.string.pin_location_off
        }
        last?.let { SiteLocation.address(context, it) }?.let(setAddress)
        locating = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pin?.coordinates ?: stringResource(if (locating) R.string.pin_locating else R.string.pin_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val detail = pin?.let { p ->
                    val source = p.accuracyM?.let { stringResource(R.string.pin_accuracy, it.roundToInt()) } ?: stringResource(R.string.pin_on_map)
                    if (locating) source + " · " + stringResource(R.string.pin_improving) else source
                }
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (pin != null && !locating) {
                IconButton(onClick = { setPin(null) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pin_clear))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            OutlinedButton(onClick = ::locate, enabled = !locating, modifier = Modifier.weight(1f), contentPadding = padding) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pin_here), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f), contentPadding = padding) {
                Icon(Icons.Filled.Map, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pin_pick), textAlign = TextAlign.Center)
            }
        }
        problem?.let { message ->
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (message == R.string.pin_location_off) {
                TextButton(onClick = { openLocationSettings(context) }) { Text(stringResource(R.string.pin_open_settings)) }
            }
        }
    }

    if (picking) {
        MapPickerDialog(
            initial = pin,
            onDismiss = { picking = false },
            onPick = { picked ->
                picking = false
                locating = false
                problem = null
                setPin(picked)
                scope.launch { SiteLocation.address(context, picked)?.let(setAddress) }
            },
        )
    }
}

/** Keeps a nullable pin across rotation. */
val GeoPinStateSaver = Saver<MutableState<GeoPin?>, List<Double>>(
    save = { state -> state.value?.let { listOf(it.latitude, it.longitude, it.accuracyM?.toDouble() ?: -1.0) } },
    restore = { v -> mutableStateOf(GeoPin(v[0], v[1], v[2].takeIf { it >= 0 }?.toFloat())) },
)

/** Shows [pin] in the phone's map app, or in Google Maps on the web without one. */
fun openInMaps(context: Context, pin: GeoPin, label: String) {
    val coordinates = "${pin.latitude},${pin.longitude}"
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates(${Uri.encode(label)})"))
    try {
        context.startActivity(geo)
    } catch (e: ActivityNotFoundException) {
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$coordinates")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web)) }
    }
}

private fun openLocationSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}
