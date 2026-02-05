package ma.scraper

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import ma.scraper.model.Car
import ma.scraper.ws.WsForegroundService
import java.text.NumberFormat

class MainActivity : ComponentActivity() {

    private var service: WsForegroundService? = null

    // Compose state
    private val carsState = mutableStateOf<List<Car>>(emptyList())

    // Listener wird vom Service immer auf Main Thread aufgerufen
    private val listener: (List<Car>) -> Unit = { list ->
        carsState.value = list
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WsForegroundService.LocalBinder).getService()
            service?.addListener(listener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeListener(listener)
            service = null
        }
    }

    private fun startWsService() {
        val i = Intent(this, WsForegroundService::class.java)
        ContextCompat.startForegroundService(this, i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // egal ob erlaubt oder nicht: Service starten
        startWsService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ Permission (optional – Service läuft auch ohne, nur Notifs ggf. stumm)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startWsService()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarListScreen(
                        cars = carsState.value,
                        onOpen = { url ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        service?.removeListener(listener)
        try { unbindService(conn) } catch (_: Throwable) {}
        super.onDestroy()
    }
}

@Composable
private fun CarListScreen(
    cars: List<Car>,
    onOpen: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "WillhabenScraper",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        Text(
            text = "Neue Autos: ${cars.size}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(cars, key = { it.id }) { car ->
                CarRow(car = car, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun CarRow(
    car: Car,
    onOpen: (String) -> Unit
) {
    Card(
        onClick = { onOpen(car.url) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {

            // Bild links
            AsyncImage(
                model = car.image,
                contentDescription = car.title,
                modifier = Modifier
                    .size(width = 110.dp, height = 86.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {

                // Titel
                Text(
                    text = car.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                // Preis fett
                Text(
                    text = car.priceEur?.let { "${formatNumber(it)} €" } ?: "Preis auf Anfrage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                // Ort + Jahr
                val placeYear = listOfNotNull(
                    car.location,
                    car.year?.let { "EZ $it" }
                ).joinToString(" · ")

                if (placeYear.isNotBlank()) {
                    Text(
                        text = placeYear,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(4.dp))

                // KM · PS · Getriebe · Kraftstoff
                val facts = listOfNotNull(
                    car.km?.let { "${formatNumber(it)} km" },
                    car.ps?.let { "$it PS" },
                    car.transmission,
                    car.fuel
                ).joinToString(" · ")

                if (facts.isNotBlank()) {
                    Text(
                        text = facts,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatNumber(n: Int): String =
    NumberFormat.getIntegerInstance().format(n)
