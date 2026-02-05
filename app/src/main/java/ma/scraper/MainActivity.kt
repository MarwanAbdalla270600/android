package ma.scraper

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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
    val listState = rememberLazyListState()

    // IDs die 5 Sekunden gelb sein sollen
    val highlighted = remember { mutableStateMapOf<String, Boolean>() }

    // Vorherige IDs merken, um neue zu erkennen
    val prevIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    // Update: neue IDs oben erkennen -> highlight + scrollTop
    LaunchedEffect(cars) {
        if (cars.isEmpty()) return@LaunchedEffect

        // Neue kommen bei dir vorne rein -> wir prüfen nur die Top 30
        val currentTopIds = cars.take(30).map { it.id }
        val old = prevIds.value

        val newIds = currentTopIds.filter { it !in old }

        // Nicht beim allerersten Laden scrollen
        if (newIds.isNotEmpty() && old.isNotEmpty()) {
            // Auto-Scroll nach oben
            listState.animateScrollToItem(0)

            // Alle neuen gelb markieren
            for (id in newIds) highlighted[id] = true

            // 5 Sekunden warten, dann Markierung entfernen
            delay(5000)
            for (id in newIds) highlighted.remove(id)
        }

        // prevIds updaten (mehr als 300 brauchst du nicht)
        prevIds.value = cars.take(300).map { it.id }.toSet()
    }

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

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
        ) {
            items(cars, key = { it.id }) { car ->
                WillhabenRowCard(
                    car = car,
                    onOpen = onOpen,
                    highlight = highlighted[car.id] == true
                )
            }
        }
    }
}

@Composable
private fun WillhabenRowCard(
    car: Car,
    onOpen: (String) -> Unit,
    highlight: Boolean
) {
    // Crash-Fix: niemals riesige Strings direkt rendern
    val title = safeText(car.title, 120)
    val location = safeText(car.location, 60)
    val fuel = safeText(car.fuel, 30)
    val transmission = safeText(car.transmission, 30)

    val bg by animateColorAsState(
        targetValue = if (highlight) Color(0xFFFFF3B0) else MaterialTheme.colorScheme.surface,
        label = "new-highlight"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(car.url) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Foto links
            AsyncImage(
                model = car.image,
                contentDescription = title,
                modifier = Modifier
                    .size(width = 96.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(10.dp))

            // Infos rechts
            Column(modifier = Modifier.weight(1f)) {

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // Specs: EZ | km | PS | Getriebe | Fuel
                val spec = safeText(
                    listOfNotNull(
                        car.year?.let { "${it} EZ" },
                        car.km?.let { "${formatNumber(it)} km" },
                        car.ps?.let { "${it} PS" },
                        transmission.takeIf { it.isNotBlank() },
                        fuel.takeIf { it.isNotBlank() }
                    ).joinToString(" | "),
                    160
                )

                if (spec.isNotBlank()) {
                    Text(
                        text = spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(2.dp))

                if (location.isNotBlank()) {
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            val priceText = safeText(
                car.priceEur?.let { "€ ${formatNumber(it)}" } ?: "Preis a.A.",
                30
            )

            Text(
                text = priceText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun safeText(s: String?, max: Int): String {
    if (s == null) return ""
    // Kontrollzeichen raus, trimmen, hart begrenzen
    val cleaned = s
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .trim()
    return if (cleaned.length <= max) cleaned else cleaned.take(max) + "…"
}

private fun formatNumber(n: Int): String =
    NumberFormat.getIntegerInstance().format(n)
