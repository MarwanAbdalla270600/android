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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ma.scraper.model.Car
import ma.scraper.ws.WsForegroundService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.NumberFormat

private const val BASE_URL = "http://195.201.127.202:3000"

private val JSON = "application/json; charset=utf-8".toMediaType()

data class ContactInfo(
    val name: String? = null,
    val address: String? = null
)

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
                        },
                        onDial = { number ->
                            // ACTION_DIAL -> keine Permission nötig
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                        },
                        onGoogle = { query ->
                            val url = "https://www.google.com/search?q=" + Uri.encode(query)
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
    onOpen: (String) -> Unit,
    onDial: (String) -> Unit,
    onGoogle: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // Highlight 5 Sekunden
    val highlighted = remember { mutableStateMapOf<String, Boolean>() }
    val prevIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    // Snackbar dezent
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Dialog state
    var showDialog by remember { mutableStateOf(false) }
    var selectedCar by remember { mutableStateOf<Car?>(null) }
    var loadingCall by remember { mutableStateOf(false) }
    var loadingGoogle by remember { mutableStateOf(false) }

    // Networking (singletons in composable ok, werden remembered)
    val gson = remember { Gson() }
    val client = remember { OkHttpClient.Builder().build() }

    suspend fun postJson(endpoint: String, payload: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val body = payload.toRequestBody(JSON)
            val req = Request.Builder()
                .url(BASE_URL + endpoint)
                .post(body)
                .build()

            client.newCall(req).execute().use { res ->
                res.code to (res.body?.string().orEmpty())
            }
        }

    suspend fun fetchTel(url: String): String? = runCatching {
        val (code, body) = postJson("/api/data/tel", """{"data":"${escapeJson(url)}"}""")
        if (code == 404) return null
        if (code !in 200..299) return null

        // Erwartet JSON string: "+4366..."
        gson.fromJson(body, String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun fetchContact(url: String): ContactInfo? = runCatching {
        val (code, body) = postJson("/api/data/contactInfo", """{"data":"${escapeJson(url)}"}""")
        if (code == 404) return null
        if (code !in 200..299) return null

        gson.fromJson(body, ContactInfo::class.java)
    }.getOrNull()

    // neue Anzeigen erkennen -> scroll + highlight
    LaunchedEffect(cars) {
        if (cars.isEmpty()) return@LaunchedEffect
        val currentTopIds = cars.take(30).map { it.id }
        val old = prevIds.value
        val newIds = currentTopIds.filter { it !in old }

        if (newIds.isNotEmpty() && old.isNotEmpty()) {
            listState.animateScrollToItem(0)
            for (id in newIds) highlighted[id] = true
            delay(5000)
            for (id in newIds) highlighted.remove(id)
        }

        prevIds.value = cars.take(300).map { it.id }.toSet()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
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
                        highlight = highlighted[car.id] == true,
                        onClick = { onOpen(car.url) },
                        onLongClick = {
                            selectedCar = car
                            showDialog = true
                        }
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showDialog && selectedCar != null) {
        val car = selectedCar!!

        AlertDialog(
            onDismissRequest = {
                if (!loadingCall && !loadingGoogle) {
                    showDialog = false
                    selectedCar = null
                }
            },
            title = { Text("Aktion") },
            text = {
                Text(
                    text = safeText(car.title, 120),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                    Button(
                        onClick = {
                            scope.launch {
                                loadingCall = true
                                val tel = fetchTel(car.url)
                                loadingCall = false

                                if (tel == null) {
                                    snackbarHostState.showSnackbar("Keine Nummer gefunden.")
                                } else {
                                    onDial(tel) // Main thread OK
                                    showDialog = false
                                    selectedCar = null
                                }
                            }
                        },
                        enabled = !loadingCall && !loadingGoogle
                    ) {
                        if (loadingCall) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Anrufen")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                loadingGoogle = true
                                val info = fetchContact(car.url)
                                loadingGoogle = false

                                val name = info?.name?.trim().orEmpty()
                                val address = info?.address?.trim().orEmpty()

                                // ✅ wenn eins von beiden fehlt -> Fehler (wie du wolltest)
                                if (name.isBlank() || address.isBlank()) {
                                    snackbarHostState.showSnackbar("Kontaktinfo unvollständig.")
                                } else {
                                    onGoogle("$name $address")
                                    showDialog = false
                                    selectedCar = null
                                }
                            }
                        },
                        enabled = !loadingCall && !loadingGoogle
                    ) {
                        if (loadingGoogle) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Googeln")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!loadingCall && !loadingGoogle) {
                            showDialog = false
                            selectedCar = null
                        }
                    }
                ) { Text("Abbrechen") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WillhabenRowCard(
    car: Car,
    highlight: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
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
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

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
    val cleaned = s.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim()
    return if (cleaned.length <= max) cleaned else cleaned.take(max) + "…"
}

private fun formatNumber(n: Int): String =
    NumberFormat.getIntegerInstance().format(n)

private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
