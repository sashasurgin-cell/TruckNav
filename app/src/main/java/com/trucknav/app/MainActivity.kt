package com.trucknav.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private enum class TruckNavTab { MAP, ROUTE, SERVICES, SAVED, VEHICLE }

@Composable
private fun TruckNavApp() {
    val context = LocalContext.current
    var russian by remember { mutableStateOf(Locale.getDefault().language == "ru") }
    var truckMode by remember { mutableStateOf(false) }
    var vehicle by remember { mutableStateOf("Cargo van") }
    var destination by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(TruckNavTab.MAP) }
    var showVehicleSettings by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(41.8781, -87.6298), 10.5f)
    }

    val vehicleNames = if (russian) {
        listOf("Грузовой фургон", "Пикап + прицеп", "Бокc-трак", "Седельный тягач")
    } else {
        listOf("Cargo van", "Pickup + trailer", "Box truck", "Semi truck")
    }
    val selectedVehicleName = when (vehicle) {
        "Pickup + trailer" -> vehicleNames[1]
        "Box truck" -> vehicleNames[2]
        "Semi truck" -> vehicleNames[3]
        else -> vehicleNames[0]
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == TruckNavTab.MAP,
                        onClick = { selectedTab = TruckNavTab.MAP; showVehicleSettings = false },
                        icon = { Icon(Icons.Default.Map, contentDescription = null) },
                        label = { Text(if (russian) "Карта" else "Map") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TruckNavTab.ROUTE,
                        onClick = { selectedTab = TruckNavTab.ROUTE; showVehicleSettings = false },
                        icon = { Icon(Icons.Default.Route, contentDescription = null) },
                        label = { Text(if (russian) "Маршрут" else "Route") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TruckNavTab.SERVICES,
                        onClick = { selectedTab = TruckNavTab.SERVICES; showVehicleSettings = false },
                        icon = { Icon(Icons.Default.LocalGasStation, contentDescription = null) },
                        label = { Text(if (russian) "Сервисы" else "Services") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TruckNavTab.SAVED,
                        onClick = { selectedTab = TruckNavTab.SAVED; showVehicleSettings = false },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                        label = { Text(if (russian) "Избранное" else "Saved") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TruckNavTab.VEHICLE,
                        onClick = { selectedTab = TruckNavTab.VEHICLE; showVehicleSettings = true },
                        icon = {
                            Icon(
                                if (truckMode) Icons.Default.LocalShipping else Icons.Default.DirectionsCar,
                                contentDescription = null
                            )
                        },
                        label = { Text(if (russian) "Транспорт" else "Vehicle") }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = hasLocationPermission,
                        compassEnabled = true,
                        mapToolbarEnabled = false
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("TruckNav", style = MaterialTheme.typography.headlineSmall)
                                    Text(
                                        if (russian) "Навигация для США" else "Navigation for the USA",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = { russian = !russian }) {
                                    Text(if (russian) "EN" else "RU")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !truckMode,
                                    onClick = { truckMode = false; message = "" },
                                    label = { Text(if (russian) "Легковой" else "Car") },
                                    leadingIcon = { Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )
                                FilterChip(
                                    selected = truckMode,
                                    onClick = { truckMode = true; message = "" },
                                    label = { Text(if (russian) "Грузовой" else "Truck") },
                                    leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = destination,
                                    onValueChange = { destination = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    placeholder = { Text(if (russian) "Куда едем?" else "Where to?") }
                                )
                                IconButton(
                                    onClick = {
                                        if (destination.isBlank()) {
                                            message = if (russian) "Сначала введи адрес назначения." else "Enter a destination first."
                                        } else if (truckMode) {
                                            message = if (russian) {
                                                "Грузовая маршрутизация ещё подключается. Не используй автомобильный маршрут для грузовика."
                                            } else {
                                                "Truck routing is not connected yet. Do not use a car route for a truck."
                                            }
                                        } else {
                                            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                                            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}"))
                                                context.startActivity(fallback)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Route, contentDescription = if (russian) "Построить маршрут" else "Build route")
                                }
                            }
                        }
                    }

                    if (!hasLocationPermission) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null)
                                Text(
                                    if (russian) "Разреши доступ к местоположению для GPS." else "Allow location access to use GPS.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                }) {
                                    Text(if (russian) "Разрешить" else "Allow")
                                }
                            }
                        }
                    }

                    if (message.isNotBlank()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (selectedTab != TruckNavTab.MAP || showVehicleSettings) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (selectedTab) {
                                TruckNavTab.ROUTE -> {
                                    Text(if (russian) "Маршрут" else "Route", style = MaterialTheme.typography.titleLarge)
                                    Text(if (russian) "Введи пункт назначения в строке поиска сверху. Для легкового режима маршрут откроется в Google Maps." else "Enter a destination above. In Car mode, directions open in Google Maps.")
                                }
                                TruckNavTab.SERVICES -> {
                                    Text(if (russian) "Сервисы для водителя" else "Driver services", style = MaterialTheme.typography.titleLarge)
                                    Text(if (russian) "На следующем этапе подключим парковки грузовиков, весовые станции, заправки и ремонтные сервисы." else "Next we will connect truck parking, weigh stations, fuel stops and repair services.")
                                }
                                TruckNavTab.SAVED -> {
                                    Text(if (russian) "Избранное" else "Saved places", style = MaterialTheme.typography.titleLarge)
                                    Text(if (russian) "Сохранённые места появятся в следующем обновлении." else "Saved places will be added in a later update.")
                                }
                                TruckNavTab.VEHICLE -> {
                                    Text(if (russian) "Профиль транспорта" else "Vehicle profile", style = MaterialTheme.typography.titleLarge)
                                    Text(if (russian) "Выбери тип транспорта для будущей грузовой маршрутизации." else "Choose a vehicle type for future truck routing.")
                                    vehicleNames.forEachIndexed { index, name ->
                                        val ids = listOf("Cargo van", "Pickup + trailer", "Box truck", "Semi truck")
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(name, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { vehicle = ids[index]; truckMode = true }) {
                                                Text(if (vehicle == ids[index]) "✓" else if (russian) "Выбрать" else "Select")
                                            }
                                        }
                                    }
                                    Text(
                                        if (russian) "Размеры и вес добавим в расширенные настройки профиля." else "Dimensions and weight will be added to advanced profile settings.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TruckNavTab.MAP -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
