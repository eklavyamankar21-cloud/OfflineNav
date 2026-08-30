package com.example.offlinenav

import androidx.compose.foundation.background
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var textToSpeech: android.speech.tts.TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                textToSpeech?.language = java.util.Locale.US
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                textToSpeech?.setAudioAttributes(audioAttributes)
            }
        }
        MapLibre.getInstance(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val context = LocalContext.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val coroutineScope = rememberCoroutineScope()

            val availableRegions = remember {
                mutableStateListOf(
                    RegionMap("Telangana", "https://your-cdn.com/telangana.mbtiles", "telangana_map.mbtiles"),
                    RegionMap("Maharashtra", "https://your-cdn.com/maharashtra.mbtiles", "maharashtra_map.mbtiles"),
                    RegionMap("All India", "https://huggingface.co/datasets/EKLAVYA369/offline-india-map/resolve/main/india.mbtiles?download=true", "india.mbtiles")
                ).apply {
                    forEach { region ->
                        region.isDownloaded = java.io.File(context.getExternalFilesDir(null), region.fileName).exists()
                    }
                }
            }

            val locationPermissionRequest = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            LaunchedEffect(Unit) {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                locationPermissionRequest.launch(permissions.toTypedArray())
            }

            var mapLibreMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
            var distanceText by remember { mutableStateOf("") }
            var etaDetails by remember { mutableStateOf(Pair("", "")) }

            val dbHelper = remember { OfflineGeocoderDB(context) }
            var searchResults by remember { mutableStateOf<List<Pair<String, Pair<Double, Double>>>>(emptyList()) }
            var searchQuery by remember { mutableStateOf("") }
            var isSearchActive by remember { mutableStateOf(false) }
            var selectedDestination by remember { mutableStateOf<Pair<Double, Double>?>(null) }

            var routeDrawn by remember { mutableStateOf(false) }
            var isNavigating by remember { mutableStateOf(false) }
            var isRecalculating by remember { mutableStateOf(false) }
            var hasAnnouncedArrival by remember { mutableStateOf(false) }
            var isNightMode by remember { mutableStateOf(false) }
            var travelMode by remember { mutableStateOf("bike") }
            var showMapManager by remember { mutableStateOf(false) }
            var showInfoDialog by remember { mutableStateOf(false) }
            var isVoiceMuted by remember { mutableStateOf(false) }
            var routeElevations by remember { mutableStateOf<List<Double>>(emptyList()) }

            var currentRouteGeoJson by remember { mutableStateOf<org.maplibre.geojson.FeatureCollection?>(null) }
            var currentRouteCoords by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }

            var activeOnlineRoute by remember { mutableStateOf<OnlineRouteData?>(null) }
            var currentInstructions by remember { mutableStateOf<com.graphhopper.util.InstructionList?>(null) }
            var currentOnlineTurns by remember { mutableStateOf<List<NavTurn>?>(null) }
            var upcomingManeuver by remember { mutableStateOf("Drive safely") }
            var maneuverDistanceText by remember { mutableStateOf("") }
            var showTurnBanner by remember { mutableStateOf(false) }
            var currentTurnIndex by remember { mutableIntStateOf(0) }
            var hasGivenAdvanceWarning by remember { mutableStateOf(false) }

            val hopperHelper = remember { GraphHopperHelper(context) }
            var isHopperReady by remember { mutableStateOf(false) }
            var activeMapFileName by remember { mutableStateOf("telangana_map.mbtiles") }

            LaunchedEffect(Unit) {
                isHopperReady = hopperHelper.initEngine()
            }

            LaunchedEffect(routeDrawn, isNavigating, mapLibreMap) {
                if (routeDrawn || isNavigating) {
                    mapLibreMap?.setPadding(0, 0, 0, 650)
                } else {
                    mapLibreMap?.setPadding(0, 0, 0, 0)
                }
            }

            var quickBookmarks by remember { mutableStateOf<List<Pair<String, Pair<Double, Double>>>>(emptyList()) }

            LaunchedEffect(Unit) {
                dbHelper.createBookmarksTable()
                quickBookmarks = dbHelper.getBookmarks()
            }

            val offlineStyleJson = remember(activeMapFileName) {
                val activePath = java.io.File(context.getExternalFilesDir(null), activeMapFileName).absolutePath
                """
                {
                  "version": 8,
                  "sources": { "local-map": { "type": "vector", "url": "mbtiles://$activePath" } },
                  "layers": [
                    { "id": "background", "type": "background", "paint": { "background-color": "#E8E0D8" } },
                    { "id": "water-layer", "type": "fill", "source": "local-map", "source-layer": "water-polygons", "paint": { "fill-color": "#73B6E6" } },
                    { "id": "parks-and-greenery", "type": "fill", "source": "local-map", "source-layer": "land", "filter": ["in", "kind", "park", "forest", "nature_reserve", "wood"], "paint": { "fill-color": "#C8DF9F" } },
                    { "id": "buildings-layer", "type": "fill", "source": "local-map", "source-layer": "buildings", "paint": { "fill-color": "#D4D4D4", "fill-opacity": 0.6 } },
                    { "id": "minor-roads", "type": "line", "source": "local-map", "source-layer": "streets", "filter": ["!in", "kind", "motorway", "trunk", "primary"], "paint": { "line-color": "#FFFFFF", "line-width": 1.5 } },
                    { "id": "major-highways", "type": "line", "source": "local-map", "source-layer": "streets", "filter": ["in", "kind", "motorway", "trunk", "primary"], "paint": { "line-color": "#FBB05B", "line-width": 3.0 } }
                  ]
                }
                """.trimIndent()
            }

            val darkOfflineStyleJson = remember(activeMapFileName) {
                val activePath = java.io.File(context.getExternalFilesDir(null), activeMapFileName).absolutePath
                """
                {
                  "version": 8,
                  "sources": { "local-map": { "type": "vector", "url": "mbtiles://$activePath" } },
                  "layers": [
                    { "id": "background", "type": "background", "paint": { "background-color": "#121212" } },
                    { "id": "water-layer", "type": "fill", "source": "local-map", "source-layer": "water-polygons", "paint": { "fill-color": "#1E3A5F" } },
                    { "id": "parks-and-greenery", "type": "fill", "source": "local-map", "source-layer": "land", "filter": ["in", "kind", "park", "forest", "nature_reserve", "wood"], "paint": { "fill-color": "#1E3315" } },
                    { "id": "buildings-layer", "type": "fill", "source": "local-map", "source-layer": "buildings", "paint": { "fill-color": "#333333", "fill-opacity": 0.6 } },
                    { "id": "minor-roads", "type": "line", "source": "local-map", "source-layer": "streets", "filter": ["!in", "kind", "motorway", "trunk", "primary"], "paint": { "line-color": "#444444", "line-width": 1.5 } },
                    { "id": "major-highways", "type": "line", "source": "local-map", "source-layer": "streets", "filter": ["in", "kind", "motorway", "trunk", "primary"], "paint": { "line-color": "#1E88E5", "line-width": 3.0 } }
                  ]
                }
                """.trimIndent()
            }

            val onlineStyleJson = remember {
                """
                {
                  "version": 8,
                  "sources": {
                    "esri-light": {
                      "type": "raster",
                      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"],
                      "tileSize": 256,
                      "attribution": "Powered by Esri"
                    }
                  },
                  "layers": [
                    {
                      "id": "background-light",
                      "type": "background",
                      "paint": { "background-color": "#F2EFE9" }
                    },
                    {
                      "id": "esri-light-layer",
                      "type": "raster",
                      "source": "esri-light",
                      "minzoom": 0,
                      "maxzoom": 19,
                      "paint": { "raster-fade-duration": 400 }
                    }
                  ]
                }
                """.trimIndent()
            }

            val onlineDarkStyleJson = remember {
                """
                {
                  "version": 8,
                  "sources": {
                    "esri-dark": {
                      "type": "raster",
                      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"],
                      "tileSize": 256,
                      "attribution": "Powered by Esri"
                    }
                  },
                  "layers": [
                    {
                      "id": "background-dark",
                      "type": "background",
                      "paint": { "background-color": "#212121" }
                    },
                    {
                      "id": "esri-dark-layer",
                      "type": "raster",
                      "source": "esri-dark",
                      "minzoom": 0,
                      "maxzoom": 19,
                      "paint": { "raster-fade-duration": 400 }
                    }
                  ]
                }
                """.trimIndent()
            }

            val triggerRouteCalculation = { destName: String, coords: Pair<Double, Double> ->
                searchQuery = destName
                selectedDestination = coords
                isSearchActive = false
                keyboardController?.hide()

                val userLocation = mapLibreMap?.locationComponent?.lastKnownLocation
                if (userLocation != null) {
                    coroutineScope.launch {
                        val offlineResult = if (isHopperReady) hopperHelper.calculateRoute(
                            userLocation.latitude, userLocation.longitude,
                            coords.first, coords.second, travelMode
                        ) else null

                        if (offlineResult != null) {
                            currentInstructions = offlineResult.instructions
                            currentOnlineTurns = null
                            activeOnlineRoute = null
                            currentTurnIndex = 0
                            if (offlineResult.instructions.isNotEmpty()) {
                                upcomingManeuver = generateManeuverText(offlineResult.instructions[0], dbHelper, true)
                            }
                            val points = offlineResult.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                            val newRoute = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points)))
                            mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("my-route-source")?.setGeoJson(newRoute)
                            mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))

                            distanceText = String.format(java.util.Locale.US, "%.1f km", offlineResult.distanceKm)
                            routeElevations = offlineResult.elevations
                            etaDetails = calculateETA(offlineResult.distanceKm, travelMode)
                            currentRouteCoords = offlineResult.pathCoords
                            currentRouteGeoJson = newRoute
                            routeDrawn = true
                        } else {
                            val onlineResult = fetchOnlineRoute(
                                userLocation.latitude, userLocation.longitude,
                                coords.first, coords.second, travelMode
                            )
                            if (onlineResult != null) {
                                activeOnlineRoute = onlineResult
                                currentInstructions = null
                                currentOnlineTurns = onlineResult.turns
                                currentTurnIndex = 0
                                upcomingManeuver = onlineResult.turns.firstOrNull()?.instruction ?: "Follow the highlighted route"
                                maneuverDistanceText = ""

                                val points = onlineResult.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                val newRoute = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points)))
                                mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("my-route-source")?.setGeoJson(newRoute)

                                if (onlineResult.altRoute != null) {
                                    val altPoints = onlineResult.altRoute.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                    val altRoute = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(altPoints)))
                                    mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(altRoute)
                                } else {
                                    mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                                }
                                distanceText = String.format(java.util.Locale.US, "%.1f km", onlineResult.distKm)

                                routeElevations = generateElevationsForPoints(onlineResult.pathCoords.size, onlineResult.distKm)

                                etaDetails = formatTomTomETA(onlineResult.travelTimeSeconds)
                                currentRouteCoords = onlineResult.pathCoords
                                currentRouteGeoJson = newRoute
                                routeDrawn = true
                                Toast.makeText(context, "Using Online Route (All-India)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(context, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(isNavigating, selectedDestination) {
                var lastRecalcTime = 0L
                var lastEtaRefreshTime = System.currentTimeMillis()

                while (isNavigating && selectedDestination != null) {
                    val rawUserLoc = mapLibreMap?.locationComponent?.lastKnownLocation
                    var isStationary = false
                    if (rawUserLoc != null && currentRouteCoords.isNotEmpty()) {
                        val snappedLoc = getSnappedLocation(rawUserLoc, currentRouteCoords)
                        mapLibreMap?.locationComponent?.forceLocationUpdate(snappedLoc)
                        val distanceToRouteMeters = getDistanceToRouteMeters(rawUserLoc, currentRouteCoords)
                        val currentTime = System.currentTimeMillis()
                        val speedKmh = rawUserLoc.speed * 3.6f
                        isStationary = speedKmh < 3.0f

                        val pingInterval = if (isStationary) 180000 else 60000
                        if (currentTime - lastEtaRefreshTime > pingInterval) {
                            lastEtaRefreshTime = currentTime
                            coroutineScope.launch {
                                val refreshResult = fetchOnlineRoute(
                                    rawUserLoc.latitude, rawUserLoc.longitude,
                                    selectedDestination!!.first, selectedDestination!!.second, travelMode
                                )
                                if (refreshResult != null) {
                                    etaDetails = formatTomTomETA(refreshResult.travelTimeSeconds)
                                }
                            }
                        }

                        if (distanceToRouteMeters > 150.0f && !isRecalculating && (currentTime - lastRecalcTime > 15000)) {
                            isRecalculating = true
                            lastRecalcTime = currentTime
                            if (!isVoiceMuted) {
                                textToSpeech?.speak("Recalculating route.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                            coroutineScope.launch {
                                val onlineResult = fetchOnlineRoute(
                                    rawUserLoc.latitude, rawUserLoc.longitude,
                                    selectedDestination!!.first, selectedDestination!!.second, travelMode
                                )
                                if (onlineResult != null) {
                                    activeOnlineRoute = onlineResult
                                    currentInstructions = null
                                    currentOnlineTurns = onlineResult.turns
                                    currentTurnIndex = 0
                                    val points = onlineResult.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                    val newRoute = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points)))
                                    currentRouteGeoJson = newRoute
                                    currentRouteCoords = onlineResult.pathCoords

                                    routeElevations = generateElevationsForPoints(onlineResult.pathCoords.size, onlineResult.distKm)

                                    etaDetails = formatTomTomETA(onlineResult.travelTimeSeconds)
                                    mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("my-route-source")?.setGeoJson(newRoute)
                                    if (onlineResult.altRoute != null) {
                                        val altPoints = onlineResult.altRoute.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                        val altRoute = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(altPoints)))
                                        mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(altRoute)
                                    } else {
                                        mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                                    }
                                }
                                isRecalculating = false
                            }
                        }

                        val distanceKm = getRemainingRouteDistance(snappedLoc, currentRouteCoords)
                        distanceText = String.format(java.util.Locale.US, "%.1f km", distanceKm)
                        if (distanceKm < 0.06 && !hasAnnouncedArrival) {
                            triggerHapticAlert(context)
                            if (!isVoiceMuted) {
                                textToSpeech?.speak("You have arrived.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                            hasAnnouncedArrival = true
                        }
                    }
                    delay(if (isStationary) 3000L else 1000L)
                }
            }

            LaunchedEffect(isNightMode, mapLibreMap) {
                mapLibreMap?.let { map ->
                    val connected = isOnline(context)
                    val currentStyle = when {
                        connected && isNightMode -> onlineDarkStyleJson
                        connected && !isNightMode -> onlineStyleJson
                        !connected && isNightMode -> darkOfflineStyleJson
                        else -> offlineStyleJson
                    }

                    map.setStyle(Style.Builder().fromJson(currentStyle)) { style ->
                        val source = org.maplibre.android.style.sources.GeoJsonSource("my-route-source")
                        if (routeDrawn && currentRouteGeoJson != null) {
                            source.setGeoJson(currentRouteGeoJson)
                        }
                        style.addSource(source)
                        val lineLayer = org.maplibre.android.style.layers.LineLayer("my-route-layer", "my-route-source")
                        lineLayer.setProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor(android.graphics.Color.parseColor(if (isNightMode) "#39FF14" else "#4A89F3")),
                            org.maplibre.android.style.layers.PropertyFactory.lineWidth(7f),
                            org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                            org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                        )
                        style.addLayer(lineLayer)

                        val altSource = org.maplibre.android.style.sources.GeoJsonSource("alt-route-source")
                        style.addSource(altSource)
                        val altLineLayer = org.maplibre.android.style.layers.LineLayer("alt-route-layer", "alt-route-source")
                        altLineLayer.setProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor(android.graphics.Color.parseColor("#888888")),
                            org.maplibre.android.style.layers.PropertyFactory.lineWidth(5f),
                            org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                            org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                        )
                        style.addLayerBelow(altLineLayer, "my-route-layer")

                        if (connected) {
                            val tomTomKey = "6sDGyGE1NhZkFs3fvRp36pXWClG1oJjp"

                            val trafficUrl = "https://api.tomtom.com/traffic/map/4/tile/flow/absolute/{z}/{x}/{y}.png?key=$tomTomKey"
                            val tileSet = org.maplibre.android.style.sources.TileSet("2.2.0", trafficUrl)
                            val trafficSource = org.maplibre.android.style.sources.RasterSource("tomtom-traffic-source", tileSet, 256)
                            style.addSource(trafficSource)

                            val trafficLayer = org.maplibre.android.style.layers.RasterLayer("tomtom-traffic-layer", "tomtom-traffic-source")
                            trafficLayer.setProperties(org.maplibre.android.style.layers.PropertyFactory.rasterOpacity(0.85f))
                            style.addLayerBelow(trafficLayer, "alt-route-layer")

                            val incidentsUrl = "https://api.tomtom.com/traffic/map/4/tile/incidents/s0/{z}/{x}/{y}.png?key=$tomTomKey&tileSize=256"
                            val incidentsTileSet = org.maplibre.android.style.sources.TileSet("2.2.0", incidentsUrl)
                            val incidentsSource = org.maplibre.android.style.sources.RasterSource("tomtom-incidents-source", incidentsTileSet, 256)
                            style.addSource(incidentsSource)

                            val incidentsLayer = org.maplibre.android.style.layers.RasterLayer("tomtom-incidents-layer", "tomtom-incidents-source")
                            style.addLayerAbove(incidentsLayer, "my-route-layer")
                        }

                        try {
                            val locationComponent = map.locationComponent
                            val customLocationOptions = org.maplibre.android.location.LocationComponentOptions.builder(context)
                                .backgroundDrawable(R.drawable.ic_nav_bg)
                                .bearingDrawable(R.drawable.ic_navigation_triangle)
                                .gpsDrawable(R.drawable.ic_navigation_triangle)
                                .backgroundTintColor(android.graphics.Color.WHITE)
                                .backgroundStaleTintColor(android.graphics.Color.WHITE)
                                .foregroundTintColor(android.graphics.Color.parseColor("#4A89F3"))
                                .bearingTintColor(android.graphics.Color.parseColor("#4A89F3"))
                                .build()
                            val options = org.maplibre.android.location.LocationComponentActivationOptions.builder(context, style)
                                .locationComponentOptions(customLocationOptions)
                                .build()
                            locationComponent.activateLocationComponent(options)
                            locationComponent.isLocationComponentEnabled = true
                            locationComponent.cameraMode = if (isNavigating) org.maplibre.android.location.modes.CameraMode.TRACKING_GPS else org.maplibre.android.location.modes.CameraMode.NONE_COMPASS
                            locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
                        } catch (e: SecurityException) {
                            println("GPS Permission missing.")
                        }

                        if (!routeDrawn && !isNavigating) {
                            val startingPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                .target(org.maplibre.android.geometry.LatLng(17.3850, 78.4867))
                                .zoom(10.0)
                                .build()
                            map.cameraPosition = startingPosition
                        }
                    }
                }
            }

            LaunchedEffect(isNavigating) {
                while (isNavigating) {
                    val userLoc = mapLibreMap?.locationComponent?.lastKnownLocation
                    if (userLoc != null) {
                        val totalDistanceKm = getRemainingRouteDistance(userLoc, currentRouteCoords)
                        if (totalDistanceKm < 0.06f) {
                            upcomingManeuver = "Arrive at destination"
                            maneuverDistanceText = ""
                            currentInstructions = null
                            currentOnlineTurns = null
                        } else {
                            if (currentOnlineTurns != null && currentTurnIndex < currentOnlineTurns!!.size) {
                                val currentTurn = currentOnlineTurns!![currentTurnIndex]
                                val results = FloatArray(1)
                                android.location.Location.distanceBetween(userLoc.latitude, userLoc.longitude, currentTurn.lat, currentTurn.lon, results)
                                val distanceToTurn = results[0]

                                maneuverDistanceText = if (currentTurn.isFinish) { "" } else if (distanceToTurn > 1000) { String.format(java.util.Locale.US, "In %.1f km", distanceToTurn / 1000) } else { "In ${distanceToTurn.toInt()} m" }

                                if (distanceToTurn in 100.0f..250.0f && !hasGivenAdvanceWarning) {
                                    if (!isVoiceMuted) textToSpeech?.speak("In $maneuverDistanceText, $upcomingManeuver", android.speech.tts.TextToSpeech.QUEUE_ADD, null, null)
                                    hasGivenAdvanceWarning = true
                                }
                                if (distanceToTurn < 35.0f) {
                                    currentTurnIndex++
                                    hasGivenAdvanceWarning = false
                                    if (currentTurnIndex < currentOnlineTurns!!.size) {
                                        upcomingManeuver = currentOnlineTurns!![currentTurnIndex].instruction
                                        if (!isVoiceMuted) textToSpeech?.speak(upcomingManeuver, android.speech.tts.TextToSpeech.QUEUE_ADD, null, null)
                                    }
                                }
                            } else if (currentInstructions != null && currentTurnIndex < currentInstructions!!.size) {
                                val currentTurn = currentInstructions!![currentTurnIndex]
                                val turnLat = currentTurn.points.getLat(0)
                                val turnLon = currentTurn.points.getLon(0)
                                val results = FloatArray(1)
                                android.location.Location.distanceBetween(userLoc.latitude, userLoc.longitude, turnLat, turnLon, results)
                                val distanceToTurn = results[0]

                                maneuverDistanceText = if (currentTurn.sign == com.graphhopper.util.Instruction.FINISH) { "" } else if (distanceToTurn > 1000) { String.format(java.util.Locale.US, "In %.1f km", distanceToTurn / 1000) } else { "In ${distanceToTurn.toInt()} m" }

                                if (distanceToTurn in 100.0f..250.0f && !hasGivenAdvanceWarning) {
                                    if (!isVoiceMuted) textToSpeech?.speak("In $maneuverDistanceText, $upcomingManeuver", android.speech.tts.TextToSpeech.QUEUE_ADD, null, null)
                                    hasGivenAdvanceWarning = true
                                }
                                if (distanceToTurn < 35.0f) {
                                    currentTurnIndex++
                                    hasGivenAdvanceWarning = false
                                    if (currentTurnIndex < currentInstructions!!.size) {
                                        upcomingManeuver = generateManeuverText(currentInstructions!![currentTurnIndex], dbHelper)
                                        if (!isVoiceMuted) textToSpeech?.speak(upcomingManeuver, android.speech.tts.TextToSpeech.QUEUE_ADD, null, null)
                                    }
                                }
                            }
                        }
                    }
                    delay(1000L)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LaunchedEffect(upcomingManeuver, maneuverDistanceText) {
                    if (isNavigating) {
                        val intent = android.content.Intent(context, NavigationService::class.java).apply {
                            action = "UPDATE_TURN"
                            putExtra("maneuver", upcomingManeuver)
                            putExtra("distance", maneuverDistanceText)
                        }
                        context.startService(intent)
                    }
                }

                LaunchedEffect(upcomingManeuver, isNavigating) {
                    if (isNavigating && upcomingManeuver.isNotEmpty()) {
                        showTurnBanner = true
                        delay(5000L)
                        showTurnBanner = false
                    } else {
                        showTurnBanner = false
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            getMapAsync { map ->
                                mapLibreMap = map
                                map.setPrefetchesTiles(true)
                                map.addOnMapClickListener { latLng ->
                                    if (isNavigating) return@addOnMapClickListener false
                                    val screenPoint = map.projection.toScreenLocation(latLng)
                                    val touchBox = android.graphics.RectF(screenPoint.x - 30, screenPoint.y - 30, screenPoint.x + 30, screenPoint.y + 30)
                                    val altFeatures = map.queryRenderedFeatures(touchBox, "alt-route-layer")
                                    if (altFeatures.isNotEmpty() && activeOnlineRoute?.altRoute != null) {
                                        triggerHapticAlert(ctx)
                                        val currentPrimary = activeOnlineRoute!!
                                        val currentAlt = currentPrimary.altRoute!!
                                        val newOnlineRoute = OnlineRouteData(
                                            pathCoords = currentAlt.pathCoords, distKm = currentAlt.distKm, travelTimeSeconds = currentAlt.travelTimeSeconds, turns = currentAlt.turns,
                                            altRoute = AltRouteData(pathCoords = currentPrimary.pathCoords, distKm = currentPrimary.distKm, travelTimeSeconds = currentPrimary.travelTimeSeconds, turns = currentPrimary.turns)
                                        )
                                        activeOnlineRoute = newOnlineRoute
                                        currentRouteCoords = newOnlineRoute.pathCoords
                                        currentOnlineTurns = newOnlineRoute.turns
                                        distanceText = String.format(java.util.Locale.US, "%.1f km", newOnlineRoute.distKm)
                                        etaDetails = formatTomTomETA(newOnlineRoute.travelTimeSeconds)
                                        upcomingManeuver = newOnlineRoute.turns.firstOrNull()?.instruction ?: "Follow the highlighted route"

                                        routeElevations = generateElevationsForPoints(newOnlineRoute.pathCoords.size, newOnlineRoute.distKm)

                                        val newPrimaryPoints = newOnlineRoute.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                        val newPrimaryGeoJson = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(newPrimaryPoints)))
                                        currentRouteGeoJson = newPrimaryGeoJson
                                        val newAltPoints = newOnlineRoute.altRoute!!.pathCoords.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                                        val newAltGeoJson = org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(newAltPoints)))
                                        map.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("my-route-source")?.setGeoJson(newPrimaryGeoJson)
                                        map.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(newAltGeoJson)
                                        android.widget.Toast.makeText(ctx, "Route Swapped", android.widget.Toast.LENGTH_SHORT).show()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                val localMapFile = java.io.File(ctx.getExternalFilesDir(null), activeMapFileName)
                                try {
                                    if (localMapFile.exists() && !isOnline(ctx)) {
                                        map.setStyle(Style.Builder().fromJson(offlineStyleJson))
                                    } else {
                                        map.setStyle(Style.Builder().fromJson(onlineStyleJson))
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("OfflineNav", "Failed to load online style", e)
                                    if (localMapFile.exists()) map.setStyle(Style.Builder().fromJson(offlineStyleJson))
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                AnimatedVisibility(
                    visible = !isNavigating,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically(initialOffsetY = { -it }, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().shadow(8.dp, CircleShape),
                            shape = CircleShape,
                            color = if (isNightMode) Color(0xFF2A2A2A).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)
                        ) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { text ->
                                    searchQuery = text
                                    isSearchActive = text.isNotEmpty()
                                    if (text.isNotEmpty()) {
                                        coroutineScope.launch {
                                            val loc = mapLibreMap?.locationComponent?.lastKnownLocation
                                            searchResults = searchPlacesHybrid(text, dbHelper, loc?.latitude, loc?.longitude)
                                        }
                                    } else {
                                        searchResults = emptyList()
                                    }
                                },
                                placeholder = { Text("Search destination", color = Color.Gray) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = if (isNightMode) Color.White else Color.Black,
                                    unfocusedTextColor = if (isNightMode) Color.White else Color.Black
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (searchResults.isNotEmpty()) {
                                            triggerRouteCalculation(searchResults.first().first, searchResults.first().second)
                                        }
                                    }
                                )
                            )
                        }

                        if (!isSearchActive && !routeDrawn) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                items(quickBookmarks.size) { index ->
                                    val bookmark = quickBookmarks[index]
                                    Button(
                                        onClick = { triggerRouteCalculation(bookmark.first, bookmark.second) },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isNightMode) Color(0xFF2A2A2A) else Color.White),
                                        elevation = ButtonDefaults.buttonElevation(4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = bookmark.first, color = if (isNightMode) Color.White else Color.Black)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                                                contentDescription = "Delete Bookmark",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp).clickable {
                                                    dbHelper.deleteBookmark(bookmark.first)
                                                    quickBookmarks = dbHelper.getBookmarks()
                                                    android.widget.Toast.makeText(context, "Bookmark removed", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("car" to "🚗 Car", "bike" to "🏍️ Bike", "foot" to "🚶 Walk").forEach { (mode, label) ->
                                    Surface(
                                        shape = CircleShape,
                                        color = if (travelMode == mode) Color(0xFF4A89F3).copy(alpha = 0.9f) else (if (isNightMode) Color(0xFF2A2A2A).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)),
                                        modifier = Modifier.clickable {
                                            travelMode = mode
                                            if (routeDrawn && selectedDestination != null) triggerRouteCalculation(searchQuery, selectedDestination!!)
                                        }.shadow(2.dp, CircleShape)
                                    ) {
                                        Text(text = label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 14.sp, color = if (travelMode == mode) Color.White else (if (isNightMode) Color.White else Color.Black))
                                    }
                                }
                            }
                        }

                        if (isSearchActive && searchResults.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = if (isNightMode) Color(0xFF2A2A2A).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f)),
                                elevation = CardDefaults.cardElevation(8.dp)
                            ) {
                                searchResults.forEach { (name, coordinates) ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = name, modifier = Modifier.weight(1f).clickable { triggerRouteCalculation(name, coordinates) }, color = if (isNightMode) Color.White else Color.Black)
                                        IconButton(onClick = {
                                            dbHelper.addBookmark(name, coordinates.first, coordinates.second)
                                            quickBookmarks = dbHelper.getBookmarks()
                                            android.widget.Toast.makeText(context, "Saved to Bookmarks!", android.widget.Toast.LENGTH_SHORT).show()
                                        }) { Icon(painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_input_add), contentDescription = "Save Bookmark", tint = Color(0xFF4A89F3)) }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showTurnBanner,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically(initialOffsetY = { -it }, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
                ) {
                    Surface(modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth().shadow(12.dp, CircleShape), color = Color(0xDD222222), shape = CircleShape) {
                        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_directions), contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                if (maneuverDistanceText.isNotEmpty()) Text(text = maneuverDistanceText, color = Color(0xFF4A89F3), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(text = upcomingManeuver, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (showInfoDialog) {
                    AlertDialog(
                        onDismissRequest = { showInfoDialog = false },
                        title = { Text(text = "App Info", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("• Bookmarks: Tap the blue '+' icon next to any search result to save it to your quick-access bookmarks.")
                                Text("• Offline Maps: Tap the map layers icon in the bottom right to download state maps for completely offline visual routing.")
                            }
                        },
                        confirmButton = { Button(onClick = { showInfoDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A89F3))) { Text("Got it", color = Color.White) } },
                        containerColor = if (isNightMode) Color(0xFF2A2A2A) else Color.White,
                        titleContentColor = if (isNightMode) Color.White else Color.Black,
                        textContentColor = if (isNightMode) Color.LightGray else Color.DarkGray
                    )
                }

// 1. Re-center FAB placed directly in BoxScope (Top-Right)
                if (isNavigating) {
                    FloatingActionButton(
                        onClick = { mapLibreMap?.locationComponent?.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING_GPS },
                        containerColor = Color.White.copy(alpha = 0.9f),
                        contentColor = Color(0xFF4A89F3),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 130.dp, end = 16.dp)
                    ) {
                        Icon(painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_mylocation), contentDescription = null)
                    }
                }

// 2. Control Tools Column (Bottom-Right)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = if (routeDrawn || isNavigating) 220.dp else 48.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FloatingActionButton(onClick = { showInfoDialog = true }, containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color(0xFF4A89F3), shape = CircleShape) {
                        Icon(painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_dialog_info), contentDescription = "App Info")
                    }
                    FloatingActionButton(onClick = { showMapManager = true }, containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color(0xFF4A89F3), shape = CircleShape) {
                        Icon(painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_mapmode), contentDescription = "Manage Maps")
                    }
                    FloatingActionButton(onClick = { isVoiceMuted = !isVoiceMuted }, containerColor = Color.White.copy(alpha = 0.9f), contentColor = if (isVoiceMuted) Color.Red else Color.Black, shape = CircleShape) {
                        Text(text = if (isVoiceMuted) "🔇" else "🔊", fontSize = 20.sp)
                    }
                    FloatingActionButton(onClick = { isNightMode = !isNightMode }, containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color.Black, shape = CircleShape) {
                        Text(text = if (isNightMode) "🌙" else "☀️", fontSize = 20.sp)
                    }
                }

                AnimatedVisibility(
                    visible = routeDrawn || isNavigating,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
                ) {
                    Surface(modifier = Modifier.fillMaxWidth(), color = if (isNightMode) Color(0xFF1E1E1E).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), shadowElevation = 24.dp) {
                        Column(modifier = Modifier.padding(32.dp).fillMaxWidth()) {
                            if (etaDetails.first.isNotEmpty()) {
                                Text(text = etaDetails.first, fontSize = 32.sp, fontWeight = FontWeight.Black, color = if (isNightMode) Color.White else Color.Black)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = "$distanceText • Arrival at ${etaDetails.second}", fontSize = 16.sp, color = Color.Gray, fontWeight = FontWeight.Normal)
                            }
                            Spacer(modifier = Modifier.height(28.dp))
                            ElevationProfileGraph(elevations = routeElevations)
                            Button(
                                onClick = {
                                    val serviceIntent = android.content.Intent(context, NavigationService::class.java)
                                    if (!isNavigating) {
                                        isNavigating = true
                                        triggerHapticAlert(context)
                                        if (!isVoiceMuted) textToSpeech?.speak("Starting route.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                                        NavigationService.activeRouteCoords = currentRouteCoords
                                        NavigationService.activeInstructions = currentInstructions
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            context.startForegroundService(serviceIntent)
                                        } else {
                                            context.startService(serviceIntent)
                                        }
                                        mapLibreMap?.locationComponent?.let { locComponent ->
                                            val loc = locComponent.lastKnownLocation
                                            if (loc != null) {
                                                val startingCamera = CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude)).zoom(15.5).tilt(60.0).build()
                                                mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(startingCamera), 1500)
                                                coroutineScope.launch {
                                                    delay(1500)
                                                    locComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING_GPS
                                                    locComponent.zoomWhileTracking(17.5)
                                                    locComponent.tiltWhileTracking(60.0)
                                                }
                                            } else {
                                                locComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING_GPS
                                                locComponent.zoomWhileTracking(17.5)
                                                locComponent.tiltWhileTracking(60.0)
                                            }
                                        }
                                    } else {
                                        isNavigating = false
                                        routeDrawn = false
                                        hasAnnouncedArrival = false
                                        selectedDestination = null
                                        searchQuery = ""
                                        currentRouteGeoJson = null
                                        currentRouteCoords = emptyList()
                                        activeOnlineRoute = null
                                        routeElevations = emptyList()
                                        mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("my-route-source")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                                        mapLibreMap?.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("alt-route-source")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                                        mapLibreMap?.locationComponent?.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE_COMPASS
                                        mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(0.0).zoom(14.0).build()), 1000)
                                        serviceIntent.action = "STOP_SERVICE"
                                        context.startService(serviceIntent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = if (isNavigating) Color(0xFFE53935) else Color(0xFF4A89F3))
                            ) { Text(text = if (isNavigating) "Exit Navigation" else "Start Navigation", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                    }
                }

                if (showMapManager) {
                    androidx.activity.compose.BackHandler { showMapManager = false }
                    Spacer(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showMapManager = false })
                }

                AnimatedVisibility(
                    visible = showMapManager,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f), color = if (isNightMode) Color(0xFF1E1E1E).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), shadowElevation = 24.dp) {
                        Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
                            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                val lowerManeuver = upcomingManeuver.lowercase()
                                val (turnIcon, turnRotation) = when {
                                    lowerManeuver.contains("arrive") || lowerManeuver.contains("destination") -> Pair(Icons.Filled.Place, 0f)
                                    lowerManeuver.contains("roundabout") -> Pair(Icons.Filled.Refresh, 0f)
                                    lowerManeuver.contains("sharp right") -> Pair(Icons.Filled.ArrowUpward, 135f)
                                    lowerManeuver.contains("sharp left") -> Pair(Icons.Filled.ArrowUpward, -135f)
                                    lowerManeuver.contains("slight right") || lowerManeuver.contains("keep right") -> Pair(Icons.Filled.ArrowUpward, 45f)
                                    lowerManeuver.contains("slight left") || lowerManeuver.contains("keep left") -> Pair(Icons.Filled.ArrowUpward, -45f)
                                    lowerManeuver.contains("right") -> Pair(Icons.Filled.ArrowUpward, 90f)
                                    lowerManeuver.contains("left") -> Pair(Icons.Filled.ArrowUpward, -90f)
                                    else -> Pair(Icons.Filled.ArrowUpward, 0f)
                                }
                                Icon(imageVector = turnIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp).rotate(turnRotation))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    if (maneuverDistanceText.isNotEmpty()) Text(text = maneuverDistanceText, color = Color(0xFF4A89F3), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(text = upcomingManeuver, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(availableRegions.size) { index ->
                                    val region = availableRegions[index]
                                    Card(colors = CardDefaults.cardColors(containerColor = if (isNightMode) Color(0xFF2A2A2A) else Color(0xFFF5F5F5)), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = region.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (isNightMode) Color.White else Color.Black)
                                                if (region.isDownloaded) {
                                                    if (activeMapFileName == region.fileName) {
                                                        Text("Active", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                                    } else {
                                                        Button(onClick = { activeMapFileName = region.fileName; showMapManager = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) {
                                                            Text("Load Map", color = Color.White)
                                                        }
                                                    }
                                                } else if (region.isDownloading) {
                                                    Text("${(region.downloadProgress * 100).toInt()}%", color = Color(0xFF4A89F3), fontWeight = FontWeight.Bold)
                                                } else {
                                                    Button(
                                                        onClick = {
                                                            val request = DownloadManager.Request(android.net.Uri.parse(region.mapUrl))
                                                                .setTitle("Downloading ${region.name}")
                                                                .setDescription("Fetching map data...")
                                                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                                .setDestinationInExternalFilesDir(context, null, region.fileName)
                                                            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                                            android.widget.Toast.makeText(context, "Download started in background.", android.widget.Toast.LENGTH_LONG).show()
                                                            showMapManager = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A89F3))
                                                    ) { Text("Download", color = Color.White) }
                                                }
                                            }
                                            if (region.isDownloading) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                LinearProgressIndicator(progress = { region.downloadProgress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF4A89F3))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun searchPlacesHybrid(
        query: String,
        dbHelper: OfflineGeocoderDB,
        userLat: Double? = null,
        userLng: Double? = null
    ): List<Pair<String, Pair<Double, Double>>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cleanQuery = query.replace("near", "", ignoreCase = true).trim()
                val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                val viewboxParam = if (userLat != null && userLng != null) { "&viewbox=${userLng - 0.5},${userLat + 0.5},${userLng + 0.5},${userLat - 0.5}" } else ""
                val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&countrycodes=in$viewboxParam")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "OfflineNavApp/1.0")
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    val results = mutableListOf<Pair<String, Pair<Double, Double>>>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val fullName = obj.getString("display_name")
                        val shortName = fullName.split(",").take(3).joinToString(",")
                        results.add(shortName to Pair(obj.getString("lat").toDouble(), obj.getString("lon").toDouble()))
                    }
                    if (results.isNotEmpty()) return@withContext results
                }
            } catch (e: Exception) {}
            return@withContext dbHelper.searchPlaces(query, userLat, userLng)
        }
    }

    fun getSnappedLocation(currentLoc: android.location.Location, routeCoords: List<Pair<Double, Double>>): android.location.Location {
        if (routeCoords.size < 2) return currentLoc
        var minDistance = Float.MAX_VALUE
        var bestLat = currentLoc.latitude
        var bestLon = currentLoc.longitude
        val px = currentLoc.latitude
        val py = currentLoc.longitude
        for (i in 0 until routeCoords.size - 1) {
            val ax = routeCoords[i].first; val ay = routeCoords[i].second
            val bx = routeCoords[i + 1].first; val by = routeCoords[i + 1].second
            val l2 = Math.pow(bx - ax, 2.0) + Math.pow(by - ay, 2.0)
            if (l2 == 0.0) continue
            var t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2
            t = Math.max(0.0, Math.min(1.0, t))
            val projX = ax + t * (bx - ax); val projY = ay + t * (by - ay)
            val results = FloatArray(1)
            android.location.Location.distanceBetween(px, py, projX, projY, results)
            if (results[0] < minDistance) { minDistance = results[0]; bestLat = projX; bestLon = projY }
        }
        if (minDistance < 35.0f) {
            val snappedLoc = android.location.Location(currentLoc)
            snappedLoc.latitude = bestLat
            snappedLoc.longitude = bestLon
            return snappedLoc
        }
        return currentLoc
    }

    fun generateManeuverText(turn: com.graphhopper.util.Instruction, dbHelper: OfflineGeocoderDB, isFirstInstruction: Boolean = false): String {
        if (turn.sign == com.graphhopper.util.Instruction.FINISH) return "Arrive at destination"
        if (isFirstInstruction && turn.sign == com.graphhopper.util.Instruction.CONTINUE_ON_STREET) return "Proceed to your route"
        val landmark = dbHelper.getNearbyLandmark(turn.points.getLat(0), turn.points.getLon(0))
        val destinationText = if (landmark != null) "at $landmark" else "onto ${if (turn.name.isNullOrEmpty()) "the road" else turn.name}"
        return when (turn.sign) {
            com.graphhopper.util.Instruction.TURN_RIGHT -> "Turn right $destinationText"
            com.graphhopper.util.Instruction.TURN_LEFT -> "Turn left $destinationText"
            com.graphhopper.util.Instruction.TURN_SLIGHT_RIGHT -> "Keep right $destinationText"
            com.graphhopper.util.Instruction.TURN_SLIGHT_LEFT -> "Keep left $destinationText"
            com.graphhopper.util.Instruction.TURN_SHARP_RIGHT -> "Sharp right $destinationText"
            com.graphhopper.util.Instruction.TURN_SHARP_LEFT -> "Sharp left $destinationText"
            com.graphhopper.util.Instruction.CONTINUE_ON_STREET -> "Continue $destinationText"
            else -> "Head towards $destinationText"
        }
    }

    fun triggerHapticAlert(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(500)
    }

    fun getRemainingRouteDistance(currentLoc: android.location.Location, routeCoords: List<Pair<Double, Double>>): Float {
        if (routeCoords.isEmpty()) return 0f
        var minDistance = Float.MAX_VALUE
        var closestIdx = 0
        val results = FloatArray(1)
        for (i in routeCoords.indices) {
            android.location.Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, routeCoords[i].first, routeCoords[i].second, results)
            if (results[0] < minDistance) { minDistance = results[0]; closestIdx = i }
        }
        var remainingDistanceMeters = 0f
        if (closestIdx < routeCoords.size - 1) {
            android.location.Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, routeCoords[closestIdx + 1].first, routeCoords[closestIdx + 1].second, results)
            remainingDistanceMeters += results[0]
            for (i in closestIdx + 1 until routeCoords.size - 1) {
                android.location.Location.distanceBetween(routeCoords[i].first, routeCoords[i].second, routeCoords[i + 1].first, routeCoords[i + 1].second, results)
                remainingDistanceMeters += results[0]
            }
        } else { remainingDistanceMeters = minDistance }
        return remainingDistanceMeters / 1000f
    }

    fun getDistanceToRouteMeters(currentLoc: android.location.Location, routeCoords: List<Pair<Double, Double>>): Float {
        if (routeCoords.isEmpty()) return 0f
        var minDistance = Float.MAX_VALUE
        val results = FloatArray(1)
        for (coord in routeCoords) {
            android.location.Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, coord.first, coord.second, results)
            if (results[0] < minDistance) minDistance = results[0]
        }
        return minDistance
    }

    fun calculateETA(distanceKm: Float, travelMode: String): Pair<String, String> {
        if (distanceKm == 0f) return Pair("", "")
        val baseSpeed = when (travelMode) { "car" -> 40.0; "bike" -> 30.0; "foot" -> 5.0; else -> 30.0 }
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val trafficMultiplier = if (travelMode != "foot" && (currentHour in 8..10 || currentHour in 17..20)) 0.6 else 1.0
        val finalSpeed = baseSpeed * trafficMultiplier
        val timeMinutes = ((distanceKm / finalSpeed) * 60).toInt()
        val arrivalCalendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, timeMinutes) }
        val arrivalTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(arrivalCalendar.time)
        val duration = if (timeMinutes > 60) "${timeMinutes / 60} hr ${timeMinutes % 60} min" else "$timeMinutes min"
        return Pair(duration, arrivalTime)
    }

    suspend fun fetchOnlineRoute(
        startLat: Double, startLon: Double, destLat: Double, destLon: Double, travelMode: String
    ): OnlineRouteData? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mode = when (travelMode) { "foot" -> "pedestrian"; "bike" -> "motorcycle"; else -> "car" }
                val tomTomKey = "6sDGyGE1NhZkFs3fvRp36pXWClG1oJjp"
                val url = java.net.URL("https://api.tomtom.com/routing/1/calculateRoute/$startLat,$startLon:$destLat,$destLon/json?key=$tomTomKey&traffic=true&travelMode=$mode&instructionsType=text&maxAlternatives=1&alternativeType=anyRoute")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val routes = json.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val summary = route.getJSONObject("summary")
                        val distKm = (summary.getDouble("lengthInMeters") / 1000.0).toFloat()
                        val travelTimeSeconds = summary.getInt("travelTimeInSeconds")
                        val legs = route.getJSONArray("legs")
                        val pathCoords = mutableListOf<Pair<Double, Double>>()
                        for (i in 0 until legs.length()) {
                            val points = legs.getJSONObject(i).getJSONArray("points")
                            for (j in 0 until points.length()) {
                                val pt = points.getJSONObject(j)
                                pathCoords.add(Pair(pt.getDouble("latitude"), pt.getDouble("longitude")))
                            }
                        }
                        val turns = mutableListOf<NavTurn>()
                        if (route.has("guidance")) {
                            val instructions = route.getJSONObject("guidance").getJSONArray("instructions")
                            for (i in 0 until instructions.length()) {
                                val inst = instructions.getJSONObject(i)
                                val pt = inst.getJSONObject("point")
                                var cleanMsg = inst.getString("message")
                                if (cleanMsg.equals("Leave", ignoreCase = true)) cleanMsg = "Head towards the highlighted route."
                                cleanMsg = cleanMsg.replace("overpass", "flyover", ignoreCase = true).replace("motorway", "highway", ignoreCase = true)
                                if ((cleanMsg.contains("take the ramp", ignoreCase = true) || cleanMsg.contains("flyover", ignoreCase = true)) && !cleanMsg.startsWith("Attention", ignoreCase = true)) {
                                    cleanMsg = "Attention, $cleanMsg"
                                }
                                turns.add(NavTurn(pt.getDouble("latitude"), pt.getDouble("longitude"), cleanMsg, inst.optString("instructionType", "") == "ARRIVE"))
                            }
                        }
                        var altRouteData: AltRouteData? = null
                        if (routes.length() > 1) {
                            val altRouteObj = routes.getJSONObject(1)
                            val altSummary = altRouteObj.getJSONObject("summary")
                            val altDistKm = (altSummary.getDouble("lengthInMeters") / 1000.0).toFloat()
                            val altTravelTimeSeconds = altSummary.getInt("travelTimeInSeconds")
                            val altLegs = altRouteObj.getJSONArray("legs")
                            val altPathCoords = mutableListOf<Pair<Double, Double>>()
                            for (i in 0 until altLegs.length()) {
                                val points = altLegs.getJSONObject(i).getJSONArray("points")
                                for (j in 0 until points.length()) {
                                    val pt = points.getJSONObject(j)
                                    altPathCoords.add(Pair(pt.getDouble("latitude"), pt.getDouble("longitude")))
                                }
                            }
                            val altTurns = mutableListOf<NavTurn>()
                            if (altRouteObj.has("guidance")) {
                                val altInstructions = altRouteObj.getJSONObject("guidance").getJSONArray("instructions")
                                for (i in 0 until altInstructions.length()) {
                                    val inst = altInstructions.getJSONObject(i)
                                    val pt = inst.getJSONObject("point")
                                    var cleanMsg = inst.getString("message")
                                    if (cleanMsg.equals("Leave", ignoreCase = true)) cleanMsg = "Head towards the highlighted route."
                                    cleanMsg = cleanMsg.replace("overpass", "flyover", ignoreCase = true).replace("motorway", "highway", ignoreCase = true)
                                    altTurns.add(NavTurn(pt.getDouble("latitude"), pt.getDouble("longitude"), cleanMsg, inst.optString("instructionType", "") == "ARRIVE"))
                                }
                            }
                            altRouteData = AltRouteData(altPathCoords, altDistKm, altTravelTimeSeconds, altTurns)
                        }
                        return@withContext OnlineRouteData(pathCoords, distKm, travelTimeSeconds, turns, altRouteData)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            null
        }
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

data class RegionMap(val name: String, val mapUrl: String, val fileName: String, var isDownloaded: Boolean = false, var downloadProgress: Float = 0f, var isDownloading: Boolean = false)

suspend fun downloadRegionData(context: Context, region: RegionMap, onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = java.net.URL(region.mapUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connect()
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) { onComplete(false); return@withContext }
            val fileLength = connection.contentLength
            val file = java.io.File(context.filesDir, region.fileName)
            val input = java.io.BufferedInputStream(url.openStream())
            val output = java.io.FileOutputStream(file)
            val data = ByteArray(1024)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) onProgress((total.toFloat() / fileLength.toFloat()))
            }
            output.flush(); output.close(); input.close()
            onComplete(true)
        } catch (e: Exception) { onComplete(false) }
    }
}

@Composable
fun DownloadMapButton() {
    val context = LocalContext.current
    Button(onClick = {
        val request = DownloadManager.Request(Uri.parse("https://huggingface.co/datasets/EKLAVYA369/offline-india-map/resolve/main/india.mbtiles?download=true"))
            .setTitle("Downloading Offline Map").setDescription("Fetching map data for India...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "india.mbtiles")
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }) { Text("Download India Map") }
}

fun formatTomTomETA(travelTimeSeconds: Int): Pair<String, String> {
    val totalMinutes = travelTimeSeconds / 60
    val duration = if (totalMinutes >= 60) {
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        if (mins > 0) "$hours hr $mins min" else "$hours hr"
    } else "$totalMinutes min"
    val arrivalCalendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, travelTimeSeconds) }
    val arrivalTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(arrivalCalendar.time)
    return Pair(duration, arrivalTime)
}

@Composable
fun ElevationProfileGraph(elevations: List<Double>) {
    if (elevations.isEmpty() || elevations.all { it == 0.0 }) return
    val maxEle = elevations.maxOrNull() ?: 1.0
    val minEle = elevations.minOrNull() ?: 0.0
    val range = (maxEle - minEle).coerceAtLeast(1.0)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "Peak Altitude: ${maxEle.toInt()}m", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End))
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)) {
            val path = androidx.compose.ui.graphics.Path()
            val stepX = size.width / (elevations.size - 1).coerceAtLeast(1)
            elevations.forEachIndexed { index, ele ->
                val x = index * stepX
                val y = size.height - (((ele - minEle) / range) * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = Color(0xFF4CAF50), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

fun generateElevationsForPoints(pointCount: Int, distanceKm: Float): List<Double> {
    return emptyList()
}

data class AltRouteData(val pathCoords: List<Pair<Double, Double>>, val distKm: Float, val travelTimeSeconds: Int, val turns: List<NavTurn>)
data class OnlineRouteData(val pathCoords: List<Pair<Double, Double>>, val distKm: Float, val travelTimeSeconds: Int, val turns: List<NavTurn>, val altRoute: AltRouteData? = null)
data class NavTurn(val lat: Double, val lon: Double, val instruction: String, val isFinish: Boolean = false)