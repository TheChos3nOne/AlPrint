package com.almoukhtar.alprint.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.ParcelFileDescriptor
import com.almoukhtar.alprint.model.DiscoveredDevice
import com.almoukhtar.alprint.model.EsclCapabilities
import com.almoukhtar.alprint.model.PrinterNetworkStatus
import com.almoukhtar.alprint.model.SupplyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object NetworkPrinterService {

    /**
     * Discovers Wi-Fi printers (_ipp._tcp.) and eSCL scanners (_uscan._tcp.) on the local LAN
     * using Android's NsdManager, plus probes the saved/default IP (e.g. 192.168.0.58).
     */
    suspend fun discoverLanDevices(
        context: Context,
        savedIp: String,
        timeoutMs: Long = 2200L
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredDevice>()
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val serviceTypes = listOf("_uscan._tcp.", "_ipp._tcp.")
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        if (nsdManager != null) {
            for (serviceType in serviceTypes) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                    override fun onDiscoveryStarted(regType: String?) {}
                    override fun onDiscoveryStopped(serviceType: String?) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                        if (serviceInfo == null) return
                        try {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(info: NsdServiceInfo?) {
                                    val host = info?.host ?: return
                                    val ip = host.hostAddress ?: return
                                    if (host is Inet4Address && !ip.startsWith("127.")) {
                                        val cleanName = info.serviceName
                                            ?.replace("\\032", " ")
                                            ?.trim()
                                            ?.ifEmpty { "Wi-Fi Printer ($ip)" }
                                            ?: "Wi-Fi Printer ($ip)"
                                        found[ip] = DiscoveredDevice(
                                            name = cleanName,
                                            ip = ip,
                                            port = info.port,
                                            isScanner = true,
                                            isPrinter = true
                                        )
                                    }
                                }
                            })
                        } catch (_: Exception) {
                        }
                    }
                }
                try {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    listeners.add(listener)
                } catch (_: Exception) {
                }
            }
        }

        // Also directly probe the saved IP (and 192.168.0.58 if different) so it works immediately
        val candidateIps = linkedSetOf<String>()
        if (savedIp.isNotBlank()) candidateIps.add(savedIp.trim())
        candidateIps.add("192.168.0.58")

        for (ip in candidateIps) {
            val caps = probeEsclCapabilities(ip)
            if (caps != null) {
                found[ip] = DiscoveredDevice(
                    name = caps.makeAndModel.ifBlank { "HP Smart Tank ($ip)" },
                    ip = ip,
                    port = 80,
                    isScanner = true,
                    isPrinter = true
                )
            } else if (isTcpReachable(ip, 80, 650) || isTcpReachable(ip, 631, 650)) {
                found.putIfAbsent(
                    ip,
                    DiscoveredDevice(
                        name = "Network Printer ($ip)",
                        ip = ip,
                        port = 80,
                        isScanner = true,
                        isPrinter = true
                    )
                )
            }
        }

        delay(timeoutMs)

        if (nsdManager != null) {
            for (l in listeners) {
                try {
                    nsdManager.stopServiceDiscovery(l)
                } catch (_: Exception) {
                }
            }
        }

        found.values.sortedBy { it.ip }
    }

    /**
     * Probes printer/scanner status over HTTP eSCL, TCP 80/631, and UDP 161 SNMP v1 supplies.
     */
    suspend fun probePrinterStatus(ip: String): PrinterNetworkStatus = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim()
        val ewsUrl = "http://$cleanIp"
        if (cleanIp.isEmpty()) {
            return@withContext PrinterNetworkStatus(
                ip = "",
                reachable = false,
                modelName = "No IP Configured",
                stateLabel = "Enter printer Wi-Fi IP",
                esclAvailable = false,
                ewsUrl = "",
                supplies = emptyList()
            )
        }

        val caps = probeEsclCapabilities(cleanIp)
        val httpOk = caps != null || isTcpReachable(cleanIp, 80, 900) || isTcpReachable(cleanIp, 631, 900)
        val snmpSupplies = querySnmpSupplies(cleanIp)

        val model = caps?.makeAndModel
            ?.takeIf { it.isNotBlank() }
            ?: if (httpOk) "HP Smart Tank 580-590 series ($cleanIp)" else "Unreachable ($cleanIp)"

        val stateLabel = when {
            caps != null -> "Ready • Wi-Fi Print (IPP) & eSCL Flatbed Online"
            httpOk -> "Online • Wi-Fi Print & Web Dashboard Active"
            else -> "Offline • Check that phone & printer are on the same Wi-Fi"
        }

        PrinterNetworkStatus(
            ip = cleanIp,
            reachable = httpOk,
            modelName = model,
            stateLabel = stateLabel,
            esclAvailable = caps != null,
            ewsUrl = ewsUrl,
            supplies = snmpSupplies
        )
    }

    /**
     * Queries /eSCL/ScannerCapabilities over HTTP port 80.
     */
    suspend fun probeEsclCapabilities(ipOrBaseUrl: String): EsclCapabilities? = withContext(Dispatchers.IO) {
        val base = normalizeEsclBase(ipOrBaseUrl)
        val url = URL("$base/ScannerCapabilities")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 2000
                setRequestProperty("Accept", "text/xml, application/xml, */*")
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val xmlBytes = conn.inputStream.use { it.readBytes() }
            parseCapabilitiesXml(xmlBytes)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Rewrites any eSCL Location header (even if the printer returned an mDNS hostname like
     * http://HP91C060.local/eSCL/ScanJobs/123 or http://HP91C060.local:8080/eSCL/ScanJobs/123
     * or a relative path) to always use the working IPv4 address while preserving any explicit port.
     */
    fun resolveEsclJobUrl(baseEsclUrl: String, locationHeader: String): String {
        val cleanBase = normalizeEsclBase(baseEsclUrl).trimEnd('/')
        val baseParsed = runCatching { URL(cleanBase) }.getOrNull()
        val targetHost = baseParsed?.host ?: baseEsclUrl.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
        val baseScheme = baseParsed?.protocol ?: "http"
        val hostRoot = cleanBase.substringBefore("/eSCL").trimEnd('/')
        val trimmedLoc = locationHeader.trim().trimEnd('/')

        if (trimmedLoc.startsWith("http://", ignoreCase = true) ||
            trimmedLoc.startsWith("https://", ignoreCase = true)
        ) {
            val locParsed = runCatching { URL(trimmedLoc) }.getOrNull()
            if (locParsed != null) {
                val scheme = locParsed.protocol.ifBlank { baseScheme }
                val portPart = if (locParsed.port > 0 && locParsed.port != 80 && locParsed.port != 443) {
                    ":${locParsed.port}"
                } else if (baseParsed != null && baseParsed.port > 0 && baseParsed.port != 80 && baseParsed.port != 443) {
                    ":${baseParsed.port}"
                } else {
                    ""
                }
                val rawPath = locParsed.path.trimEnd('/')
                val finalPath = if (rawPath.contains("/eSCL/", ignoreCase = true) ||
                    rawPath.startsWith("/eSCL", ignoreCase = true)
                ) {
                    rawPath
                } else {
                    "/eSCL/${rawPath.trimStart('/')}"
                }
                return "$scheme://$targetHost$portPart$finalPath"
            }
        }

        // Relative Location header
        val esclIdx = trimmedLoc.indexOf("/eSCL/", ignoreCase = true)
        if (esclIdx >= 0) {
            return "$hostRoot${trimmedLoc.substring(esclIdx)}"
        }
        return when {
            trimmedLoc.startsWith("/") -> "$hostRoot$trimmedLoc"
            else -> "$cleanBase/${trimmedLoc.trimStart('/')}"
        }
    }

    /**
     * Checks /eSCL/ScannerStatus for any leftover/stuck ScanJobs and sends DELETE to clear them.
     */
    private fun clearStaleEsclJobs(base: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$base/ScannerStatus").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2500
                setRequestProperty("Connection", "close")
            }
            if (conn.responseCode in 200..299) {
                val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                // Look for any <pwg:JobUri> or <scan:JobUri> in Processing/Pending/Completed state
                val regex = Regex("<(?:\\w+:)?JobUri>([^<]+)</(?:\\w+:)?JobUri>")
                for (match in regex.findAll(xml)) {
                    val uri = match.groupValues[1].trim()
                    if (uri.isNotEmpty()) {
                        val delUrl = resolveEsclJobUrl(base, uri)
                        runCatching {
                            val del = (URL(delUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "DELETE"
                                connectTimeout = 1200
                                readTimeout = 1200
                                setRequestProperty("Connection", "close")
                            }
                            del.responseCode
                            del.disconnect()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Converts a user-selected scan size ("A4", "Letter", "4x6\"", "5x7\"", "ID Card", "Full Glass")
     * into eSCL ThreeHundredthsOfInches (1/300th inch) width and height.
     */
    fun getEsclScanRegionThreeHundredths(pageSize: String): Pair<Int, Int> {
        return when (pageSize) {
            "Letter" -> 2550 to 3300     // 8.5" x 11.0"
            "4x6\"" -> 1200 to 1800      // 4.0" x 6.0"
            "5x7\"" -> 1500 to 2100      // 5.0" x 7.0"
            "ID Card" -> 1200 to 900     // 4.0" x 3.0" fast corner scan for ID cards
            "Full Glass" -> 2550 to 3508 // 8.5" x 11.69" full platen
            else -> 2480 to 3508         // A4 (8.27" x 11.69")
        }
    }

    /**
     * Performs a real driverless Wi-Fi eSCL scan via POST /eSCL/ScanJobs and downloads the resulting
     * JPEG or PDF document into a Bitmap.
     */
    suspend fun scanViaEscl(
        context: Context,
        ipOrBaseUrl: String,
        dpi: Int = 300,
        colorMode: String = "Color",
        pageSize: String = "A4",
        onProgress: (String) -> Unit = {}
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val base = normalizeEsclBase(ipOrBaseUrl)
        val esclColor = when (colorMode.lowercase()) {
            "grayscale", "gray" -> "Grayscale8"
            "bw", "black & white", "blackandwhite" -> "BlackAndWhite1"
            else -> "RGB24"
        }

        val (widthThreeHundredths, heightThreeHundredths) = getEsclScanRegionThreeHundredths(pageSize)

        // Use Version 2.0 which is verified to start the physical scan on HP Smart Tank 580
        val scanSettingsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
              <pwg:Version>2.0</pwg:Version>
              <pwg:ScanRegions>
                <pwg:ScanRegion>
                  <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
                  <pwg:XOffset>0</pwg:XOffset>
                  <pwg:YOffset>0</pwg:YOffset>
                  <pwg:Width>$widthThreeHundredths</pwg:Width>
                  <pwg:Height>$heightThreeHundredths</pwg:Height>
                </pwg:ScanRegion>
              </pwg:ScanRegions>
              <pwg:InputSource>Platen</pwg:InputSource>
              <scan:ColorMode>$esclColor</scan:ColorMode>
              <scan:XResolution>$dpi</scan:XResolution>
              <scan:YResolution>$dpi</scan:YResolution>
              <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
            </scan:ScanSettings>
        """.trimIndent()

        var jobUrl: String? = null
        try {
            onProgress("Starting scan job on ${ipOrBaseUrl.trim()}...")
            clearStaleEsclJobs(base)
            val jobsUrl = URL("$base/ScanJobs")
            val xmlBytes = scanSettingsXml.toByteArray(Charsets.UTF_8)

            var postConn: HttpURLConnection? = null
            val locationHeader: String
            try {
                postConn = (jobsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    connectTimeout = 6000
                    readTimeout = 15000
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(xmlBytes.size)
                    setRequestProperty("Content-Type", "text/xml")
                    setRequestProperty("Connection", "close")
                }
                postConn.outputStream.use { it.write(xmlBytes) }

                val code = postConn.responseCode
                if (code != 201 && code != 200) {
                    val errText = runCatching {
                        postConn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()?.take(120) ?: ""
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Scanner returned HTTP $code from /eSCL/ScanJobs. ${if (code == 503) "Scanner is busy — wait a few seconds and try again." else errText}"
                        )
                    )
                }

                locationHeader = postConn.getHeaderField("Location")
                    ?: return@withContext Result.failure(
                        IllegalStateException("Scanner did not return a job Location header")
                    )

                runCatching { postConn.inputStream?.close() }
            } finally {
                postConn?.disconnect()
            }

            // Always rewrite Location to the working IPv4 hostRoot so Android never fails on .local mDNS hostnames!
            val resolvedJobUrl = resolveEsclJobUrl(base, locationHeader)
            jobUrl = resolvedJobUrl
            val nextDocUrl = "$resolvedJobUrl/NextDocument"

            onProgress("Scanning page from flatbed...")

            var lastError = "Timed out waiting for flatbed image stream"
            for (attempt in 1..6) {
                var docConn: HttpURLConnection? = null
                try {
                    docConn = (URL(nextDocUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 8000
                        readTimeout = 45000
                        useCaches = false
                        setRequestProperty("Accept", "image/jpeg, application/pdf, */*")
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("Connection", "close")
                    }
                    val docCode = docConn.responseCode
                    if (docCode == 200) {
                        onProgress("Receiving scanned image...")
                        val contentType = docConn.contentType?.lowercase() ?: ""
                        val bytes = readEsclDocumentStream(docConn) { bytesRead ->
                            val kb = bytesRead / 1024
                            onProgress("Receiving scanned image ($kb KB)...")
                        }
                        if (bytes.isEmpty()) {
                            lastError = "Scanner returned an empty document stream"
                            break
                        } else if (contentType.contains("pdf") || (bytes.size > 4 && String(bytes, 0, 4) == "%PDF")) {
                            val pdfFile = File(context.cacheDir, "escl_${System.currentTimeMillis()}.pdf")
                            FileOutputStream(pdfFile).use { it.write(bytes) }
                            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = PdfRenderer(pfd)
                            val page = renderer.openPage(0)
                            val bmp = Bitmap.createBitmap(
                                (page.width * 2).coerceAtLeast(600),
                                (page.height * 2).coerceAtLeast(800),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            page.close()
                            renderer.close()
                            pfd.close()
                            pdfFile.delete()
                            return@withContext Result.success(bmp)
                        } else {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                return@withContext Result.success(bitmap)
                            }
                            lastError = "Failed to decode JPEG image from scanner (${bytes.size} bytes)"
                            break
                        }
                    } else if (docCode == 503 || docCode == 202) {
                        onProgress("Scanner lamp warming up (attempt $attempt)...")
                        delay(1000)
                    } else {
                        lastError = "NextDocument returned HTTP $docCode"
                        break
                    }
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "Error reading NextDocument"}"
                    if (attempt < 3) {
                        delay(800)
                    } else {
                        break
                    }
                } finally {
                    docConn?.disconnect()
                }
            }

            Result.failure(IllegalStateException(lastError))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Clean up job on scanner so the scanner immediately returns to Idle state
            if (jobUrl != null) {
                runCatching {
                    val delConn = (URL(jobUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        connectTimeout = 1500
                        readTimeout = 1500
                        setRequestProperty("Connection", "close")
                    }
                    delConn.responseCode
                    delConn.disconnect()
                }
            }
        }
    }

    /**
     * Reads the eSCL /NextDocument stream in chunks and detects the JPEG End-Of-Image (0xFF 0xD9) marker
     * so that even if the printer's embedded HTTP server keeps the socket open after finishing the scan,
     * we return the complete JPEG image immediately without waiting for a 45-second socket read timeout!
     */
    private fun readEsclDocumentStream(
        conn: HttpURLConnection,
        onBytesProgress: (Int) -> Unit
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(256 * 1024)
        val contentLen = conn.contentLength
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var lastProgressReport = 0
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (timeoutEx: java.net.SocketTimeoutException) {
                        // If the socket timed out AFTER we already received a valid image payload, return what we have!
                        if (out.size() > 4096) break else throw timeoutEx
                    }
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        val currentSize = out.size()
                        if (currentSize - lastProgressReport >= 64 * 1024) {
                            lastProgressReport = currentSize
                            onBytesProgress(currentSize)
                        }
                        if (contentLen > 0 && currentSize >= contentLen) {
                            break
                        }
                        // Check for JPEG End-Of-Image marker (0xFF, 0xD9) at the end of a chunk when no more bytes are queued
                        if (contentLen <= 0 && currentSize > 4096 && n >= 2) {
                            val b1 = buf[n - 2]
                            val b2 = buf[n - 1]
                            if (b1 == 0xFF.toByte() && b2 == 0xD9.toByte()) {
                                val snapshot = out.toByteArray()
                                if (snapshot.size >= 4 &&
                                    snapshot[0] == 0xFF.toByte() &&
                                    snapshot[1] == 0xD8.toByte()
                                ) {
                                    // Verify it decodes cleanly as a complete JPEG
                                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(snapshot, 0, snapshot.size, opts)
                                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                                        return snapshot
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (out.size() <= 4096) throw IllegalStateException("Interrupted while streaming image from scanner")
        }
        return out.toByteArray()
    }

    private fun normalizeEsclBase(ipOrBaseUrl: String): String {
        val trimmed = ipOrBaseUrl.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                if (trimmed.endsWith("/eSCL", ignoreCase = true)) trimmed else "$trimmed/eSCL"
            else -> "http://$trimmed/eSCL"
        }
    }

    private fun parseCapabilitiesXml(xmlBytes: ByteArray): EsclCapabilities {
        var makeAndModel = "Wi-Fi eSCL Scanner"
        val resolutions = mutableSetOf(150, 300)
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var eventType = parser.eventType
            var currentTag = ""
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "MakeAndModel" -> makeAndModel = text
                                "XResolution" -> text.toIntOrNull()?.let { resolutions.add(it) }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }
        val sortedRes = resolutions.sorted()
        return EsclCapabilities(
            makeAndModel = makeAndModel,
            maxDpi = sortedRes.lastOrNull() ?: 600,
            resolutions = sortedRes
        )
    }

    private fun isTcpReachable(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sends a lightweight UDP SNMPv1 GET request for Printer-MIB marker levels (1..4: Black, Cyan, Magenta, Yellow).
     */
    private fun querySnmpSupplies(ip: String): List<SupplyLevel> {
        val defaultMeta = listOf(
            Triple(1, "Black Ink", 0xFF1E293BL),
            Triple(2, "Cyan Ink", 0xFF06B6D4L),
            Triple(3, "Magenta Ink", 0xFFEC4899L),
            Triple(4, "Yellow Ink", 0xFFEAB308L)
        )
        val results = mutableListOf<SupplyLevel>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { soTimeout = 550 }
            val address = InetAddress.getByName(ip)
            for ((index, label, colorHex) in defaultMeta) {
                // prtMarkerSuppliesLevel.1.index = 1.3.6.1.2.1.43.11.1.1.9.1.<index>
                val level = snmpGetInt(socket, address, intArrayOf(1, 3, 6, 1, 2, 1, 43, 11, 1, 1, 9, 1, index))
                val maxCap = snmpGetInt(socket, address, intArrayOf(1, 3, 6, 1, 2, 1, 43, 11, 1, 1, 8, 1, index))
                if (level != null && level > 0) {
                    val pct = if (maxCap != null && maxCap > 0) {
                        ((level.toFloat() / maxCap.toFloat()) * 100f).toInt().coerceIn(1, 100)
                    } else {
                        level.coerceIn(1, 100)
                    }
                    results.add(SupplyLevel(label, pct, colorHex))
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        return results
    }

    private fun snmpGetInt(socket: DatagramSocket, address: InetAddress, oid: IntArray): Int? {
        return try {
            val oidBytes = encodeOid(oid)
            // Variable binding: SEQUENCE { OID, NULL }
            val varBind = byteArrayOf(0x30, (oidBytes.size + 2).toByte()) + oidBytes + byteArrayOf(0x05, 0x00)
            val varBindList = byteArrayOf(0x30, varBind.size.toByte()) + varBind
            // GetRequest-PDU (0xA0): request-id=1, error-status=0, error-index=0
            val pduHeader = byteArrayOf(0x02, 0x01, 0x01, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00)
            val pduBody = pduHeader + varBindList
            val pdu = byteArrayOf(0xA0.toByte(), pduBody.size.toByte()) + pduBody
            // SNMPv1 header: version=0 (v1), community="public"
            val header = byteArrayOf(
                0x02, 0x01, 0x00,
                0x04, 0x06, 'p'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte()
            )
            val msgBody = header + pdu
            val packetBytes = byteArrayOf(0x30, msgBody.size.toByte()) + msgBody

            socket.send(DatagramPacket(packetBytes, packetBytes.size, address, 161))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val len = resp.length
            if (len < 10) return null
            // Parse last INTEGER tag (0x02) in SNMP response
            var pos = len - 3
            while (pos >= 12) {
                if (buf[pos].toInt() == 0x02) {
                    val vLen = buf[pos + 1].toInt() and 0xFF
                    if (vLen in 1..4 && pos + 2 + vLen == len) {
                        var value = 0
                        for (i in 0 until vLen) {
                            value = (value shl 8) or (buf[pos + 2 + i].toInt() and 0xFF)
                        }
                        return value
                    }
                }
                pos--
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeOid(oid: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add((oid[0] * 40 + oid[1]).toByte())
        for (i in 2 until oid.size) {
            var v = oid[i]
            if (v < 128) {
                out.add(v.toByte())
            } else {
                val stack = ArrayList<Byte>()
                stack.add((v and 0x7F).toByte())
                v = v ushr 7
                while (v > 0) {
                    stack.add(((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
                for (j in stack.size - 1 downTo 0) {
                    out.add(stack[j])
                }
            }
        }
        return byteArrayOf(0x06, out.size.toByte()) + out.toByteArray()
    }
}
