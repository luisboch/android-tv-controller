package dev.atvremote.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import dev.atvremote.common.Command
import dev.atvremote.common.Crypto
import dev.atvremote.common.Discovery
import dev.atvremote.common.Ids
import dev.atvremote.common.Pairing
import dev.atvremote.common.Ports
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Listens for discovery, pairing and commands from paired phones. Started on boot. */
class RemoteService : Service() {
    private lateinit var store: PairingStore
    private val injector = KeyInjector()
    private val pairing = AtomicBoolean(false)
    private val sockets = mutableListOf<Closeable>()
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        store = (application as App).store
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Serviço", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_PAIR, "Pareamento", NotificationManager.IMPORTANCE_HIGH))
        startForeground(
            NOTIF_SERVICE,
            Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("Controle remoto ativo")
                .setOngoing(true)
                .build(),
        )
        running = true
        loop("discovery", ::discoveryLoop)
        loop("pairing", ::pairingLoop)
        loop("command", ::commandLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        super.onDestroy()
    }

    /** Runs [body] on its own thread, restarting it if the network isn't ready yet or a socket fails. */
    private fun loop(name: String, body: () -> Unit) = thread(name = name, isDaemon = true) {
        while (running) {
            try {
                body()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "$name failed, retrying", e)
            }
            if (running) Thread.sleep(2_000)
        }
    }

    private fun <T : Closeable> track(c: T): T = c.also { synchronized(sockets) { sockets += it } }

    private fun discoveryLoop() {
        val socket = track(DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(Ports.DISCOVERY))
        })
        socket.use {
            val buf = ByteArray(64)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                it.receive(p)
                if (Discovery.isRequest(p.data, p.length)) {
                    val res = Discovery.response(tvName())
                    it.send(DatagramPacket(res, res.size, p.socketAddress))
                }
            }
        }
    }

    private fun pairingLoop() {
        track(ServerSocket(Ports.PAIRING)).use { server ->
            while (running) {
                val client = server.accept()
                if (!pairing.compareAndSet(false, true)) {
                    client.close()
                    continue
                }
                thread(name = "pair-session", isDaemon = true) {
                    try {
                        client.use(::pair)
                    } catch (e: Exception) {
                        Log.w(TAG, "pairing failed", e)
                    } finally {
                        pairing.set(false)
                    }
                }
            }
        }
    }

    private fun pair(socket: Socket) {
        socket.soTimeout = Pairing.TIMEOUT_MS
        val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val req = Pairing.readRequest(inp)
        val keys = Crypto.newKeyPair()
        val tvPub = keys.public.encoded
        val pin = Crypto.randomPin()
        val key = Crypto.deriveKey(keys.private, Crypto.decodePublicKey(req.publicKey), pin, Ids.toBytes(req.deviceId))
        val expected = Pairing.confirmMac(key, req.publicKey, tvPub)
        Pairing.writeKey(out, tvPub)

        showPin(pin, req.name)
        try {
            for (attempt in 1..Pairing.MAX_ATTEMPTS) {
                val mac = ByteArray(Pairing.MAC_SIZE).also(inp::readFully)
                if (MessageDigest.isEqual(mac, expected)) {
                    store.add(PairedPhone(req.deviceId, req.name, key))
                    out.writeByte(Pairing.OK)
                    out.write(Pairing.acceptMac(key, req.publicKey, tvPub))
                    out.flush()
                    notifyPaired(req.name)
                    return
                }
                out.writeByte(if (attempt == Pairing.MAX_ATTEMPTS) Pairing.REJECTED else Pairing.WRONG_PIN)
                out.flush()
            }
        } finally {
            hidePin()
        }
    }

    private fun commandLoop() {
        track(DatagramSocket(Ports.COMMAND)).use {
            val buf = ByteArray(256)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                it.receive(p)
                val packet = buf.copyOf(p.length)
                val phone = Command.deviceId(packet)?.let(store::get) ?: continue
                val cmd = Command.decode(phone.key, packet) ?: continue
                if (!store.acceptCounter(phone.id, cmd.counter)) continue
                injector.inject(cmd.keyCode, cmd.action)
            }
        }
    }

    private fun tvName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun showPin(pin: String, phoneName: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_PAIR,
            Notification.Builder(this, CH_PAIR)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("Código de pareamento: $pin")
                .setContentText("Digite no celular \"$phoneName\"")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build(),
        )
        startActivity(
            Intent(this, PinActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PinActivity.EXTRA_PIN, pin)
                .putExtra(PinActivity.EXTRA_PHONE, phoneName),
        )
    }

    private fun hidePin() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_PAIR)
        PinActivity.close()
    }

    private fun notifyPaired(phoneName: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_PAIRED,
            Notification.Builder(this, CH_PAIR)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("Celular pareado")
                .setContentText(phoneName)
                .setTimeoutAfter(10_000)
                .build(),
        )
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val CH_SERVICE = "service"
        private const val CH_PAIR = "pair"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_PAIR = 2
        private const val NOTIF_PAIRED = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }
    }
}
