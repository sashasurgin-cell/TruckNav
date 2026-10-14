package com.trucknav.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TruckNavApp() }
    }
}

@Composable
private fun TruckNavApp() {
    var russian by remember { mutableStateOf(Locale.getDefault().language == "ru") }
    var vehicle by remember { mutableStateOf("Cargo van") }
    var height by remember { mutableStateOf("9.5") }
    var width by remember { mutableStateOf("6.8") }
    var length by remember { mutableStateOf("20") }
    var weight by remember { mutableStateOf("10000") }
    var destination by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val title = if (russian) "TruckNav" else "TruckNav"
    val subtitle = if (russian) "Навигация для коммерческого транспорта" else "Commercial vehicle navigation"
    val vehicleLabel = if (russian) "Тип автомобиля" else "Vehicle type"
    val destinationLabel = if (russian) "Куда едем?" else "Destination"
    val dimensions = if (russian) "Параметры автомобиля (футы / фунты)" else "Vehicle dimensions (feet / pounds)"
    val heightLabel = if (russian) "Высота" else "Height"
    val widthLabel = if (russian) "Ширина" else "Width"
    val lengthLabel = if (russian) "Длина" else "Length"
    val weightLabel = if (russian) "Полный вес" else "Gross weight"
    val buildRoute = if (russian) "Построить маршрут" else "Build route"
    val note = if (russian) "Начальная версия: маршрутная карта и расчёт грузового маршрута ещё не подключены." else "Starter version: map routing and truck restrictions are not connected yet."

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { russian = !russian }) {
                        Text(if (russian) "EN" else "RU")
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vehicleLabel, style = MaterialTheme.typography.titleMedium)
                        listOf("Cargo van", "Pickup + trailer", "Box truck", "Semi truck").forEach { option ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (russian) when (option) {
                                    "Cargo van" -> "Грузовой фургон"
                                    "Pickup + trailer" -> "Пикап с прицепом"
                                    "Box truck" -> "Бокc-трак"
                                    else -> "Седельный тягач"
                                } else option)
                                TextButton(onClick = { vehicle = option }) {
                                    Text(if (vehicle == option) "✓" else if (russian) "Выбрать" else "Select")
                                }
                            }
                        }
                    }
                }

                Text(dimensions, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text(heightLabel) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text(widthLabel) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = length, onValueChange = { length = it }, label = { Text(lengthLabel) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text(weightLabel) }, modifier = Modifier.weight(1f), singleLine = true)
                }

                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text(destinationLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    message = if (russian) {
                        "Параметры сохранены для профиля: $vehicle. Для маршрутизации требуется подключить карты и грузовой routing engine."
                    } else {
                        "Settings saved for profile: $vehicle. Maps and a truck routing engine are needed for navigation."
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(buildRoute)
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
