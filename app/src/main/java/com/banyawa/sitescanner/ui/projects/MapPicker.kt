package com.banyawa.sitescanner.ui.projects

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.project.GeoPin
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * Full-screen OpenStreetMap for putting the site pin by hand. The pin stays in the middle
 * and the map moves under it: drag, tap a spot to centre it, search an address or jump to
 * the phone's position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerDialog(initial: GeoPin?, onDismiss: () -> Unit, onPick: (GeoPin) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val located = remember { SiteLocation.hasPermission(context) }
    val map = rememberMapView(initial, located)
    var centre by remember { mutableStateOf(initial ?: GeoPin(DEFAULT_CENTRE.latitude, DEFAULT_CENTRE.longitude)) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, GeoPin>>?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(map) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                centre = map.centrePin()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                centre = map.centrePin()
                return false
            }
        }
        map.addMapListener(listener)
        onDispose { map.removeMapListener(listener) }
    }

    fun goTo(pin: GeoPin) {
        map.controller.animateTo(GeoPoint(pin.latitude, pin.longitude), maxOf(map.zoomLevelDouble, CLOSE_ZOOM), ANIMATION_MS)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.pin_pick_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(centre.coordinates, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onPick(map.centrePin()) }) { Text(stringResource(R.string.pin_use_here)) }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())

                // The tip of the marker is the pinned point.
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(48.dp),
                )

                if (Geocoder.isPresent()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                results = null
                            },
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall),
                            placeholder = { Text(stringResource(R.string.pin_search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                scope.launch {
                                    busy = true
                                    results = SiteLocation.search(context, query)
                                    busy = false
                                    results?.singleOrNull()?.let {
                                        goTo(it.second)
                                        results = null
                                    }
                                }
                            }),
                            trailingIcon = {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, contentDescription = null)
                            },
                        )
                        results?.let { found ->
                            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                if (found.isEmpty()) {
                                    Text(stringResource(R.string.pin_search_none), Modifier.padding(16.dp))
                                }
                                found.forEachIndexed { i, (name, pin) ->
                                    if (i > 0) HorizontalDivider()
                                    Text(
                                        name,
                                        Modifier.fillMaxWidth().clickable {
                                            goTo(pin)
                                            results = null
                                        }.padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                if (located) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                val fix = runCatching { SiteLocation.fixes(context).firstOrNull() }.getOrNull()
                                busy = false
                                fix?.let { goTo(GeoPin(it.latitude, it.longitude)) }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.pin_my_location)) }
                }

                // Required attribution for OpenStreetMap data and tiles.
                Text(
                    stringResource(R.string.pin_osm_attribution),
                    Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray,
                )
            }
        }
    }
}

private fun MapView.centrePin(): GeoPin = mapCenter.let { GeoPin(it.latitude, it.longitude) }

/** An OpenStreetMap view following the screen's lifecycle, on [start] or on Thailand. */
@Composable
private fun rememberMapView(start: GeoPin?, showMyLocation: Boolean): MapView {
    val context = LocalContext.current
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMinZoomLevel(MIN_ZOOM)
            setMaxZoomLevel(MAX_ZOOM)
            controller.setZoom(if (start != null) CLOSE_ZOOM else COUNTRY_ZOOM)
            controller.setCenter(start?.let { GeoPoint(it.latitude, it.longitude) } ?: DEFAULT_CENTRE)
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    p?.let { controller.animateTo(it) }
                    return true
                }

                override fun longPressHelper(p: GeoPoint?): Boolean = false
            }))
        }
    }
    val myLocation = remember(showMyLocation) {
        if (showMyLocation) MyLocationNewOverlay(GpsMyLocationProvider(context), map).also { map.overlays.add(it) } else null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    map.onResume()
                    myLocation?.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    myLocation?.disableMyLocation()
                    map.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            myLocation?.disableMyLocation()
            map.onDetach()
        }
    }
    return map
}

/**
 * osmdroid settings: tiles cached in the app's cache folder (no storage permission) and
 * the app named in the user agent, as the OpenStreetMap tile policy asks.
 */
private fun configureOsm(context: Context) {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

private val DEFAULT_CENTRE = GeoPoint(13.0, 101.0)
private const val COUNTRY_ZOOM = 6.0
private const val CLOSE_ZOOM = 18.0
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0
private const val ANIMATION_MS = 600L
