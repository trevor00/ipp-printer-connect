package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PrinterDiscovery(private val context: Context) {
    private val workers = Executors.newFixedThreadPool(32)
    private val found = ConcurrentHashMap<String, PrinterRecord>()
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scanLock = Any()
    private var activeScanCancellation: AtomicBoolean? = null
    private var activePortPool: ExecutorService? = null
    private var activeIppPool: ExecutorService? = null

    fun connectedCidr(): String? {
        return currentNetwork()?.connectedCidr
    }

    fun currentNetwork(): NetworkSnapshot? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = cm.activeNetwork ?: return null
        return networkSnapshot(cm, activeNetwork)
    }

    fun currentWifiNetwork(): NetworkSnapshot? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return networkSnapshot(cm, wifiNetwork)
    }

    private fun networkSnapshot(cm: ConnectivityManager, network: android.net.Network): NetworkSnapshot? {
        val properties = cm.getLinkProperties(network) ?: return null
        val capabilities = cm.getNetworkCapabilities(network)
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        val gateway = properties.routes.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address
        }?.gateway?.hostAddress
        val localIp = link.address.hostAddress ?: return null
        val connectionTypes = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("모바일 데이터")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("유선 LAN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
        }
        return NetworkSnapshot(
            localIp,
            "$localIp/${link.prefixLength}",
            gateway,
            connectionTypes.distinct().joinToString(" + ").ifBlank { "기타 네트워크" },
        )
    }

    fun publicBgpScope(): BgpScope {
        val egress = getJson("https://api.ipify.org?format=json").getString("ip")
        val overview = getJson("https://stat.ripe.net/data/prefix-overview/data.json?resource=$egress")
        val data = overview.getJSONObject("data")
        val prefix = data.optString("prefix").ifBlank { data.optString("resource") }
            .ifBlank { throw IllegalStateException("BGP prefix를 찾지 못했습니다") }
        require(prefix.contains('/')) { "RIPE가 IPv4 prefix 대신 $prefix 를 반환했습니다" }
        return BgpScope(egress, prefix)
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun scanCidr(
        cidr: String,
        hostCap: Int,
        port: Int,
        connectTimeoutMs: Int,
        onFound: (PrinterRecord) -> Unit,
        onProgress: (String, Int, Int) -> Unit,
        onDone: (String?, Int) -> Unit,
    ) {
        cancelCidrScan()
        val cancelled = AtomicBoolean(false)
        synchronized(scanLock) { activeScanCancellation = cancelled }
        workers.execute {
            val openCount = AtomicInteger(0)
            try {
                val targets = enumerate(cidr, hostCap)
                val portCompleted = AtomicInteger(0)
                val ippCompleted = AtomicInteger(0)
                val openHosts = ConcurrentHashMap.newKeySet<String>()
                val portPool = Executors.newFixedThreadPool(minOf(targets.size, TCP_FIRST_PASS_WORKERS))
                val ippPool = Executors.newFixedThreadPool(IPP_VALIDATION_WORKERS)
                synchronized(scanLock) {
                    if (activeScanCancellation === cancelled && !cancelled.get()) {
                        activePortPool = portPool
                        activeIppPool = ippPool
                    } else {
                        portPool.shutdownNow()
                        ippPool.shutdownNow()
                    }
                }

                fun submitIpp(host: String) {
                    if (!openHosts.add(host)) return
                    openCount.incrementAndGet()
                    if (cancelled.get()) return
                    try {
                        ippPool.execute {
                            if (cancelled.get()) return@execute
                            try {
                                val records = probeHost(host, port)
                                if (!cancelled.get()) {
                                    if (records.isEmpty()) publish(unavailableHostRecord(host, port), onFound)
                                    else records.forEach { publish(it, onFound) }
                                }
                            } finally {
                                if (!cancelled.get()) {
                                    val done = ippCompleted.incrementAndGet()
                                    reportProgress("IPP 확인", done, maxOf(done, openCount.get()), onProgress)
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        // The user cancelled while a TCP result was handed to IPP validation.
                    }
                }

                // Fast first pass. Open ports immediately enter the independent IPP pool.
                for (host in targets) {
                    if (cancelled.get()) break
                    try {
                        portPool.execute {
                            if (cancelled.get()) return@execute
                            if (isTcpPortOpen(host, port, connectTimeoutMs)) submitIpp(host)
                            if (!cancelled.get()) reportProgress("TCP $port 확인", portCompleted.incrementAndGet(), targets.size, onProgress)
                        }
                    } catch (_: RejectedExecutionException) {
                        if (!cancelled.get()) throw IllegalStateException("TCP 검사 작업을 시작하지 못했습니다")
                    }
                }
                portPool.shutdown()
                while (!portPool.isTerminated) Thread.sleep(20)

                ippPool.shutdown()
                while (!ippPool.isTerminated) Thread.sleep(20)
                onDone(if (cancelled.get()) "검색 중지됨" else null, openCount.get())
            } catch (e: Exception) {
                onDone(if (cancelled.get()) "검색 중지됨" else "${e.javaClass.simpleName}: ${e.message}", openCount.get())
            } finally {
                synchronized(scanLock) {
                    if (activeScanCancellation === cancelled) {
                        activeScanCancellation = null
                        activePortPool = null
                        activeIppPool = null
                    }
                }
            }
        }
    }

    fun cancelCidrScan(): Boolean = synchronized(scanLock) {
        val cancellation = activeScanCancellation ?: return@synchronized false
        cancellation.set(true)
        activePortPool?.shutdownNow()
        activeIppPool?.shutdownNow()
        true
    }

    fun clearFoundPrinters() {
        found.clear()
    }

    private fun reportProgress(phase: String, done: Int, total: Int, callback: (String, Int, Int) -> Unit) {
        val interval = maxOf(1, total / 100)
        if (done == total || done % interval == 0) callback(phase, done, total)
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)

    fun startMdns(onFound: (PrinterRecord) -> Unit, onStatus: (String) -> Unit) {
        stopMdns()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("network-printer-studio").apply { setReferenceCounted(false); acquire() }
        val nsd = context.getSystemService(NsdManager::class.java)
        listOf("_ipp._tcp." to false, "_ipps._tcp." to true).forEach { (serviceType, secure) ->
            val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = onStatus("mDNS IPP 광고 수집 중")
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val rp = info.attributes["rp"]?.toString(Charsets.UTF_8)?.trim().orEmpty()
                        val path = "/" + rp.ifBlank { "ipp/print" }.trimStart('/')
                        workers.execute {
                            val endpoint = PrinterEndpoint(host, info.port, path, secure)
                            val record = runCatching { IppClient.getPrinterAttributes(endpoint) }.getOrNull()
                                ?.copy(source = if (secure) "mdns-ipps" else "mdns-ipp")
                                ?: IppClient.authChallenge(endpoint)?.let { challenge ->
                                    authenticationRequiredRecord(
                                        endpoint = endpoint,
                                        challenge = challenge,
                                        displayName = info.serviceName.ifBlank { host },
                                        source = if (secure) "mdns-ipps-auth" else "mdns-ipp-auth",
                                    )
                                }
                                ?: unavailableRecord(
                                    host,
                                    if (secure) "IPPS 광고는 확인했지만 TLS 인증서 또는 IPP 속성 조회에 실패했습니다"
                                    else "IPP 광고는 확인했지만 IPP 속성 조회에 실패했습니다",
                                    endpoint,
                                    info.serviceName.ifBlank { host },
                                    if (secure) "IPPS 연결 후보" else "IPP 연결 후보",
                                )
                            publish(record, onFound)
                        }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(type: String) = onStatus("현재 네트워크의 IPP 프린터 검색 중지 (mDNS)")
            override fun onStartDiscoveryFailed(type: String, code: Int) { onStatus("현재 네트워크의 IPP 프린터 검색 시작 실패 (mDNS): $code"); stopMdns() }
            override fun onStopDiscoveryFailed(type: String, code: Int) = onStatus("현재 네트워크 IPP 광고 검색 중지 실패 (mDNS): $code")
            }
            nsdListeners.add(listener)
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopMdns() {
        val listeners = nsdListeners.toList()
        nsdListeners.clear()
        val nsd = context.getSystemService(NsdManager::class.java)
        listeners.forEach { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    fun close() { cancelCidrScan(); stopMdns(); workers.shutdownNow() }

    private fun publish(record: PrinterRecord, onFound: (PrinterRecord) -> Unit) {
        var changed = false
        found.compute(record.endpoint.key) { _, previous ->
            val improvesAvailability = previous != null && !previous.available && record.available
            val improvesIdentity = previous != null && previous.available == record.available &&
                previous.name.isBlank() && record.name.isNotBlank()
            val improvesAuthentication = previous != null &&
                !previous.available && !record.available &&
                previous.authChallenge == null && record.authChallenge != null
            if (previous == null || improvesAvailability || improvesIdentity || improvesAuthentication) {
                changed = true
                record
            } else previous
        }
        if (changed) onFound(record)
    }

    private fun unavailableRecord(
        host: String,
        detail: String,
        endpoint: PrinterEndpoint = PrinterEndpoint(host, IPP_PORT, "/"),
        displayName: String = host,
        model: String = "IPP 연결 후보",
        authChallenge: IppAuthChallenge? = null,
        source: String = "ipp-unavailable",
    ) = PrinterRecord(
        endpoint = endpoint,
        name = displayName,
        model = model,
        location = "",
        formats = emptyList(),
        colorModes = emptyList(),
        defaultColorMode = null,
        operations = emptyList(),
        source = source,
        available = false,
        statusDetail = detail,
        authChallenge = authChallenge,
    )

    private fun authenticationRequiredRecord(
        endpoint: PrinterEndpoint,
        challenge: IppAuthChallenge,
        displayName: String = endpoint.host,
        source: String = "ipp-auth-required",
    ): PrinterRecord = unavailableRecord(
        host = endpoint.host,
        detail = "IPP 프린터가 로그인을 요구합니다: ${challenge.label}",
        endpoint = endpoint,
        displayName = displayName,
        model = "IPP 인증 필요",
        authChallenge = challenge,
        source = source,
    )

    private fun unavailableHostRecord(host: String, port: Int): PrinterRecord {
        val reverseName = runCatching { InetAddress.getByName(host).canonicalHostName }
            .getOrNull()
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() && it != host }
        val server = runCatching { IppClient.getServerSoftware(host, port) }.getOrNull()
        return unavailableRecord(
            host = host,
            detail = "TCP ${port}은 열렸지만 IPP/IPPS 표준 경로와 CUPS 프린터 큐를 확인하지 못했습니다",
            endpoint = PrinterEndpoint(host, port, "/"),
            displayName = reverseName ?: host,
            model = server ?: "IPP 연결 후보",
        )
    }

    fun probeDirectPrinter(
        host: String,
        port: Int = IPP_PORT,
        preferredEndpoint: PrinterEndpoint? = null,
    ): List<PrinterRecord> {
        preferredEndpoint?.let { endpoint ->
            val preferred = runCatching {
                IppClient.getPrinterAttributes(endpoint, 3_000)
            }.getOrNull()
            if (preferred != null) {
                return listOf(
                    preferred.copy(
                        source = if (endpoint.secure) {
                            "direct-ipps"
                        } else {
                            "direct-ipp"
                        }
                    )
                )
            }
            IppClient.authChallenge(endpoint)?.let { challenge ->
                return listOf(
                    authenticationRequiredRecord(
                        endpoint = endpoint,
                        challenge = challenge,
                        source = if (endpoint.secure) "direct-ipps-auth" else "direct-ipp-auth",
                    )
                )
            }
        }
        return probeHost(host, port).map { record ->
            record.copy(
                source = if (record.endpoint.secure) {
                    "direct-ipps-auto"
                } else {
                    "direct-ipp-auto"
                }
            )
        }
    }

    private fun probeHost(host: String, port: Int): List<PrinterRecord> {
        val secureModes = listOf(false, true)
        var firstAuthenticationRequired: PrinterRecord? = null
        for (secure in secureModes) {
            for (path in STANDARD_PATHS) {
                val endpoint = PrinterEndpoint(host, port, path, secure)
                val record = runCatching {
                    IppClient.getPrinterAttributes(endpoint, 1500)
                }.getOrNull()
                if (record != null) return listOf(record.copy(source = if (secure) "cidr-ipps" else "cidr-ipp"))
                if (firstAuthenticationRequired == null) {
                    IppClient.authChallenge(endpoint)?.let { challenge ->
                        firstAuthenticationRequired = authenticationRequiredRecord(
                            endpoint = endpoint,
                            challenge = challenge,
                            source = if (secure) "cidr-ipps-auth" else "cidr-ipp-auth",
                        )
                    }
                }
            }
            val cupsEndpoints = runCatching {
                IppClient.getCupsPrinterEndpoints(host, port, secure, 1500)
            }.getOrDefault(emptyList())
            val queues = cupsEndpoints.mapNotNull { candidate ->
                runCatching { IppClient.getPrinterAttributes(candidate.endpoint, 1500)?.copy(source = "cups-ipp") }.getOrNull()
            }
            if (queues.isNotEmpty()) return queues
        }
        return firstAuthenticationRequired?.let(::listOf) ?: emptyList()
    }

    companion object {
        private const val IPP_PORT = 631
        private val STANDARD_PATHS = listOf("/ipp/print", "/ipp/printer", "/ipp", "/printers", "/")
        private const val TCP_FIRST_PASS_WORKERS = 128
        private const val IPP_VALIDATION_WORKERS = 24

        fun containing24(ip: String): String {
            val octets = ip.split('.').map { it.toIntOrNull() ?: -1 }
            require(octets.size == 4 && octets.all { it in 0..255 }) { "유효한 공인 IPv4 주소가 아닙니다: $ip" }
            return "${octets[0]}.${octets[1]}.${octets[2]}.0/24"
        }

        fun normalizeCidr(cidr: String): String {
            val parts = cidr.trim().split('/')
            require(parts.size == 2) { "CIDR 형식이 아닙니다" }
            val octets = parts[0].split('.').map { it.toInt() }
            require(octets.size == 4 && octets.all { it in 0..255 }) { "IPv4 주소가 아닙니다" }
            val prefix = parts[1].toInt()
            require(prefix in 0..32) { "prefix는 0..32여야 합니다" }
            val ip = octets.fold(0L) { acc, value -> (acc shl 8) or value.toLong() }
            val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val network = ip and mask
            val address = listOf(24, 16, 8, 0).joinToString(".") { shift ->
                ((network shr shift) and 255).toString()
            }
            return "$address/$prefix"
        }

        fun usableHostCount(cidr: String): Long {
            val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return 0
            if (prefix !in 0..32) return 0
            val total = 1L shl (32 - prefix)
            return if (prefix >= 31) total else maxOf(0, total - 2)
        }

        fun enumerate(cidr: String, hostCap: Int): List<String> {
            val parts = cidr.trim().split("/")
            require(parts.size == 2) { "CIDR 형식이 아닙니다" }
            val octets = parts[0].split(".").map { it.toInt() }
            require(octets.size == 4 && octets.all { it in 0..255 }) { "IPv4 주소가 아닙니다" }
            val prefix = parts[1].toInt(); require(prefix in 0..32) { "prefix는 0..32여야 합니다" }
            val ip = octets.fold(0L) { acc, value -> (acc shl 8) or value.toLong() }
            val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val network = ip and mask; val count = 1L shl (32 - prefix)
            val first = if (prefix >= 31) network else network + 1
            val lastExclusive = if (prefix >= 31) network + count else network + count - 1
            val usable = lastExclusive - first
            require(usable <= hostCap) { "검색 대상 $usable 대가 최대 검사 수 $hostCap 대를 초과합니다" }
            return (first until lastExclusive).map { value ->
                listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() }
            }
        }
    }
}
