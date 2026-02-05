package ma.scraper.ws

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import ma.scraper.AppVisibility
import ma.scraper.MainActivity
import ma.scraper.R
import ma.scraper.model.Car
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WsForegroundService : Service() {

    companion object {
        private const val TAG = "WS"

        const val CHANNEL_ID = "willhaben_foreground"
        const val NEW_CARS_CHANNEL_ID = "willhaben_newcars"
        const val NOTIF_ID = 1
        const val NEW_CARS_NOTIF_ID = 2

        // Emulator: ws://10.0.2.2:3000/ws
        // Handy: ws://<PC_IP>:3000/ws
        const val WS_URL = "ws://195.201.127.202:3000/ws"
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    // RAM store (dedup)
    private val byId = ConcurrentHashMap<String, Car>()
    private val ordered = CopyOnWriteArrayList<Car>() // newest-first

    // UI listeners
    private val listeners = CopyOnWriteArrayList<(List<Car>) -> Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectAttempt = 0

    // 🔔 Sound
    private var soundPool: SoundPool? = null
    private var pingId: Int = 0

    inner class LocalBinder : Binder() {
        fun getService(): WsForegroundService = this@WsForegroundService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels()
        }

        initSound()

        startForeground(NOTIF_ID, persistentNotif("Starte…"))
        connect()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(cb: (List<Car>) -> Unit) {
        listeners.add(cb)
        val snap = currentList()
        mainHandler.post { cb(snap) }
    }

    fun removeListener(cb: (List<Car>) -> Unit) {
        listeners.remove(cb)
    }

    fun currentList(): List<Car> = ordered.toList()

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()

        pingId = soundPool!!.load(this, R.raw.hupe, 1)
    }

    private fun connect() {
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null

        Log.i(TAG, "Connecting to: $WS_URL")

        val req = Request.Builder().url(WS_URL).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "CONNECTED ✅ code=${response.code}")
                reconnectAttempt = 0
                updatePersistentNotif("Verbunden ✅")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    val incoming = parseCars(text) ?: return@launch

                    val added = mergeIncoming(incoming)
                    if (added > 0) {
                        notifyUi()

                        if (AppVisibility.isForeground) {
                            soundPool?.play(pingId, 1f, 1f, 0, 0, 1f)
                        } else {
                            showNewCarsNotif(added)
                        }

                        updatePersistentNotif("Verbunden ✅ ($added neu)")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "CLOSED code=$code reason=$reason")
                updatePersistentNotif("Getrennt ❌")
                scheduleReconnect("closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "FAIL ❌ ${t.message}", t)
                updatePersistentNotif("Verbindung fehlgeschlagen ❌")
                scheduleReconnect("failure: ${t.message}")
            }
        })
    }

    private fun parseCars(text: String): List<Car>? =
        runCatching {
            val type = object : TypeToken<List<Car>>() {}.type
            gson.fromJson<List<Car>>(text, type)
        }.getOrNull()

    private fun scheduleReconnect(reason: String) {
        scope.launch {
            reconnectAttempt++
            val delayMs = min(30_000L, 1000L * reconnectAttempt)
            Log.w(TAG, "Reconnect in ${delayMs}ms (attempt=$reconnectAttempt) reason=$reason")
            delay(delayMs)
            connect()
        }
    }

    /**
     * - dedup by id
     * - newest-first
     * - HARD CAP: max 200
     *   wenn >200 => untere 100 löschen
     */
    private fun mergeIncoming(incoming: List<Car>): Int {
        var added = 0

        for (car in incoming) {
            val existed = byId.putIfAbsent(car.id, car)
            if (existed == null) {
                ordered.add(0, car)
                added++
            }
        }

        // HARD CAP: nie größer als 200
        if (ordered.size > 200) {
            val removeCount = min(100, ordered.size)
            repeat(removeCount) {
                val last = ordered.removeAt(ordered.size - 1)
                byId.remove(last.id)
            }
        }

        return added
    }

    private fun notifyUi() {
        val snap = currentList()
        mainHandler.post {
            for (cb in listeners) cb(snap)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { ws?.close(1000, "service destroyed") } catch (_: Throwable) {}
        soundPool?.release()
        soundPool = null
        scope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WillhabenScraper", NotificationManager.IMPORTANCE_LOW)
        )

        nm.createNotificationChannel(
            NotificationChannel(NEW_CARS_CHANNEL_ID, "Neue Autos", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 80, 150)
            }
        )
    }

    private fun updatePersistentNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, persistentNotif(text))
    }

    private fun persistentNotif(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WillhabenScraper")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun showNewCarsNotif(count: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NEW_CARS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Neue Autos")
            .setContentText("$count neue Anzeige(n) gefunden")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        getSystemService(NotificationManager::class.java).notify(NEW_CARS_NOTIF_ID, notif)
    }
}
