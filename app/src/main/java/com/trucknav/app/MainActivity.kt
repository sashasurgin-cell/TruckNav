package com.trucknav.app

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
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

private data class TruckProfile(
    val heightFt: Double,
    val widthFt: Double,
    val lengthFt: Double,
    val weightLb: Double,
    val axles: Int
)

private fun parseTruckProfile(
    heightRaw: String,
    widthRaw: String,
    lengthRaw: String,
    weightRaw: String,
    axlesRaw: String
): TruckProfile? {
    val height = heightRaw.trim().replace(",", "").toDoubleOrNull() ?: return null
    val width = widthRaw.trim().replace(",", "").toDoubleOrNull() ?: return null
    val length = lengthRaw.trim().replace(",", "").toDoubleOrNull() ?: return null
    val weight = weightRaw.trim().replace(",", "").toDoubleOrNull() ?: return null
    val axles = axlesRaw.trim().toIntOrNull() ?: return null

    // Broad sanity bounds catch empty, mistyped, or unit-confused entries before routing.
    if (height !in 1.0..30.0 || width !in 1.0..20.0 ||
        length !in 1.0..200.0 || weight !in 1.0..300000.0 || axles !in 2..20
    ) return null

    return TruckProfile(height, width, length, weight, axles)
}

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
    avoidUnpaved: Boolean,
    language: String
): Triple<List<GeoPoint>, String, List<String>>? = withContext(Dispatchers.IO) {
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
            put("language", language)
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
        val directions = mutableListOf<String>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val shape = leg.optString("shape")
            if (shape.isNotBlank()) points.addAll(decodePolyline6(shape))
            val maneuvers = leg.optJSONArray("maneuvers")
            if (maneuvers != null) {
                for (j in 0 until maneuvers.length()) {
                    val maneuver = maneuvers.optJSONObject(j) ?: continue
                    val instruction = maneuver.optString("instruction").trim()
                    if (instruction.isNotBlank()) {
                        val distanceMiles = maneuver.optDouble("length", 0.0)
                        val distanceLabel = if (distanceMiles >= 0.1) String.format(Locale.US, " (%.1f mi)", distanceMiles) else ""
                        directions.add(instruction + distanceLabel)
                    }
                }
            }
        }
        val summary = trip.optJSONObject("summary") ?: return@withContext null
        val miles = summary.optDouble("length", 0.0)
        val minutes = summary.optDouble("time", 0.0) / 60.0
        if (points.size < 2 || miles <= 0.0) return@withContext null
        Triple(points, String.format(Locale.US, "%.1f mi • %.0f min", miles, minutes), directions)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun formatEta(minutesValue: Double, russian: Boolean): String {
    val minutes = minutesValue.coerceAtLeast(0.0).toInt()
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours > 0 && russian -> hours.toString() + " ч " + remainingMinutes + " мин"
        hours > 0 -> hours.toString() + " hr " + remainingMinutes + " min"
        russian -> remainingMinutes.toString() + " мин"
        else -> remainingMinutes.toString() + " min"
    }
}

private data class TruckPoi(val id: Long, val name: String, val latitude: Double, val longitude: Double, val category: String, val details: String = "")

@Volatile private var nationwideLowClearanceCache: List<TruckPoi>? = null


private class CurrentLocationDotOverlay(private val position: GeoPoint) : Overlay() {
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 66, 133, 244)
        style = Paint.Style.FILL
    }
    private val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val blueDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 133, 244)
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pixel = mapView.projection.toPixels(position, android.graphics.Point())
        val x = pixel.x.toFloat()
        val y = pixel.y.toFloat()
        canvas.drawCircle(x, y, 24f, haloPaint)
        canvas.drawCircle(x, y, 9f, whiteBorderPaint)
        canvas.drawCircle(x, y, 6.5f, blueDotPaint)
    }
}

private fun weighStationMarkerIcon(context: android.content.Context): BitmapDrawable {
    val width = 88
    val height = 104
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 64, 36) }
    canvas.drawCircle(width / 2f, 39f, 32f, circlePaint)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(width / 2f, 39f, 32f, outlinePaint)
    val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 43f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    canvas.drawText("⚖", width / 2f, 53f, symbolPaint)
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 64, 36) }
    val path = android.graphics.Path().apply {
        moveTo(width / 2f - 13f, 62f)
        lineTo(width / 2f + 13f, 62f)
        lineTo(width / 2f, 83f)
        close()
    }
    canvas.drawPath(path, pointPaint)
    return BitmapDrawable(context.resources, bitmap)
}


private fun lowBridgeMarkerIcon(context: android.content.Context): BitmapDrawable {
    val width = 88
    val height = 104
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 132, 24) }
    canvas.drawCircle(width / 2f, 39f, 32f, markerPaint)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(width / 2f, 39f, 32f, outlinePaint)
    val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 43f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    canvas.drawText("↕", width / 2f, 53f, symbolPaint)
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 132, 24) }
    val path = android.graphics.Path().apply {
        moveTo(width / 2f - 13f, 62f)
        lineTo(width / 2f + 13f, 62f)
        lineTo(width / 2f, 83f)
        close()
    }
    canvas.drawPath(path, pointPaint)
    return BitmapDrawable(context.resources, bitmap)
}

private val poiCategories = listOf(
    "Truck parking", "Weigh stations", "Fuel / DEF", "Truck stops",
    "CAT scales", "Rest areas", "Repair shops", "Tire shops",
    "Truck wash", "Low bridges", "Walmart", "Truck dealers",
    "DOT inspections", "Hotels", "Restaurants"
)

private fun overpassFilter(category: String, lat: Double, lon: Double): String {
    // Weigh stations are sparse; low-clearance restrictions are searched in a wider radius too.
    val radiusMeters = when (category) {
        "Weigh stations" -> 100000
        "Low bridges" -> 50000
        else -> 25000
    }
    val area = "(around:$radiusMeters,$lat,$lon)"
    val queries = when (category) {
        "Truck parking" -> """
            nwr$area["amenity"="parking"]["hgv"~"yes|designated|permissive"];
            nwr$area["parking"="truck"];
            nwr$area["name"~"truck parking|semi truck parking|overnight truck parking",i];
            nwr$area["highway"~"rest_area|services"];
        """.trimIndent()
        "Weigh stations" -> """
            nwr$area["highway"="weigh_station"];
            nwr$area["amenity"="weighbridge"];
            nwr$area["man_made"="weighbridge"];
            nwr$area["public_transport"="station"]["name"~"weigh",i];
            nwr$area["name"~"weigh[ -]?station|weigh[ -]?scale|weigh[ -]?scales|truck scale|truck inspection|commercial vehicle inspection|port of entry|ag inspection|agricultural inspection|DOT inspection|inspection station|scale house|port of entry",i];
            nwr$area["operator"~"DOT|Department of Transportation|Highway Patrol",i]["name"~"scale|inspection|weigh",i];
        """.trimIndent()
        "Fuel / DEF" -> """
            nwr$area["amenity"="fuel"];
            nwr$area["name"~"diesel|DEF|truck fuel",i];
        """.trimIndent()
        "Truck stops" -> """
            nwr$area["name"~"truck stop|travel center|travel plaza|travel centre|truckstop",i];
            nwr$area["amenity"="fuel"]["hgv"="yes"];
            nwr$area["brand"~"Love.?s|Pilot|Flying J|TA|Petro|Roady.?s|Kwik Trip",i];
        """.trimIndent()
        "CAT scales" -> """nwr$area["name"~"CAT Scale|CAT Scales|Cat Scale",i];"""
        "Rest areas" -> """
            nwr$area["highway"="rest_area"];
            nwr$area["highway"="services"];
            nwr$area["amenity"="parking"]["name"~"rest area|welcome center",i];
        """.trimIndent()
        "Repair shops" -> """
            nwr$area["shop"~"truck_repair|car_repair"];
            nwr$area["craft"="mechanic"];
            nwr$area["name"~"truck repair|diesel repair",i];
        """.trimIndent()
        "Tire shops" -> """
            nwr$area["shop"="tyres"];
            nwr$area["name"~"truck tire|truck tyre|tire center|tire shop",i];
        """.trimIndent()
        "Truck wash" -> """
            nwr$area["name"~"truck wash|Blue Beacon|trailer washout",i];
            nwr$area["amenity"="car_wash"]["hgv"="yes"];
        """.trimIndent()
        "Low bridges" -> """
            way$area["maxheight"];
            way$area["maxheight:physical"];
            way$area["maxheight:conditional"];
            nwr$area["barrier"="height_restrictor"];
        """.trimIndent()
        "Walmart" -> """nwr$area["name"~"Walmart",i];"""
        "Truck dealers" -> """
            nwr$area["shop"="truck"];
            nwr$area["name"~"truck dealer|truck sales|Freightliner|Kenworth|Peterbilt|International Trucks|Volvo Trucks|Mack Trucks",i];
        """.trimIndent()
        "DOT inspections" -> """
            nwr$area["name"~"DOT inspection|commercial vehicle inspection|weigh station",i];
            nwr$area["highway"="weigh_station"];
        """.trimIndent()
        "Hotels" -> """
            nwr$area["tourism"="hotel"];
            nwr$area["tourism"="motel"];
        """.trimIndent()
        else -> """nwr$area["amenity"="restaurant"];"""
    }
    val outputLimit = when (category) { "Weigh stations" -> 300; "Low bridges" -> 250; else -> 80 }
    return "[out:json][timeout:25];($queries);out center tags $outputLimit;"
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
            val details = if (category == "Low bridges") {
                val clearance = tags.optString("maxheight").ifBlank {
                    tags.optString("maxheight:physical").ifBlank { tags.optString("maxheight:conditional") }
                }
                when {
                    clearance.isNotBlank() -> "Clearance: $clearance"
                    tags.optString("barrier") == "height_restrictor" -> "Height restrictor; exact clearance unknown"
                    else -> "Height restriction recorded; exact clearance unknown"
                }
            } else ""
            results.add(TruckPoi(element.optLong("id", i.toLong()), name, lat, lon, category, details))
        }
        results.distinctBy { "${it.id}:${it.latitude}:${it.longitude}" }.take(
            when (category) { "Weigh stations" -> 300; "Low bridges" -> 250; else -> 80 }
        )
    } catch (_: Exception) {
        emptyList()
    } finally {
        connection?.disconnect()
    }
}



private suspend fun loadNationalLowClearancePois(
    context: android.content.Context,
    center: LatLng
): List<TruckPoi>? = withContext(Dispatchers.IO) {
    val cacheFile = java.io.File(context.cacheDir, "low-clearances.json")
    val cacheMaxAgeMs = 7L * 24L * 60L * 60L * 1000L
    var items = nationwideLowClearanceCache

    if (items == null) {
        if (!cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > cacheMaxAgeMs) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://github.com/sashasurgin-cell/TruckNav/releases/download/clearance-data/low-clearances.json").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 45000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TruckNav-Android/1.0 (low-clearance data)")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode in 200..299) {
                    val temp = java.io.File(context.cacheDir, "low-clearances.json.tmp")
                    temp.writeText(connection.inputStream.bufferedReader().use { it.readText() }, Charsets.UTF_8)
                    if (temp.length() > 20 && temp.renameTo(cacheFile)) {
                        // Keep the previous cache if the new download is empty or incomplete.
                    }
                }
            } catch (_: Exception) {
                // A cached snapshot remains usable if the network is unavailable.
            } finally {
                connection?.disconnect()
            }
        }

        if (!cacheFile.exists()) return@withContext null
        items = try {
            val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val features = root.optJSONArray("features") ?: return@withContext null
            val parsed = ArrayList<TruckPoi>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val lat = feature.optDouble("lat", Double.NaN)
                val lon = feature.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val height = feature.optString("height")
                val barrier = feature.optString("barrier")
                val details = when {
                    height.isNotBlank() -> "Clearance: $height"
                    barrier == "height_restrictor" -> "Height restrictor; exact clearance unknown"
                    else -> "Height restriction recorded; exact clearance unknown"
                }
                val osmId = feature.optString("id")
                val id = osmId.substringAfterLast('/').toLongOrNull() ?: i.toLong()
                parsed.add(
                    TruckPoi(
                        id = id,
                        name = feature.optString("name").ifBlank { "Low-clearance restriction" },
                        latitude = lat,
                        longitude = lon,
                        category = "Low bridges",
                        details = details
                    )
                )
            }
            parsed
        } catch (_: Exception) {
            return@withContext null
        }
        nationwideLowClearanceCache = items
    }

    val all = items ?: return@withContext null
    fun distanceMeters(a: LatLng, bLat: Double, bLon: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(bLat - a.latitude)
        val dLon = Math.toRadians(bLon - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(bLat)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
    all.asSequence()
        .filter { distanceMeters(center, it.latitude, it.longitude) <= 50000.0 }
        .sortedBy { distanceMeters(center, it.latitude, it.longitude) }
        .take(250)
        .toList()
}


private fun parseClearanceFeet(raw: String): Double? {
    val value = raw.trim().substringBefore("@").trim()
    if (value.isBlank() || value.equals("none", true) || value.equals("unknown", true)) return null

    val feetInches = Regex("""([0-9]+(?:\.[0-9]+)?)\s*'\s*([0-9]+(?:\.[0-9]+)?)?\s*"?""").find(value)
    if (feetInches != null) {
        val feet = feetInches.groupValues[1].toDoubleOrNull() ?: return null
        val inches = feetInches.groupValues[2].toDoubleOrNull() ?: 0.0
        return feet + inches / 12.0
    }

    val words = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(feet|foot|ft|inches|inch|in|meters|metres|meter|metre|m|cm)\b""", RegexOption.IGNORE_CASE).find(value)
    if (words != null) {
        val number = words.groupValues[1].toDoubleOrNull() ?: return null
        return when (words.groupValues[2].lowercase(Locale.US)) {
            "feet", "foot", "ft" -> number
            "inches", "inch", "in" -> number / 12.0
            "cm" -> number / 30.48
            else -> number * 3.280839895
        }
    }
    // OSM maxheight values without an explicit unit are metres.
    val numeric = Regex("""^([0-9]+(?:\.[0-9]+)?)$""").matchEntire(value)?.groupValues?.get(1)?.toDoubleOrNull()
    return numeric?.takeIf { it > 0.0 }?.times(3.280839895)
}

private suspend fun checkClearanceAlongRoute(
    context: android.content.Context,
    route: List<GeoPoint>,
    truckHeightFeet: Double
): List<String>? {
    if (route.size < 2) return emptyList()
    loadNationalLowClearancePois(context, LatLng(route.first().latitude, route.first().longitude))
    val all = nationwideLowClearanceCache ?: return null
    return withContext(Dispatchers.Default) {
        val minLat = route.minOf { it.latitude } - 0.003
        val maxLat = route.maxOf { it.latitude } + 0.003
        val minLon = route.minOf { it.longitude } - 0.004
        val maxLon = route.maxOf { it.longitude } + 0.004

        fun distanceToRouteMeters(lat: Double, lon: Double): Double {
            val cosLat = kotlin.math.cos(Math.toRadians(lat))
            val px = lon * 111320.0 * cosLat
            val py = lat * 110540.0
            var best = Double.MAX_VALUE
            for (i in 0 until route.size - 1) {
                val a = route[i]
                val b = route[i + 1]
                val ax = a.longitude * 111320.0 * cosLat
                val ay = a.latitude * 110540.0
                val bx = b.longitude * 111320.0 * cosLat
                val by = b.latitude * 110540.0
                val dx = bx - ax
                val dy = by - ay
                val denominator = dx * dx + dy * dy
                val t = if (denominator <= 0.0001) 0.0 else (((px - ax) * dx + (py - ay) * dy) / denominator).coerceIn(0.0, 1.0)
                val distance = kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (distance < best) best = distance
                if (best <= 25.0) break
            }
            return best
        }

        val nearRoute = all.asSequence()
            .filter { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }
            .map { poi -> poi to distanceToRouteMeters(poi.latitude, poi.longitude) }
            .filter { it.second <= 150.0 }
            .sortedBy { it.second }
            .toList()

        nearRoute.mapNotNull { (poi, distance) ->
            val raw = poi.details.substringAfter("Clearance:", "").trim()
            val clearance = parseClearanceFeet(raw)
            val distanceLabel = "${distance.toInt()} m"
            when {
                clearance == null -> "UNKNOWN: ${poi.name} — clearance not verified (${distanceLabel} from route)"
                clearance < truckHeightFeet + 0.5 -> {
                    val feet = clearance.toInt()
                    val inches = ((clearance - feet) * 12).toInt().coerceIn(0, 11)
                    "LOW: ${poi.name} — about ${feet} ft ${inches} in clearance vs truck ${truckHeightFeet} ft (${distanceLabel} from route)"
                }
                clearance < truckHeightFeet + 1.0 -> "CAUTION: ${poi.name} — only about ${"%.1f".format(Locale.US, clearance - truckHeightFeet)} ft margin (${distanceLabel} from route)"
                else -> null
            }
        }.distinct().take(8)
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
    var routeDestination by remember { mutableStateOf<LatLng?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeSummary by remember { mutableStateOf("") }
    var routeInstructions by remember { mutableStateOf(emptyList<String>()) }
    var routeClearanceWarnings by remember { mutableStateOf<List<String>?>(null) }
    var showClearanceConfirmation by remember { mutableStateOf(false) }
    var clearanceWarningConfirmed by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var selectedMapPoint by remember { mutableStateOf<LatLng?>(null) }
    var isNavigating by remember { mutableStateOf(false) }
    var currentInstructionIndex by remember { mutableStateOf(0) }
    var remainingDistanceMiles by remember { mutableStateOf(0.0) }
    var remainingTimeMinutes by remember { mutableStateOf(0.0) }
    var distanceToNextTurnMiles by remember { mutableStateOf(0.0) }
    var timeToNextTurnMinutes by remember { mutableStateOf(0.0) }
    var hasCenteredOnLocation by remember { mutableStateOf(false) }
    val speechEngine = remember(context) { TextToSpeech(context) { } }
    androidx.compose.runtime.DisposableEffect(speechEngine) {
        onDispose { speechEngine.stop(); speechEngine.shutdown() }
    }
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
    var height by remember { mutableStateOf(settingsPrefs.getString("truck_height_ft", "13.6") ?: "13.6") }
    var width by remember { mutableStateOf(settingsPrefs.getString("truck_width_ft", "8.5") ?: "8.5") }
    var length by remember { mutableStateOf(settingsPrefs.getString("truck_length_ft", "71.0") ?: "71.0") }
    var weight by remember { mutableStateOf(settingsPrefs.getString("truck_weight_lb", "36,287") ?: "36,287") }
    var axles by remember { mutableStateOf(settingsPrefs.getString("truck_axles", "5") ?: "5") }
    var vehicleType by remember { mutableStateOf(settingsPrefs.getString("truck_type", "Semi truck") ?: "Semi truck") }

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

    fun label(en: String, ru: String) = if (russian) ru else en

    LaunchedEffect(currentLocation, isNavigating, routePoints, routeInstructions, currentInstructionIndex, routeSummary) {
        if (!isNavigating) return@LaunchedEffect
        val location = currentLocation ?: return@LaunchedEffect
        if (routePoints.size < 2) return@LaunchedEffect
        mapView?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
        mapView?.controller?.setZoom(17.0)
        fun segmentMiles(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1; val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            return 3958.8 * 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
        }
        val segmentDistances = (0 until routePoints.lastIndex).map { segmentMiles(routePoints[it], routePoints[it + 1]) }
        val cumulativeMiles = DoubleArray(routePoints.size)
        for (i in segmentDistances.indices) cumulativeMiles[i + 1] = cumulativeMiles[i] + segmentDistances[i]
        val totalRouteMiles = cumulativeMiles.last().coerceAtLeast(0.01)
        var closestIndex = 0; var closestDistance = Double.MAX_VALUE
        routePoints.forEachIndexed { index, point ->
            val dLat = point.latitude - location.latitude
            val dLon = (point.longitude - location.longitude) * kotlin.math.cos(Math.toRadians(location.latitude))
            val distance = dLat * dLat + dLon * dLon
            if (distance < closestDistance) { closestDistance = distance; closestIndex = index }
        }
        val remainingMiles = (totalRouteMiles - cumulativeMiles[closestIndex]).coerceAtLeast(0.0)
        val summaryMatch = Regex("([0-9]+(?:\\.[0-9]+)?) mi .*?([0-9]+(?:\\.[0-9]+)?) min").find(routeSummary)
        val totalMinutes = summaryMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val summaryMiles = summaryMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        remainingDistanceMiles = remainingMiles
        remainingTimeMinutes = if (summaryMiles > 0.0) totalMinutes * remainingMiles / summaryMiles else 0.0
        if (routeInstructions.isNotEmpty()) {
            val estimated = ((closestIndex.toDouble() / (routePoints.size - 1)) * routeInstructions.size).toInt().coerceIn(0, routeInstructions.lastIndex)
            if (estimated > currentInstructionIndex) {
                currentInstructionIndex = estimated
                speechEngine.language = if (russian) Locale("ru", "RU") else Locale.US
                speechEngine.speak(routeInstructions[estimated], TextToSpeech.QUEUE_FLUSH, null, "trucknav-turn-$estimated")
            }
            val nextTurnPointIndex = (((currentInstructionIndex + 1).toDouble() / routeInstructions.size) * (routePoints.size - 1)).toInt().coerceIn(closestIndex, routePoints.lastIndex)
            distanceToNextTurnMiles = (cumulativeMiles[nextTurnPointIndex] - cumulativeMiles[closestIndex]).coerceAtLeast(0.0)
            timeToNextTurnMinutes = if (summaryMiles > 0.0 && totalMinutes > 0.0) distanceToNextTurnMiles * totalMinutes / summaryMiles else 0.0
        } else {
            distanceToNextTurnMiles = remainingMiles
            timeToNextTurnMinutes = remainingTimeMinutes
        }
    }

    // Automatic off-route recovery: two consecutive readings prevent GPS-jitter reroutes.
    LaunchedEffect(isNavigating) {
        var offRouteReadings = 0
        var rerouting = false
        while (isNavigating) {
            delay(5000)
            if (!isNavigating || rerouting) continue
            val location = currentLocation ?: continue
            val destinationPoint = routeDestination ?: continue
            val points = routePoints
            if (points.size < 2) continue
            var nearestMiles = Double.MAX_VALUE
            for (point in points) {
                val lat1 = Math.toRadians(location.latitude)
                val lat2 = Math.toRadians(point.latitude)
                val dLat = lat2 - lat1
                val dLon = Math.toRadians(point.longitude - location.longitude)
                val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
                val miles = 3958.8 * 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
                if (miles < nearestMiles) nearestMiles = miles
            }
            if (nearestMiles > 0.35) offRouteReadings++ else offRouteReadings = 0
            if (offRouteReadings < 2) continue
            val profile = parseTruckProfile(height, width, length, weight, axles)
            if (profile == null) {
                statusMessage = label("Off route. Check truck dimensions before recalculating.", "Вы отклонились от маршрута. Проверь габариты грузовика перед перестроением.")
                offRouteReadings = 0
                continue
            }
            rerouting = true
            offRouteReadings = 0
            routeLoading = true
            statusMessage = label("Off route — recalculating truck route…", "Маршрут изменён — перестраиваю грузовой маршрут…")
            try {
                val result = fetchRoadRoute(LatLng(location.latitude, location.longitude), destinationPoint,
                    profile.heightFt, profile.widthFt, profile.lengthFt, profile.weightLb, profile.axles,
                    avoidTolls, avoidFerries, avoidHighways, avoidUnpaved, if (russian) "ru-RU" else "en-US")
                if (isNavigating && result != null && result.first.size >= 2) {
                    routePoints = result.first
                    routeSummary = result.second
                    routeInstructions = result.third
                    currentInstructionIndex = 0
                    routeClearanceWarnings = checkClearanceAlongRoute(context, result.first, profile.heightFt)
                    clearanceWarningConfirmed = false
                    speechEngine.language = if (russian) Locale("ru", "RU") else Locale.US
                    speechEngine.speak(label("Route recalculated. Continue to the highlighted route.", "Маршрут перестроен. Продолжай по новому маршруту."), TextToSpeech.QUEUE_FLUSH, null, "trucknav-reroute")
                    statusMessage = when {
                        routeClearanceWarnings == null -> label("Route recalculated. Clearance data unavailable; verify posted signs.", "Маршрут перестроен. База высот недоступна — проверяй дорожные знаки.")
                        routeClearanceWarnings!!.isNotEmpty() -> label("Route recalculated with " + routeClearanceWarnings!!.size + " possible clearance warning(s). Check posted signs.", "Маршрут перестроен. Возможных предупреждений по высоте: " + routeClearanceWarnings!!.size + ". Проверяй знаки.")
                        else -> label("Route recalculated. Known bridge data may be incomplete; follow posted signs.", "Маршрут перестроен. Данные о мостах могут быть неполными — следи за знаками.")
                    }
                } else if (isNavigating) statusMessage = label("Could not recalculate route. Check GPS and internet; follow road signs safely.", "Не удалось перестроить маршрут. Проверь GPS и интернет; ориентируйся по дорожным знакам.")
            } catch (_: Exception) {
                if (isNavigating) statusMessage = label("Rerouting failed. Check internet and follow road signs.", "Не удалось перестроить маршрут. Проверь интернет и дорожные знаки.")
            } finally {
                routeLoading = false
                rerouting = false
            }
        }
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
            val loaded = if (selectedPoi == "Low bridges") {
                loadNationalLowClearancePois(context, mapCenter) ?: loadTruckPois(selectedPoi, mapCenter)
            } else {
                loadTruckPois(selectedPoi, mapCenter)
            }
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


    fun startNavigation() {
        val clearanceWarnings = routeClearanceWarnings
        if (!clearanceWarningConfirmed && (clearanceWarnings == null || clearanceWarnings.isNotEmpty())) {
            showClearanceConfirmation = true
            return
        }
        if (routePoints.size < 2) {
            statusMessage = label("Calculate a route first.", "Сначала построй маршрут.")
            return
        }
        if (currentLocation == null) {
            statusMessage = label("Waiting for GPS location.", "Ожидаю GPS.")
            if (!hasLocationPermission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        isNavigating = true
        currentInstructionIndex = 0
        selectedTab = AppTab.MAP
        mapView?.controller?.setZoom(17.0)
        mapView?.controller?.animateTo(GeoPoint(currentLocation!!.latitude, currentLocation!!.longitude))
        if (routeInstructions.isNotEmpty()) {
            speechEngine.language = if (russian) Locale("ru", "RU") else Locale.US
            speechEngine.speak(routeInstructions.first(), TextToSpeech.QUEUE_FLUSH, null, "trucknav-start")
        }
        statusMessage = label("Navigation started. Follow road signs; height data is incomplete.", "Навигация запущена. Соблюдай дорожные знаки: база ограничений высоты неполная.")
    }

    fun cancelNavigation() {
        isNavigating = false
        currentInstructionIndex = 0
        speechEngine.stop()
        routePoints = emptyList()
        routeDestination = null
        routeSummary = ""
        routeInstructions = emptyList()
        routeClearanceWarnings = null
        selectedMapPoint = null
        destination = ""
        statusMessage = label("Navigation cancelled.", "Навигация отменена.")
    }

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
        val truckProfile = parseTruckProfile(height, width, length, weight, axles)
        if (truckProfile == null) {
            statusMessage = label(
                "Check truck dimensions: enter numeric feet, pounds, and 2–20 axles.",
                "Проверь габариты: введи числовые значения в футах, фунтах и количество осей от 2 до 20."
            )
            selectedTab = AppTab.VEHICLE
            return
        }
        routeLoading = true
        routePoints = emptyList()
        routeSummary = ""
        routeInstructions = emptyList()
        routeClearanceWarnings = null
        clearanceWarningConfirmed = false
        coroutineScope.launch {
            try {
                statusMessage = label("Finding destination…", "Ищу пункт назначения…")
                val end = geocodeDestination(destination)
                if (end == null) {
                    statusMessage = label("Destination not found. Try a full US address or city, state.", "Адрес не найден. Попробуй полный адрес или город и штат.")
                    return@launch
                }
                statusMessage = label("Calculating road route…", "Рассчитываю маршрут…")
                val parsedHeight = truckProfile.heightFt
                val result = fetchRoadRoute(
                    start, end, truckProfile.heightFt, truckProfile.widthFt, truckProfile.lengthFt, truckProfile.weightLb, truckProfile.axles,
                    avoidTolls, avoidFerries, avoidHighways, avoidUnpaved,
                    if (russian) "ru-RU" else "en-US"
                )
                if (result == null || result.first.size < 2) {
                    statusMessage = label("Could not calculate route. Check your internet and try again.", "Не удалось построить маршрут. Проверь интернет и попробуй ещё раз.")
                    return@launch
                }
                routePoints = result.first
                routeDestination = end
                routeSummary = result.second
                routeInstructions = result.third
                statusMessage = label("Checking known low-clearance restrictions along route…", "Проверяю известные ограничения высоты вдоль маршрута…")
                routeClearanceWarnings = checkClearanceAlongRoute(context, result.first, parsedHeight)
                mapView?.controller?.setCenter(GeoPoint(start.latitude, start.longitude))
                mapView?.controller?.setZoom(7.0)
                val warnings = routeClearanceWarnings
                statusMessage = when {
                    warnings == null -> label("Route calculated, but nationwide clearance data could not be loaded. Verify all posted signs.", "Маршрут построен, но национальную базу габаритов загрузить не удалось. Проверяй все дорожные знаки.")
                    warnings.isEmpty() -> label("No known low-clearance warnings found near this route. This does NOT guarantee every bridge is safe; verify posted signs.", "В базе не найдено известных предупреждений о низких мостах вдоль маршрута. Это НЕ гарантирует безопасный проезд каждого моста — проверяй дорожные знаки.")
                    else -> label("Route check found ${warnings.size} potential clearance warning(s). Review the list and verify posted signs.", "Проверка маршрута нашла предупреждения по высоте: ${warnings.size}. Посмотри список и обязательно сверяйся с дорожными знаками.")
                }
                selectedTab = AppTab.MAP
            } catch (_: Exception) {
                statusMessage = label("Route calculation failed. Please try again.", "Не удалось рассчитать маршрут. Попробуй ещё раз.")
            } finally {
                routeLoading = false
            }
        }
    }


    fun calculateRouteToMapPoint(point: LatLng) {
        val start = currentLocation
        if (start == null) {
            statusMessage = label("Waiting for your GPS location. Enable Location and try again.", "Ожидаю GPS. Включи геолокацию и попробуй ещё раз.")
            if (!hasLocationPermission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val truckProfile = parseTruckProfile(height, width, length, weight, axles)
        if (truckProfile == null) {
            statusMessage = label(
                "Check truck dimensions: enter numeric feet, pounds, and 2–20 axles.",
                "Проверь габариты: введи числовые значения в футах, фунтах и количество осей от 2 до 20."
            )
            selectedTab = AppTab.VEHICLE
            return
        }
        routeLoading = true
        routePoints = emptyList()
        routeSummary = ""
        routeInstructions = emptyList()
        routeClearanceWarnings = null
        clearanceWarningConfirmed = false
        coroutineScope.launch {
            try {
                statusMessage = label("Calculating truck route to selected point…", "Рассчитываю грузовой маршрут до выбранной точки…")
                val parsedHeight = truckProfile.heightFt
                val result = fetchRoadRoute(
                    start, point, truckProfile.heightFt, truckProfile.widthFt, truckProfile.lengthFt, truckProfile.weightLb, truckProfile.axles,
                    avoidTolls, avoidFerries, avoidHighways, avoidUnpaved,
                    if (russian) "ru-RU" else "en-US"
                )
                if (result == null || result.first.size < 2) {
                    statusMessage = label("Could not calculate route. Check your internet and try again.", "Не удалось построить маршрут. Проверь интернет и попробуй ещё раз.")
                    return@launch
                }
                routePoints = result.first
                routeDestination = point
                routeSummary = result.second
                routeInstructions = result.third
                statusMessage = label("Checking known low-clearance restrictions along route…", "Проверяю известные ограничения высоты вдоль маршрута…")
                routeClearanceWarnings = checkClearanceAlongRoute(context, result.first, parsedHeight)
                mapView?.controller?.setCenter(GeoPoint(start.latitude, start.longitude))
                mapView?.controller?.setZoom(7.0)
                val warnings = routeClearanceWarnings
                statusMessage = when {
                    warnings == null -> label("Route calculated, but nationwide clearance data could not be loaded. Verify all posted signs.", "Маршрут построен, но национальную базу габаритов загрузить не удалось. Проверяй все дорожные знаки.")
                    warnings.isEmpty() -> label("Route calculated: ${result.second}. No known low-clearance warnings found; this does NOT guarantee every bridge is safe.", "Маршрут построен: ${result.second}. Известных предупреждений о низких мостах не найдено, но это НЕ гарантирует безопасный проезд каждого моста.")
                    else -> label("Route calculated: ${result.second}. Review ${warnings.size} clearance warning(s) and posted signs.", "Маршрут построен: ${result.second}. Проверь предупреждения по высоте: ${warnings.size}, и дорожные знаки.")
                }
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
                            setBuiltInZoomControls(true)
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
                        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                                if (point == null) return false
                                selectedMapPoint = LatLng(point.latitude, point.longitude)
                                statusMessage = label("Map point selected. Tap Route to point to build a truck route from your GPS location.", "Точка выбрана. Нажми «Маршрут до точки», чтобы построить грузовой маршрут от GPS.")
                                return true
                            }
                            override fun longPressHelper(point: GeoPoint?): Boolean = false
                        }))
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
                                snippet = when (poi.category) {
                                    "Weigh stations" -> label("Weigh station / inspection point — verify operating status and truck access", "Весовая / пункт контроля — проверь режим работы и доступ для грузовиков")
                                    "Low bridges" -> buildString {
                                        append(if (poi.details.isNotBlank()) poi.details else label("Height restriction; clearance unknown", "Ограничение высоты; габарит неизвестен"))
                                        append(" • ")
                                        append(label("Verify road signs before proceeding", "Перед проездом обязательно проверь дорожные знаки"))
                                    }
                                    else -> poi.category
                                }
                                if (poi.category == "Weigh stations") icon = weighStationMarkerIcon(context)
                                if (poi.category == "Low bridges") icon = lowBridgeMarkerIcon(context)
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                            })
                        }
                        selectedMapPoint?.let { point ->
                            map.overlays.add(OsmMarker(map).apply {
                                position = GeoPoint(point.latitude, point.longitude)
                                title = label("Selected destination", "Выбранная точка назначения")
                                snippet = label("Tap Route to point below to calculate a truck route", "Нажми «Маршрут до точки» ниже, чтобы рассчитать грузовой маршрут")
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                            })
                        }
                        currentLocation?.let { location ->
                            // Draw the GPS position directly on the map canvas so OSMDroid's
                            // default green pin can never replace the Google-style blue dot.
                            map.overlays.add(
                                CurrentLocationDotOverlay(GeoPoint(location.latitude, location.longitude))
                            )
                        }
                        map.invalidate()
                    }
                )

                // Keep the small auto-hiding OSMDroid +/- controls; add only a separate recenter target.
                if (selectedTab == AppTab.MAP) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 180.dp)
                    ) {
                        Surface(
                            modifier = Modifier.padding(start = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            IconButton(onClick = {
                                if (!hasLocationPermission) {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                } else {
                                    currentLocation?.let { location ->
                                        mapView?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
                                        mapView?.controller?.setZoom(16.0)
                                    } ?: run {
                                        statusMessage = label("Waiting for GPS signal…", "Ожидаю сигнал GPS…")
                                    }
                                }
                            }) {
                                Icon(Icons.Default.MyLocation, contentDescription = label("My location", "Моё местоположение"))
                            }
                        }
                    }
                }

                if (isNavigating) {
                    Card(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(10.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label("NAVIGATION ACTIVE", "НАВИГАЦИЯ АКТИВНАЯ"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(label("To destination", "До пункта назначения"), style = MaterialTheme.typography.labelMedium)
                            Text(String.format(Locale.US, "%.1f mi • %s", remainingDistanceMiles, formatEta(remainingTimeMinutes, russian)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(label("Full route: ", "Весь маршрут: ") + routeSummary, style = MaterialTheme.typography.bodySmall)
                            if (routeInstructions.isNotEmpty()) {
                                Text(label("Next turn / exit", "Следующий поворот / съезд"), style = MaterialTheme.typography.labelMedium)
                                Text(routeInstructions[currentInstructionIndex.coerceIn(0, routeInstructions.lastIndex)], style = MaterialTheme.typography.titleMedium)
                                Text(String.format(Locale.US, "%.1f mi", distanceToNextTurnMiles) + " • " + formatEta(timeToNextTurnMinutes, russian) + " • " + label("approx.", "примерно"), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(label("Guidance is approximate; follow road signs and verify height/weight restrictions.", "Подсказки ориентировочные; соблюдай знаки и проверяй ограничения высоты и веса."), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { cancelNavigation() }, modifier = Modifier.fillMaxWidth()) {
                                Text(label("Cancel navigation", "Отменить навигацию"))
                            }
                        }
                    }
                } else if (selectedTab == AppTab.MAP) {
                    var routePanelExpanded by remember { mutableStateOf(false) }
                    var poiPanelExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                                    OutlinedTextField(
                                        value = destination,
                                        onValueChange = { destination = it },
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                        singleLine = true,
                                        shape = RoundedCornerShape(28.dp),
                                        placeholder = { Text(label("Search places or addresses", "Поиск мест и адресов")) },
                                        trailingIcon = {
                                            if (destination.isNotBlank()) {
                                                IconButton(onClick = { destination = "" }) {
                                                    Icon(Icons.Default.Tune, contentDescription = label("Clear search", "Очистить поиск"))
                                                }
                                            }
                                        }
                                    )
                                    IconButton(onClick = { settingsOpen = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = label("Settings", "Настройки"))
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = routePanelExpanded,
                                        onClick = {
                                            routePanelExpanded = !routePanelExpanded
                                            if (routePanelExpanded && destination.isNotBlank()) calculateTruckRoute()
                                        },
                                        label = { Text(label("Directions", "Маршрут")) },
                                        leadingIcon = { Icon(Icons.Default.Route, contentDescription = null) }
                                    )
                                    FilterChip(
                                        selected = poiPanelExpanded && selectedPoi == "Truck parking",
                                        onClick = { selectedPoi = "Truck parking"; poiPanelExpanded = true; routePanelExpanded = false },
                                        label = { Text(label("Parking", "Стоянки")) }
                                    )
                                    FilterChip(
                                        selected = poiPanelExpanded && selectedPoi == "Weigh stations",
                                        onClick = { selectedPoi = "Weigh stations"; poiPanelExpanded = true; routePanelExpanded = false },
                                        label = { Text(label("Weigh stations", "Весовые")) }
                                    )
                                    FilterChip(
                                        selected = poiPanelExpanded && selectedPoi == "Fuel / DEF",
                                        onClick = { selectedPoi = "Fuel / DEF"; poiPanelExpanded = true; routePanelExpanded = false },
                                        label = { Text("Fuel / DEF") }
                                    )
                                    FilterChip(
                                        selected = poiPanelExpanded && selectedPoi == "Truck stops",
                                        onClick = { selectedPoi = "Truck stops"; poiPanelExpanded = true; routePanelExpanded = false },
                                        label = { Text(label("Truck stops", "Трак-стопы")) }
                                    )
                                    IconButton(onClick = { poiPanelExpanded = !poiPanelExpanded; routePanelExpanded = false }) {
                                        Icon(Icons.Default.Tune, contentDescription = label("All map layers", "Все объекты карты"))
                                    }
                                }
                                if (routePanelExpanded) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(
                                            onClick = { calculateTruckRoute() },
                                            enabled = !routeLoading,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(if (routeLoading) label("Finding route…", "Строю маршрут…") else label("Build truck route", "Построить грузовой маршрут"))
                                        }
                                        IconButton(onClick = {
                                            if (!hasLocationPermission) {
                                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                            } else {
                                                currentLocation?.let { location ->
                                                    mapView?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
                                                    mapView?.controller?.setZoom(16.0)
                                                } ?: run {
                                                    statusMessage = label("Waiting for GPS signal…", "Ожидаю сигнал GPS…")
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.MyLocation, contentDescription = label("My location", "Моё местоположение"))
                                        }
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
                                                    "Weigh stations" -> label("⚖ Weigh stations", "⚖ Весовые")
                                                    "Fuel / DEF" -> label("Fuel / DEF", "Топливо / DEF")
                                                    "Truck stops" -> label("Truck stops", "Трак-стопы")
                                                    "CAT scales" -> label("CAT scales", "CAT-весы")
                                                    "Rest areas" -> label("Rest areas", "Зоны отдыха")
                                                    "Repair shops" -> label("Repair", "Ремонт")
                                                    "Truck wash" -> label("Truck wash", "Мойки")
                                                    "Tire shops" -> label("Tire shops", "Шиномонтаж")
                                                    "Low bridges" -> label("Low bridges", "Низкие мосты")
                                                    "Walmart" -> "Walmart"
                                                    "Truck dealers" -> label("Truck dealers", "Дилеры грузовиков")
                                                    "DOT inspections" -> label("DOT inspection", "DOT-контроль")
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
                    selectedMapPoint?.let { point ->
                        Card(
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                            shape = RoundedCornerShape(18.dp),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(label("Selected map point", "Выбранная точка на карте"), fontWeight = FontWeight.SemiBold)
                                Text(String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude), style = MaterialTheme.typography.bodySmall)
                                if (routeSummary.isNotBlank()) Text(routeSummary, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { calculateRouteToMapPoint(point) },
                                        enabled = !routeLoading,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (routeLoading) label("Calculating…", "Рассчитываю…") else label("Route to point", "Маршрут до точки"))
                                    }
                                    if (routePoints.size > 1 && !isNavigating) {
                                        Button(onClick = { startNavigation() }, modifier = Modifier.weight(1f)) {
                                            Text(label("Start", "Поехали"))
                                        }
                                    }
                                    TextButton(onClick = {
                                        selectedMapPoint = null
                                        statusMessage = ""
                                    }) { Text(label("Clear", "Убрать")) }
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
                                AppTab.MAP -> {
                                    routeClearanceWarnings?.let { warnings ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = if (warnings.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(
                                                    if (warnings.isEmpty()) label("Low-clearance check: no known conflicts found", "Проверка высоты: известных конфликтов не найдено") else label("Low-clearance warnings", "Предупреждения по высоте"),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                warnings.take(4).forEach { warning -> Text("⚠ $warning", style = MaterialTheme.typography.bodySmall) }
                                                if (warnings.size > 4) Text(label("Open Trip for more warnings.", "Открой вкладку «Маршрут» для остальных предупреждений."), style = MaterialTheme.typography.bodySmall)
                                                Text(label("Incomplete data — follow posted signs.", "Данные неполные — соблюдай дорожные знаки."), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
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
                                    if (routeSummary.isNotBlank()) {
                                        Text(routeSummary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Button(onClick = { startNavigation() }, enabled = !isNavigating, modifier = Modifier.fillMaxWidth()) {
                                            Text(label("Start navigation", "Начать навигацию"))
                                        }
                                    }
                                    routeClearanceWarnings?.let { warnings ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = if (warnings.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    if (warnings.isEmpty()) label("Low-clearance check: no known conflicts found", "Проверка высоты: известных конфликтов не найдено") else label("Low-clearance warnings", "Предупреждения по высоте"),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                warnings.forEach { warning -> Text("⚠ $warning", style = MaterialTheme.typography.bodySmall) }
                                                Text(label("Data is incomplete; warnings are advisory only. Follow posted signs.", "База неполная; предупреждения носят справочный характер. Соблюдай дорожные знаки."), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (routeInstructions.isNotEmpty()) {
                                        Text(label("Turn-by-turn directions", "Повороты по маршруту"), fontWeight = FontWeight.SemiBold)
                                        routeInstructions.take(14).forEachIndexed { index, instruction ->
                                            Text("${index + 1}. $instruction", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text(label("Truck routing uses Valhalla truck costing with your dimensions and weight. OpenStreetMap restrictions may be incomplete; verify critical bridge, weight and truck-access signs.", "Маршрут рассчитывается движком Valhalla в режиме грузовика с учётом заданных габаритов и массы. Данные OpenStreetMap могут быть неполными — обязательно проверяй критичные ограничения мостов, веса и въезда."), style = MaterialTheme.typography.bodySmall)
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
                                            TextButton(onClick = {
                                                vehicleType = type
                                                settingsPrefs.edit().putString("truck_type", type).apply()
                                            }) {
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
                                        val parsed = parseTruckProfile(height, width, length, weight, axles)
                                        if (parsed == null) {
                                            statusMessage = label(
                                                "Profile not saved. Check the numeric values and units.",
                                                "Профиль не сохранён. Проверь числовые значения и единицы измерения."
                                            )
                                        } else {
                                            settingsPrefs.edit()
                                                .putString("truck_height_ft", height.trim())
                                                .putString("truck_width_ft", width.trim())
                                                .putString("truck_length_ft", length.trim())
                                                .putString("truck_weight_lb", weight.trim())
                                                .putString("truck_axles", axles.trim())
                                                .putString("truck_type", vehicleType)
                                                .apply()
                                            statusMessage = label(
                                                "Truck profile saved on this device and will be used for the next route.",
                                                "Профиль грузовика сохранён на устройстве и будет использоваться при следующем расчёте маршрута."
                                            )
                                        }
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
        if (showClearanceConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearanceConfirmation = false },
                title = {
                    Text(label("Review truck-clearance warnings", "Проверь ограничения для грузовика"))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (routeClearanceWarnings == null) {
                                label(
                                    "Clearance data could not be loaded, so this route has not been checked against the clearance database. This app cannot guarantee the route is safe. Check official signs and do not proceed if clearance is insufficient.",
                                    "Не удалось загрузить базу ограничений высоты, поэтому маршрут не проверен по этой базе. Приложение не гарантирует безопасность маршрута. Проверяй официальные знаки и не продолжай движение, если высоты недостаточно."
                                )
                            } else {
                                label(
                                    "Known or unverified low-clearance restrictions were found near the route. This app cannot guarantee the route is safe. Check official signs and do not proceed if clearance is insufficient.",
                                    "Рядом с маршрутом найдены известные ограничения или участки с непроверенным габаритом. Приложение не гарантирует безопасность маршрута. Проверяй официальные знаки и не продолжай движение, если высоты недостаточно."
                                )
                            }
                        )
                        (routeClearanceWarnings ?: emptyList()).take(5).forEach { warning ->
                            Text("⚠ $warning", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            label(
                                "Starting navigation does not mean the route has been verified as safe.",
                                "Запуск навигации не означает, что безопасность маршрута подтверждена."
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearanceConfirmation = false
                        clearanceWarningConfirmed = true
                        startNavigation()
                    }) {
                        Text(label("I reviewed — continue", "Проверил — продолжить"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearanceConfirmation = false }) {
                        Text(label("Go back", "Вернуться"))
                    }
                }
            )
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
                                "These preferences are saved on this device and apply to the next route calculation.",
                                "Настройки сохраняются на устройстве и применяются при следующем расчёте маршрута."
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

// Restore auto-hiding map zoom buttons and keep recenter target [publish-v33]