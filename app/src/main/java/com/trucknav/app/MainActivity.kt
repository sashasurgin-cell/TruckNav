package com.trucknav.app

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TruckNavApp() }
    }
}

private enum class AppTab { MAP, ROUTE, POI, SAVED, VEHICLE }

private val poiCategories = listOf(
    "Truck stops", "Truck parking", "Fuel / DEF", "Weigh stations",
    "CAT scales", "Rest areas", "Repair shops", "Truck wash",
    "Low bridges", "Walmart", "Hotels", "Restaurants"
)

@Composable
private fun TruckNavApp() {
    val context = LocalContext.current
    var russian by remember { mutableStateOf(Locale.getDefault().language == "ru") }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var destination by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var selectedPoi by remember { mutableStateOf("Truck stops") }
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

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(41.8781, -87.6298), 9.5f)
    }

    fun label(en: String, ru: String) = if (russian) ru else en

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
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = hasLocationPermission,
                        compassEnabled = true,
                        mapToolbarEnabled = false
                    )
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
                                        statusMessage = if (destination.isBlank()) label("Enter a destination to plan a trip.", "Введи пункт назначения для планирования.")
                                            else label("Truck routing provider is not connected yet.", "Движок грузовой маршрутизации ещё не подключён.")
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
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Parking", "Fuel", "Scales").forEach { category ->
                                        FilterChip(
                                            selected = selectedPoi == category,
                                            onClick = { selectedPoi = category },
                                            label = { Text(when (category) {
                                                "Parking" -> label("Parking", "Стоянки")
                                                "Fuel" -> label("Fuel", "Топливо")
                                                else -> label("Scales", "Весовые")
                                            }) }
                                        )
                                    }
                                }
                                Text(
                                    label("POI data will appear after the data provider is connected.", "Объекты появятся после подключения источника данных."),
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
                                    Button(onClick = {
                                        statusMessage = label(
                                            "The truck routing engine is not connected yet. No route has been calculated.",
                                            "Движок грузовой маршрутизации ещё не подключён. Маршрут не рассчитан."
                                        )
                                    }, modifier = Modifier.fillMaxWidth()) { Text(label("Calculate truck route", "Рассчитать грузовой маршрут")) }
                                    Text(label("Route calculation will use the selected truck profile and road restrictions.", "При расчёте будут учитываться профиль грузовика и ограничения дорог."), style = MaterialTheme.typography.bodySmall)
                                }
                                AppTab.POI -> {
                                    Text(label("Truck points of interest", "Грузовые объекты (POI)"), style = MaterialTheme.typography.titleLarge)
                                    Text(label("Choose categories to show on the map.", "Выбери категории для отображения на карте."), style = MaterialTheme.typography.bodyMedium)
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
