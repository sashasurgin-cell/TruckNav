package com.trucknav.app

import android.Manifest
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.google.android.gms.maps.model.LatLng
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
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TruckNavApp() }
    }
}

private enum class AppTab { MAP, ROUTE, POI, SAVED, VEHICLE }

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


private data class TruckRouteResult(val points: List<GeoPoint>, val distanceMeters: Long, val durationSeconds: Long)

private fun decodeHerePolyline(encoded: String): List<GeoPoint> {
    var index = 0
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (index < encoded.length) {
            val chunk = encoded[index++].code - 63
            result = result or ((chunk and 31).toLong() shl shift)
            if (chunk < 32) return result
            shift += 5
            if (shift > 60) throw IllegalArgumentException("Invalid route geometry")
        }
        return result
    }
    fun signed(value: Long): Long = if ((value and 1L) != 0L) -(value shr 1) else value shr 1
    if (encoded.isEmpty()) return emptyList()
    if (readVarint() != 1L) throw IllegalArgumentException("Unsupported route geometry version")
    val precision = readVarint().toInt()
    val thirdDimension = readVarint()
    readVarint()
    val factor = Math.pow(10.0, precision.toDouble())
    var lat = 0L
    var lon = 0L
    val result = mutableListOf<GeoPoint>()
    while (index < encoded.length) {
        lat += signed(readVarint())
        lon += signed(readVarint())
        if (thirdDimension != 0L && index < encoded.length) readVarint()
        result.add(GeoPoint(lat / factor, lon / factor))
    }
    return result
}

private suspend fun calculateHereTruckRoute(
    apiKey: String,
    origin: LatLng,
    destination: String,
    heightFeet: Double,
    widthFeet: Double,
    lengthFeet: Double,
    grossWeightLb: Double,
    axleCount: Int
): TruckRouteResult = withContext(Dispatchers.IO) {
    fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TruckNav-Android/0.5")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val reason = error.optString("title", error.optString("cause", "HTTP $code"))
                throw IllegalStateException("HERE API error $code: $reason")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
    val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
    val geocodeUrl = "https://geocode.search.hereapi.com/v1/geocode?q=" +
        java.net.URLEncoder.encode(destination, "UTF-8") + "&in=countryCode:USA&apiKey=$key"
    val items = getJson(geocodeUrl).optJSONArray("items")
    if (items == null || items.length() == 0) throw IllegalStateException("Address not found. Enter a US address or city and state.")
    val position = items.getJSONObject(0).getJSONObject("position")
    val destLat = position.getDouble("lat")
    val destLon = position.getDouble("lng")
    val params = listOf(
        "transportMode" to "truck",
        "origin" to "${origin.latitude},${origin.longitude}",
        "destination" to "$destLat,$destLon",
        "routingMode" to "fast",
        "return" to "polyline,summary",
        "lang" to "en-US",
        "vehicle[height]" to (heightFeet * 30.48).toInt().toString(),
        "vehicle[width]" to (widthFeet * 30.48).toInt().toString(),
        "vehicle[length]" to (lengthFeet * 30.48).toInt().toString(),
        "vehicle[grossWeight]" to (grossWeightLb * 0.45359237).toInt().toString(),
        "vehicle[axleCount]" to axleCount.coerceIn(2, 20).toString()
    )
    val query = params.joinToString("&") { (name, value) ->
        java.net.URLEncoder.encode(name, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
    } + "&apiKey=$key"
    val routes = getJson("https://router.hereapi.com/v8/routes?$query").optJSONArray("routes")
    if (routes == null || routes.length() == 0) throw IllegalStateException("HERE returned no truck route for these locations and vehicle dimensions.")
    val sections = routes.getJSONObject(0).optJSONArray("sections") ?: throw IllegalStateException("No route sections returned.")
    val points = mutableListOf<GeoPoint>()
    var distance = 0L
    var duration = 0L
    for (i in 0 until sections.length()) {
        val section = sections.getJSONObject(i)
        val summary = section.optJSONObject("summary")
        if (summary != null) {
            distance += summary.optLong("length")
            duration += summary.optLong("duration")
        }
        val encoded = section.optString("polyline")
        if (encoded.isNotBlank()) points.addAll(decodeHerePolyline(encoded))
    }
    if (points.isEmpty()) throw IllegalStateException("HERE returned no route geometry.")
    TruckRouteResult(points, distance, duration)
}


@Composable
private fun TruckNavApp() {
    val context = LocalContext.current
    var russian by remember { mutableStateOf(Locale.getDefault().language == "ru") }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var destination by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var selectedPoi by remember { mutableStateOf("Truck stops") }
    var visiblePois by remember { mutableStateOf(emptyList<TruckPoi>()) }
    var poiLoading by remember { mutableStateOf(false) }
    var poiRefreshKey by remember { mutableStateOf(0) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapCenter by remember { mutableStateOf(LatLng(41.8781, -87.6298)) }
    var routePoints by remember { mutableStateOf(emptyList<GeoPoint>()) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeSummary by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var height by remember { mutableStateOf("13.6") }
    var width by remember { mutableStateOf("2.6") }
    var length by remember { mutableStateOf("16.2") }
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


    LaunchedEffect(selectedPoi, poiRefreshKey) {
        poiLoading = true
        visiblePois = loadTruckPois(selectedPoi, mapCenter)
        poiLoading = false
        statusMessage = if (visiblePois.isEmpty()) {
            if (russian) "В этой области объекты не найдены или источник данных временно недоступен." else "No places found in this area, or the data source is temporarily unavailable."
        } else {
            if (russian) "Найдено объектов: ${visiblePois.size}. Данные OpenStreetMap могут быть неполными." else "Found ${visiblePois.size} places. OpenStreetMap data may be incomplete."
        }
    }


    fun label(en: String, ru: String) = if (russian) ru else en

    fun calculateTruckRoute() {
        if (destination.isBlank()) {
            statusMessage = label("Enter a destination first.", "Сначала введи пункт назначения.")
            return
        }
        if (BuildConfig.HERE_API_KEY.isBlank()) {
            statusMessage = label(
                "Routing is wired into the app, but HERE_API_KEY is not configured. Create a HERE API key and add it as a GitHub Actions secret.",
                "Маршрутизация подключена в коде, но HERE_API_KEY не задан. Создай API-ключ HERE и добавь его в секреты GitHub Actions."
            )
            return
        }
        routeLoading = true
        routeSummary = ""
        statusMessage = label("Calculating truck route…", "Рассчитываем грузовой маршрут…")
        coroutineScope.launch {
            try {
                val route = calculateHereTruckRoute(
                    BuildConfig.HERE_API_KEY,
                    mapCenter,
                    destination,
                    height.replace(',', '.').toDouble(),
                    width.replace(',', '.').toDouble(),
                    length.replace(',', '.').toDouble(),
                    weight.replace(",", "").replace(" ", "").toDouble(),
                    axles.toInt()
                )
                routePoints = route.points
                mapView?.controller?.setCenter(route.points.first())
                mapView?.controller?.setZoom(8.0)
                routeSummary = String.format(Locale.US, "%.1f mi · %.1f hr", route.distanceMeters / 1609.344, route.durationSeconds / 3600.0)
                statusMessage = label(
                    "Route calculated. This is best-effort truck routing; follow posted signs and verify restrictions.",
                    "Маршрут рассчитан. Это расчёт по доступным данным; соблюдай дорожные знаки и проверяй ограничения."
                )
                selectedTab = AppTab.ROUTE
            } catch (e: Exception) {
                statusMessage = e.message ?: label("Could not calculate truck route.", "Не удалось рассчитать грузовой маршрут.")
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
                        Configuration.getInstance().userAgentValue = ctx.packageName
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(9.5)
                            controller.setCenter(GeoPoint(41.8781, -87.6298))
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
                                outlinePaint.color = Color.rgb(25, 105, 220)
                                outlinePaint.strokeWidth = 9f
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
                        map.invalidate()
                    }
                )

                if (selectedTab == AppTab.MAP) {
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            elevation = CardDefaults.cardElevation(5.dp)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(28.dp))
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text("TruckNav", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        Text(label("Truck GPS & driver POI", "Грузовая навигация США"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { russian = !russian }) {
                                        Text(if (russian) "EN" else "RU")
                                    }
                                }
                                OutlinedTextField(
                                    value = destination,
                                    onValueChange = { destination = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    placeholder = { Text(label("Address, city, truck stop…", "Адрес, город, truck stop…")) }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        selectedTab = AppTab.ROUTE
                                        calculateTruckRoute()
                                    }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Route, contentDescription = null)
                                        Text("  " + label("Plan truck route", "Построить маршрут"))
                                    }
                                    Button(onClick = {
                                        if (!hasLocationPermission) {
                                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                        }
                                    }) {
                                        Icon(Icons.Default.MyLocation, contentDescription = label("Location permission", "Доступ к GPS"))
                                    }
                                }
                            }
                        }
                        Card(shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label("Truck POI nearby", "Грузовые объекты рядом"), fontWeight = FontWeight.SemiBold)
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
                                        if (poiLoading) label("Searching…", "Ищем объекты…")
                                        else label("${visiblePois.size} places found", "Найдено объектов: ${visiblePois.size}"),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { mapView?.let { mapCenter = LatLng(it.mapCenter.latitude, it.mapCenter.longitude) }; poiRefreshKey++ }) { Text(label("Search area", "Искать здесь")) }
                                }
                                Text(
                                    label(
                                        "© OpenStreetMap contributors. Coverage varies by area; live parking availability and fuel prices are not included.",
                                        "© OpenStreetMap contributors. Покрытие зависит от района; наличие свободных мест и актуальные цены на топливо не предоставляются."
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                                    Text(label("HERE truck routing uses entered height, width, length, gross weight and axle count. It is not a guarantee of legal clearance.", "HERE учитывает высоту, ширину, длину, общий вес и число осей. Это не гарантия юридической проходимости маршрута."), style = MaterialTheme.typography.bodySmall)
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
    }
}
