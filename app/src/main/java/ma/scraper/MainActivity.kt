package ma.scraper

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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
    private val carsState = mutableStateOf<List<Car>>(emptyList())

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
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startWsService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startWsService()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WillhabenListScreen(
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
private fun WillhabenListScreen(
    cars: List<Car>,
    onOpen: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "WillhabenScraper",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${cars.size} Anzeigen",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                top = 8.dp,      // 👈 Abstand zur oberen Kante
                bottom = 12.dp
            )
        ){
            items(cars, key = { it.id }) { car ->
                WillhabenRowCard(car = car, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun WillhabenRowCard(
    car: Car,
    onOpen: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(car.url) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FOTO LINKS (klein, wie Willhaben)
            AsyncImage(
                model = car.image,
                contentDescription = car.title,
                modifier = Modifier
                    .size(width = 96.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(10.dp))

            // INFOS RECHTS
            Column(modifier = Modifier.weight(1f)) {

                // Titel
                Text(
                    text = car.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // Specs-Zeile: EZ | KM | PS
                val spec = listOfNotNull(
                    car.year?.let { "${it} EZ" },
                    car.km?.let { "${formatNumber(it)} km" },
                    car.ps?.let { "${it} PS" }
                ).joinToString(" | ")

                if (spec.isNotBlank()) {
                    Text(
                        text = spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(2.dp))

                // Ort
                if (!car.location.isNullOrBlank()) {
                    Text(
                        text = car.location!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // PREIS RECHTS
            Text(
                text = car.priceEur?.let { "€ ${formatNumber(it)}" } ?: "Preis a.A.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatNumber(n: Int): String =
    NumberFormat.getIntegerInstance().format(n)
