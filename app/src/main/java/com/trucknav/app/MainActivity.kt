package com.trucknav.app

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TruckNavApp() }
    }
}

private enum class AppTab { MAP, ROUTE, POI, SAVED, VEHICLE }

private data class LatLng(val latitude: Double, val longitude: Double)

private suspend fun geocodeDestination(query: String): LatLng? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        connection = (URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&countrycodes=us")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "TruckNav-Android/1.0 (route planning)")
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val arr = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        if (arr.length() == 0) return@withContext null
        val item = arr.getJSONObject(0)
        LatLng(item.getDouble("lat"), item.getDouble("lon"))
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun decodePolyline6(encoded: String): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    var index = 0
    var lat = 0
    var lon = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val deltaLat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        lat += deltaLat

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val deltaLon = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        lon += deltaLon
        points.add(GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0))
    }
    return points
}

private suspend fun fetchRoadRoute(
    start: LatLng,
    end: LatLng,
    heightFt: Double,
    widthFt: Double,
    lengthFt: Double,
    weightLb: Double,
    axles: Int,
    avoidTolls: Boolean,
    avoidFerries: Boolean,
    avoidHighways: Boolean,
    avoidUnpaved: Boolean
): Pair<List<GeoPoint>, String>? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val truckOptions = JSONObject().apply {
            put("height", (heightFt * 0.3048).coerceAtLeast(0.5))
            put("width", (widthFt * 0.3048).coerceAtLeast(0.5))
            put("length", (lengthFt * 0.3048).coerceAtLeast(1.0))
            put("weight", (weightLb / 2204.6226218).coerceAtLeast(0.1))
            put("axle_count", axles.coerceIn(2, 20))
            put("axle_load", ((weightLb / 2204.6226218) / axles.coerceIn(2, 20)).coerceIn(1.0, 9.07))
            put("use_tolls", if (avoidTolls) 0.0 else 0.5)
            put("use_ferry", if (avoidFerries) 0.0 else 0.5)
            put("use_highways", if (avoidHighways) 0.0 else 0.8)
            put("exclude_unpaved", if (avoidUnpaved) 1 else 0)
            put("hgv_no_access_penalty", 43200)
        }
        val payload = JSONObject().apply {
            put("locations", org.json.JSONArray().put(
                JSONObject().put("lat", start.latitude).put("lon", start.longitude)
            ).put(
                JSONObject().put("lat", end.latitude).put("lon", end.longitude)
            ))
            put("costing", "truck")
            put("costing_options", JSONObject().put("truck", truckOptions))
            put("units", "miles")
            put("language", "en-US")
            put("alternates", 0)
        }
        connection = (URL("https://valhalla1.openstreetmap.de/route").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("User-Agent", "TruckNav-Android/0.14 (Android navigation app)")
            setRequestProperty("X-Client-Id", "TruckNav-Android")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = responseStream?.bufferedReader()?.use { it.readText() } ?: return@withContext null
        if (responseCode !in 200..299) return@withContext null
        val root = JSONObject(body)
        val trip = root.optJSONObject("trip") ?: return@withContext null
        val legs = trip.optJSONArray("legs") ?: return@withContext null
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until legs.length()) {
            val shape = legs.getJSONObject(i).optString("shape")
            if (shape.isNotBlank()) points.addAll(decodePolyline6(shape))
        }
        val summary = trip.optJSONObject("summary") ?: return@withContext null
        val miles = summary.optDouble("length", 0.0)
        val minutes = summary.optDouble("time", 0.0) / 60.0
        if (points.size < 2 || miles <= 0.0) return@withContext null
        Pair(points, String.format(Locale.US, "%.1f mi • %.0f min", miles, minutes))
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private data class TruckPoi(val id: Long, val name: String, val latitude: Double, val longitude: Double, val category: String)

private val poiCategories = listOf(
    "Truck parking", "Weigh stations", "Fuel / DEF", "Truck stops",
    "CAT scales", "Rest areas", "Repair shops", "Truck wash",
    "Low bridges", "Walmart", "Hotels", "Restaurants"
)

private fun overpassFilter(category: String, lat: Double, lon: Double): String {
    val area = "(around:25000,$lat,$lon)"
    val queries = when (category) {
        "Truck parking" -> """
            nwr$area["amenity"="parking"]["hgv"="yes"];
            nwr$area["parking"="truck"];
            nwr$area["name"~"truck parking",i];
        """.trimIndent()
        "Weigh stations" -> """
            nwr$area["highway"="weigh_station"];
            nwr$area["amenity"="weighbridge"];
        """.trimIndent()
        "Fuel / DEF" -> """nwr$area["amenity"="fuel"];"""
        "Truck stops" -> """
            nwr$area["name"~"truck stop|travel center|travel plaza",i];
            nwr$area["amenity"="fuel"]["hgv"="yes"];
        """.trimIndent()
        "CAT scales" -> """nwr$area["name"~"CAT Scale|CAT Scales",i];"""
        "Rest areas" -> """
            nwr$area["highway"="rest_area"];
            nwr$area["highway"="services"];
        """.trimIndent()
        "Repair shops" -> """
            nwr$area["shop"="truck_repair"];
            nwr$area["shop"="car_repair"];
            nwr$area["craft"="mechanic"];
        """.trimIndent()
        "Truck wash" -> """
            nwr$area["name"~"truck wash",i];
            nwr$area["amenity"="car_wash"]["hgv"="yes"];
        """.trimIndent()
        "Low bridges" -> """way$area["maxheight"];"""
        "Walmart" -> """nwr$area["name"~"Walmart",i];"""
        "Hotels" -> """
            nwr$area["tourism"="hotel"];
            nwr$area["tourism"="motel"];
        """.trimIndent()
        else -> """nwr$area["amenity"="restaurant"];"""
    }
    return "[out:json][timeout:20];($queries);out center tags 80;"
}

private suspend fun loadTruckPois(category: String, center: LatLng): List<TruckPoi> = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val query = overpassFilter(category, center.latitude, center.longitude)
        connection = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "TruckNav-Android/0.3")
        }
        val body = "data=" + java.net.URLEncoder.encode(query, "UTF-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) return@withContext emptyList()
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val elements = json.optJSONArray("elements") ?: return@withContext emptyList()
        val results = mutableListOf<TruckPoi>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val lat = if (element.has("lat")) element.optDouble("lat", Double.NaN)
                else element.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (element.has("lon")) element.optDouble("lon", Double.NaN)
                else element.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (!lat.isFinite() || !lon.isFinite()) continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: category
            results.add(TruckPoi(element.optLong("id", i.toLong()), name, lat, lon, category))
        }
        results.distinctBy { "${it.id}:${it.latitude}:${it.longitude}" }.take(80)
    } catch (_: Exception) {
        emptyList()
    } finally {
        connection?.disconnect()
    }
}


@Composable
private fun TruckNavApp() {
    val context = LocalContext.current
    var russian by remember { mutableStateOf(Locale.getDefault().language == "ru") }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var destination by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val settingsPrefs = remember { context.getSharedPreferences("trucknav_settings", android.content.Context.MODE_PRIVATE) }
    var avoidTolls by remember { mutableStateOf(settingsPrefs.getBoolean("avoid_tolls", false)) }
    var avoidFerries by remember { mutableStateOf(settingsPrefs.getBoolean("avoid_ferries", false)) }
    var avoidHighways by remember { mutableStateOf(settingsPrefs.getBoolean("avoid_highways", false)) }
    var avoidUnpaved by remember { mutableStateOf(settingsPrefs.getBoolean("avoid_unpaved", true)) }
    var selectedPoi by remember { mutableStateOf("Truck stops") }
    var visiblePois by remember { mutableStateOf(emptyList<TruckPoi>()) }
    var poiLoading by remember { mutableStateOf(false) }
    var poiRefreshKey by remember { mutableStateOf(0) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapCenter by remember { mutableStateOf(LatLng(41.8781, -87.6298)) }
    var routePoints by remember { mutableStateOf(emptyList<GeoPoint>()) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeSummary by remember { mutableStateOf("") }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var hasCenteredOnLocation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var updateInProgress by remember { mutableStateOf(false) }
    var pendingInstallUri by remember { mutableStateOf<Uri?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            pendingInstallUri?.let { uri ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }.onFailure {
                    android.widget.Toast.makeText(context, if (russian) "Не удалось запустить установщик" else "Could not start installer", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            pendingInstallUri = null
        } else {
            android.widget.Toast.makeText(context, if (russian) "Разрешите установку приложений из TruckNav, чтобы продолжить" else "Allow installs from TruckNav to continue", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun installApk(uri: Uri) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = uri
            installPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        availableUpdate = checkForTruckNavUpdate(BuildConfig.VERSION_CODE)
    }
    var height by remember { mutableStateOf("13.6") }
    var width by remember { mutableStateOf("8.5") }
    var length by remember { mutableStateOf("71.0") }
    var weight by remember { mutableStateOf("36,287") }
    var axles by remember { mutableStateOf("5") }
    var vehicleType by remember { mutableStateOf("Semi truck") }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    androidx.compose.runtime.DisposableEffect(hasLocationPermission) {
        val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val point = LatLng(location.latitude, location.longitude)
                currentLocation = point
                if (!hasCenteredOnLocation) {
                    mapCenter = point
                    mapView?.controller?.setZoom(15.0)
                    mapView?.controller?.setCenter(GeoPoint(point.latitude, point.longitude))
                    hasCenteredOnLocation = true
                }
            }
        }
        if (hasLocationPermission) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(listener::onLocationChanged)
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener)
                }
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(listener::onLocationChanged)
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, listener)
            } catch (_: SecurityException) { }
        }
        onDispose { try { manager.removeUpdates(listener) } catch (_: Exception) { } }
    }

    // Refresh POIs automatically when the selected category or map area changes.
    // A 0.1-degree grid avoids firing a request for every tiny map gesture.
    LaunchedEffect(
        selectedPoi,
        poiRefreshKey,
        String.format(Locale.US, "%.1f", mapCenter.latitude),
        String.format(Locale.US, "%.1f", mapCenter.longitude)
    ) {
        delay(900)
        while (true) {
            poiLoading = true
            val loaded = loadTruckPois(selectedPoi, mapCenter)
            visiblePois = loaded
            poiLoading = false
            statusMessage = if (loaded.isEmpty()) {
                if (russian) "Объекты не найдены или бесплатный источник временно недоступен." else "No places found, or the free data source is temporarily unavailable."
            } else {
                if (russian) "Найдено: ${loaded.size}. Источник: OpenStreetMap; полнота данных зависит от региона." else "Found: ${loaded.size}. Source: OpenStreetMap; coverage varies by region."
            }
            // Periodic refresh while the app remains open; a new map area/category restarts this loop.
            delay(6 * 60 * 60 * 1000L)
        }
    }


    fun label(en: String, ru: String) = if (russian) ru else en

    fun calculateTruckRoute() {
        val start = currentLocation
        if (start == null) {
            statusMessage = label("Waiting for your GPS location. Enable Location and try again.", "Ожидаю GPS. Включи геолокацию и попробуй ещё раз.")
            if (!hasLocationPermission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (destination.isBlank()) {
            statusMessage = label("Enter a destination address or city.", "Введи адрес или город назначения.")
            return
        }
        routeLoading = true
        routePoints = emptyList()
        routeSummary = ""
        coroutineScope.launch {
            try {
                statusMessage = label("Finding destination…", "Ищу пункт назначения…")
                val end = geocodeDestination(destination)
                if (end == null) {
                    statusMessage = label("Destination not found. Try a full US address or city, state.", "Адрес не найден. Попробуй полный адрес или город и штат.")
                    return@launch
                }
                statusMessage = label("Calculating road route…", "Рассчитываю маршрут…")
                val parsedHeight = height.toDoubleOrNull() ?: 13.6
                val parsedWidth = width.toDoubleOrNull() ?: 8.5
                val parsedLength = length.toDoubleOrNull() ?: 71.0
                val parsedWeight = weight.replace(",", "").toDoubleOrNull() ?: 36287.0
                val parsedAxles = axles.toIntOrNull() ?: 5
                val result = fetchRoadRoute(
                    start, end, parsedHeight, parsedWidth, parsedLength, parsedWeight, parsedAxles,
                    avoidTolls, avoidFerries, avoidHighways, avoidUnpaved
                )
                if (result == null || result.first.size < 2) {
                    statusMessage = label("Could not calculate route. Check your internet and try again.", "Не удалось построить маршрут. Проверь интернет и попробуй ещё раз.")
                    return@launch
                }
                routePoints = result.first
                routeSummary = result.second
                mapView?.controller?.setCenter(GeoPoint(start.latitude, start.longitude))
                mapView?.controller?.setZoom(7.0)
                statusMessage = label("Route drawn. Important: this is a standard car route, not truck-safe navigation. Check truck restrictions before driving.", "Маршрут построен. Важно: это обычный автомобильный маршрут, не грузовая навигация. Перед поездкой проверь ограничения для грузовиков.")
                selectedTab = AppTab.MAP
            } catch (_: Exception) {
                statusMessage = label("Route calculation failed. Please try again.", "Не удалось рассчитать маршрут. Попробуй ещё раз.")
            } finally {
                routeLoading = false
            }
        }
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == AppTab.MAP,
                        onClick = { selectedTab = AppTab.MAP },
                        icon = { Icon(Icons.Default.Map, contentDescription = null) },
                        label = { Text(label("Map", "Карта")) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.ROUTE,
                        onClick = { selectedTab = AppTab.ROUTE },
                        icon = { Icon(Icons.Default.Route, contentDescription = null) },
                        label = { Text(label("Trip", "Маршрут")) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.POI,
                        onClick = { selectedTab = AppTab.POI },
                        icon = { Icon(Icons.Default.LocalGasStation, contentDescription = null) },
                        label = { Text("POI") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.SAVED,
                        onClick = { selectedTab = AppTab.SAVED },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                        label = { Text(label("Saved", "Избранное")) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.VEHICLE,
                        onClick = { selectedTab = AppTab.VEHICLE },
                        icon = { Icon(Icons.Default.LocalShipping, contentDescription = null) },
                        label = { Text(label("Truck", "Грузовик")) }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        Configuration.getInstance().load(ctx, android.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
                        Configuration.getInstance().userAgentValue = ctx.packageName
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(if (currentLocation != null) 15.0 else 9.5)
                            val initialCenter = currentLocation ?: mapCenter
                            controller.setCenter(GeoPoint(initialCenter.latitude, initialCenter.longitude))
                            val osmMap = this
                            addMapListener(object : MapListener {
                                override fun onScroll(event: ScrollEvent?): Boolean {
                                    mapCenter = LatLng(osmMap.mapCenter.latitude, osmMap.mapCenter.longitude)
                                    return true
                                }
                                override fun onZoom(event: ZoomEvent?): Boolean {
                                    mapCenter = LatLng(osmMap.mapCenter.latitude, osmMap.mapCenter.longitude)
                                    return true
                                }
                            })
                            mapView = this
                        }
                    },
                    update = { map ->
                        map.overlays.clear()
                        if (routePoints.size > 1) {
                            map.overlays.add(Polyline().apply {
                                setPoints(routePoints)
                                setColor(Color.rgb(25, 105, 220))
                                setWidth(9f)
                            })
                        }
                        visiblePois.forEach { poi ->
                            map.overlays.add(OsmMarker(map).apply {
                                position = GeoPoint(poi.latitude, poi.longitude)
                                title = poi.name
                                snippet = poi.category
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                            })
                        }
                        currentLocation?.let { location ->
                            map.overlays.add(OsmMarker(map).apply {
                                position = GeoPoint(location.latitude, location.longitude)
                                title = label("Your location", "Ваше местоположение")
                                snippet = label("GPS location", "По GPS")
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                            })
                        }
                        map.invalidate()
                    }
                )

                if (selectedTab == AppTab.MAP) {
                    var routePanelExpanded by remember { mutableStateOf(false) }
                    var poiPanelExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(5.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(25.dp))
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text("TruckNav", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(onClick = { settingsOpen = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = label("Settings", "Настройки"))
                                    }
                                    TextButton(onClick = { routePanelExpanded = !routePanelExpanded; poiPanelExpanded = false }) {
                                        Icon(Icons.Default.Route, contentDescription = null)
                                        Text(if (routePanelExpanded) "  −" else "  +")
                                    }
                                    TextButton(onClick = { poiPanelExpanded = !poiPanelExpanded; routePanelExpanded = false }) {
                                        Icon(Icons.Default.Tune, contentDescription = null)
                                        Text(if (poiPanelExpanded) "  −" else "  +")
                                    }
                                }
                                if (routePanelExpanded) {
                                    OutlinedTextField(
                                        value = destination,
                                        onValueChange = { destination = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        placeholder = { Text(label("Destination address or city", "Адрес или город назначения")) }
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { selectedTab = AppTab.ROUTE; calculateTruckRoute() }, modifier = Modifier.weight(1f)) {
                                            Text(label("Plan route", "Построить маршрут"))
                                        }
                                        TextButton(onClick = {
                                            if (!hasLocationPermission) {
                                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                            } else {
                                                currentLocation?.let { location ->
                                                    mapView?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
                                                    mapView?.controller?.setZoom(15.0)
                                                } ?: run {
                                                    statusMessage = label("Waiting for GPS signal…", "Ожидаю сигнал GPS…")
                                                }
                                            }
                                        }) { Icon(Icons.Default.MyLocation, contentDescription = label("My location", "Моё местоположение")); Text(label(" GPS", " GPS")) }
                                    }
                                }
                            }
                        }
                        if (poiPanelExpanded) {
                            Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(5.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(label("Truck objects", "Грузовые объекты"), fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        poiCategories.forEach { category ->
                                            FilterChip(
                                                selected = selectedPoi == category,
                                                onClick = { selectedPoi = category },
                                                label = { Text(when (category) {
                                                    "Truck parking" -> label("Parking", "Стоянки")
                                                    "Weigh stations" -> label("Weigh stations", "Весовые")
                                                    "Fuel / DEF" -> label("Fuel / DEF", "Топливо / DEF")
                                                    "Truck stops" -> label("Truck stops", "Трак-стопы")
                                                    "CAT scales" -> label("CAT scales", "CAT-весы")
                                                    "Rest areas" -> label("Rest areas", "Зоны отдыха")
                                                    "Repair shops" -> label("Repair", "Ремонт")
                                                    "Truck wash" -> label("Truck wash", "Мойки")
                                                    "Low bridges" -> label("Low bridges", "Низкие мосты")
                                                    "Walmart" -> "Walmart"
                                                    "Hotels" -> label("Hotels", "Отели")
                                                    else -> label("Restaurants", "Рестораны")
                                                }) }
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            if (poiLoading) label("Searching…", "Ищем…")
                                            else label("${visiblePois.size} places", "Объектов: ${visiblePois.size}"),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(onClick = { mapView?.let { mapCenter = LatLng(it.mapCenter.latitude, it.mapCenter.longitude) }; poiRefreshKey++ }) {
                                            Text(label("Search here", "Искать здесь"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        shape = RoundedCornerShape(22.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (selectedTab) {
                                AppTab.MAP -> Unit
                                AppTab.ROUTE -> {
                                    Text(label("Truck trip planner", "Планировщик грузового маршрута"), style = MaterialTheme.typography.titleLarge)
                                    OutlinedTextField(
                                        value = destination,
                                        onValueChange = { destination = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(label("Destination", "Пункт назначения")) }
                                    )
                                    Button(onClick = { calculateTruckRoute() }, enabled = !routeLoading, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (routeLoading) label("Calculating…", "Рассчитываем…") else label("Calculate truck route", "Рассчитать грузовой маршрут"))
                                    }
                                    if (routeSummary.isNotBlank()) Text(routeSummary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(label("The current route uses a standard car routing service and may ignore truck height, weight, length, and restricted roads. Do not rely on it as truck-safe navigation.", "Сейчас используется обычный автомобильный маршрут. Он может не учитывать высоту, вес, длину и запреты для грузовиков. Не считай его безопасной грузовой навигацией."), style = MaterialTheme.typography.bodySmall)
                                }
                                AppTab.POI -> {
                                    Text(label("Truck points of interest", "Грузовые объекты (POI)"), style = MaterialTheme.typography.titleLarge)
                                    Text(label("Choose categories to show on the map.", "Выбери категории для отображения на карте."), style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = { selectedTab = AppTab.MAP }, modifier = Modifier.fillMaxWidth()) { Text(label("Open map and search", "Открыть карту и искать")) }
                                    LazyColumn(modifier = Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(poiCategories) { category ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(category, modifier = Modifier.weight(1f))
                                                TextButton(onClick = { selectedPoi = category; selectedTab = AppTab.MAP }) {
                                                    Text(label("Show", "На карте"))
                                                }
                                            }
                                        }
                                    }
                                    Text(label("The category list is ready; live places, availability, reviews and prices require data integrations.", "Список категорий готов; реальные объекты, наличие мест, отзывы и цены требуют подключения источников данных."), style = MaterialTheme.typography.bodySmall)
                                }
                                AppTab.SAVED -> {
                                    Text(label("Saved places", "Избранное"), style = MaterialTheme.typography.titleLarge)
                                    Text(label("Saved truck stops, yards and customer locations will be added next.", "Сохранённые стоянки, базы и адреса клиентов добавим следующим этапом."))
                                }
                                AppTab.VEHICLE -> {
                                    Text(label("Truck profile", "Профиль грузовика"), style = MaterialTheme.typography.titleLarge)
                                    Text(label("Vehicle type", "Тип транспорта"))
                                    listOf("Cargo van", "Pickup + trailer", "Box truck", "Semi truck").forEach { type ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(label(type, when (type) {
                                                "Cargo van" -> "Грузовой фургон"
                                                "Pickup + trailer" -> "Пикап + прицеп"
                                                "Box truck" -> "Бокс-трак"
                                                else -> "Седельный тягач"
                                            }), modifier = Modifier.weight(1f))
                                            TextButton(onClick = { vehicleType = type }) {
                                                Text(if (vehicleType == type) "✓" else label("Select", "Выбрать"))
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(height, { height = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(label("Height (ft)", "Высота (ft)")) })
                                        OutlinedTextField(width, { width = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(label("Width (ft)", "Ширина (ft)")) })
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(length, { length = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(label("Length (ft)", "Длина (ft)")) })
                                        OutlinedTextField(weight, { weight = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(label("Weight (lb)", "Вес (lb)")) })
                                    }
                                    OutlinedTextField(axles, { axles = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label("Axles", "Количество осей")) })
                                    Button(onClick = {
                                        statusMessage = label("Truck profile values are in this session; persistent profiles are coming next.", "Параметры сохранены в текущем сеансе; постоянное сохранение добавим далее.")
                                    }, modifier = Modifier.fillMaxWidth()) { Text(label("Save truck profile", "Сохранить профиль")) }
                                }
                            }
                            if (statusMessage.isNotBlank()) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                    Text(statusMessage, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text(label("App settings", "Настройки приложения")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            label("Language", "Язык приложения"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (russian) "Русский" else "English",
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { russian = !russian }) {
                                Text(if (russian) "English" else "Русский")
                            }
                        }
                        Text(
                            label("App updates", "Обновление приложения"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (availableUpdate != null) label("Update available", "Доступно обновление") else label("Check for updates", "Проверить обновления"),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                enabled = !updateInProgress,
                                onClick = {
                                    coroutineScope.launch {
                                        updateInProgress = true
                                        try {
                                            val update = checkForTruckNavUpdate(BuildConfig.VERSION_CODE)
                                            availableUpdate = update
                                            if (update == null) {
                                                android.widget.Toast.makeText(context, label("TruckNav is up to date", "Установлена последняя версия TruckNav"), android.widget.Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }

                                            val manager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                                            val request = DownloadManager.Request(Uri.parse(update.apkUrl))
                                                .setTitle("TruckNav ${update.tagName}")
                                                .setDescription(label("Downloading update…", "Загрузка обновления…"))
                                                .setMimeType("application/vnd.android.package-archive")
                                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "trucknav-${update.tagName}.apk")
                                            val downloadId = manager.enqueue(request)
                                            android.widget.Toast.makeText(context, label("Downloading update…", "Загружаю обновление…"), android.widget.Toast.LENGTH_SHORT).show()

                                            var finished = false
                                            while (!finished) {
                                                delay(700)
                                                val query = DownloadManager.Query().setFilterById(downloadId)
                                                manager.query(query)?.use { cursor ->
                                                    if (cursor.moveToFirst()) {
                                                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                                        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                                        when (cursor.getInt(statusIndex)) {
                                                            DownloadManager.STATUS_SUCCESSFUL -> finished = true
                                                            DownloadManager.STATUS_FAILED -> {
                                                                val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                                                throw IllegalStateException("Download failed: $reason")
                                                            }
                                                        }
                                                    } else {
                                                        throw IllegalStateException("Download not found")
                                                    }
                                                }
                                            }
                                            val apkUri = manager.getUriForDownloadedFile(downloadId)
                                                ?: throw IllegalStateException("Downloaded APK URI unavailable")
                                            installApk(apkUri)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(
                                                context,
                                                label("Update failed. Check your internet connection and try again.", "Не удалось обновить приложение. Проверьте интернет и попробуйте ещё раз."),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            updateInProgress = false
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    if (updateInProgress) label("Downloading…", "Загрузка…")
                                    else if (availableUpdate != null) label("Update", "Обновить")
                                    else label("Check", "Проверить")
                                )
                            }
                        }
                        Text(
                            label("Route preferences", "Предпочтения маршрута"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Avoid toll roads", "Избегать платных дорог"), modifier = Modifier.weight(1f))
                            Switch(checked = avoidTolls, onCheckedChange = {
                                avoidTolls = it
                                settingsPrefs.edit().putBoolean("avoid_tolls", it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Avoid ferries", "Избегать паромов"), modifier = Modifier.weight(1f))
                            Switch(checked = avoidFerries, onCheckedChange = {
                                avoidFerries = it
                                settingsPrefs.edit().putBoolean("avoid_ferries", it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Avoid highways", "Избегать хайвеев"), modifier = Modifier.weight(1f))
                            Switch(checked = avoidHighways, onCheckedChange = {
                                avoidHighways = it
                                settingsPrefs.edit().putBoolean("avoid_highways", it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Avoid unpaved roads", "Избегать грунтовых дорог"), modifier = Modifier.weight(1f))
                            Switch(checked = avoidUnpaved, onCheckedChange = {
                                avoidUnpaved = it
                                settingsPrefs.edit().putBoolean("avoid_unpaved", it).apply()
                            })
                        }
                        Text(
                            label(
                                "These preferences are saved on this device. They will affect route calculation after the truck-routing engine is connected.",
                                "Настройки сохраняются на этом устройстве. На расчёт маршрута они начнут влиять после подключения движка грузовой навигации."
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { settingsOpen = false }) {
                        Text(label("Done", "Готово"))
                    }
                }
            )
        }
    }
}
