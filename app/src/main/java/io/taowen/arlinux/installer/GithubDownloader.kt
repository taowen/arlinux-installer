package io.taowen.arlinux.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Public GitHub assets only. TLS hostname verification is never bypassed. */
class GithubDownloader(private val context: Context, private val progress: (InstallState) -> Unit) {
    private data class Asset(val size: Long, val hash: String)
    private data class Route(val name: String, val client: OkHttpClient, val proxy: Boolean = false)
    private data class Sample(val route: Route, val bytes: ByteArray, val elapsed: Long)
    private val stopped = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val direct = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    // Steam302 routes preserve GitHub's hostname, SNI and certificate validation.
    private val routes = listOf(Route("GitHub", direct)) +
        listOf("20.205.243.166", "20.207.73.82", "20.27.177.113", "20.233.83.145").mapIndexed { index, ip ->
            Route("Steam302-${index + 1}", direct.newBuilder().proxy(Proxy.NO_PROXY).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val address = when (hostname) {
                        "github.com" -> ip
                        "api.github.com" -> "${index + 1}.ghapi.steam302.xyz"
                        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> "${index + 1}.ghcontent.steam302.xyz"
                        else -> hostname
                    }
                    return Dns.SYSTEM.lookup(address).map { InetAddress.getByAddress(hostname, it.address) }
                }
            }).build())
        } + Route("Release proxy", direct.newBuilder().proxy(Proxy.NO_PROXY).build(), true)

    fun cancel() { stopped.set(true); calls.forEach { it.cancel() } }
    private fun check() { if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException() }
    private fun prefix(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            check()
            val size = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (size < 0) break
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
    private fun <T> request(route: Route, url: String, start: Long? = null, end: Long? = null,
                            body: (Response) -> T): T {
        check()
        val address = if (route.proxy) "https://gh-proxy.com/$url" else url
        val builder = Request.Builder().url(address).header("User-Agent", "ARLinux-Installer/0.0.1")
            .header("Accept-Encoding", "identity")
        if (start != null) builder.header("Range", "bytes=$start-$end")
        val call = route.client.newCall(builder.build())
        calls.add(call)
        try { check(); return call.execute().use(body) }
        finally { calls.remove(call) }
    }

    private fun metadata(): Asset {
        var last: Exception? = null
        // Metadata never goes through the public release proxy.
        for (route in routes.filterNot { it.proxy }) {
            try {
                return request(route, "https://api.github.com/repos/taowen/arlinux-installer/releases/tags/latest") { response ->
                    require(response.isSuccessful) { "GitHub metadata: HTTP ${response.code}" }
                    val bytes = prefix(requireNotNull(response.body).byteStream(), 256 * 1024)
                    require(bytes.size < 256 * 1024) { "Release metadata is too large" }
                    val assets = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("assets")
                    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .single { it.getString("name") == "arlinux-latest.apk" }
                    require(asset.getString("browser_download_url") == URL) { "Unexpected release URL" }
                    val digest = asset.getString("digest")
                    require(digest.matches(Regex("sha256:[0-9a-f]{64}"))) { "Missing release SHA-256" }
                    val size = asset.getLong("size")
                    require(size in 1..512L * 1024 * 1024) { "Invalid APK size" }
                    Asset(size, digest.removePrefix("sha256:"))
                }
            } catch (error: Exception) { check(); last = error }
        }
        throw IOException("Cannot read GitHub release", last)
    }

    private fun range(response: Response, start: Long, end: Long, size: Long) {
        require(response.code == 206 && response.header("Content-Range") == "bytes $start-$end/$size") {
            "Invalid range response: HTTP ${response.code}"
        }
    }

    private fun compare(asset: Asset): List<Sample> {
        val pool = Executors.newFixedThreadPool(routes.size)
        val completion = ExecutorCompletionService<Sample>(pool)
        val end = minOf(asset.size, 1024L * 1024) - 1
        val futures = routes.map { route -> completion.submit(Callable {
            val started = SystemClock.elapsedRealtime()
            request(route, URL, 0, end) { response ->
                range(response, 0, end, asset.size)
                val bytes = prefix(response.body!!.byteStream(), (end + 1).toInt())
                require(bytes.size.toLong() == end + 1) { "Incomplete route sample" }
                Sample(route, bytes, maxOf(1, SystemClock.elapsedRealtime() - started))
            }
        }) }
        val samples = mutableListOf<Sample>()
        try {
            val deadline = SystemClock.elapsedRealtime() + 22_000
            repeat(routes.size) {
                check()
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) {
                    val future = completion.poll(remaining, TimeUnit.MILLISECONDS)
                    if (future != null) try { samples += future.get() } catch (_: ExecutionException) { check() }
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }; calls.forEach { it.cancel() }; pool.shutdownNow()
        }
        require(samples.isNotEmpty()) { "No GitHub download route succeeded" }
        return samples.sortedBy { it.elapsed }
    }

    fun download(): File {
        val asset = metadata()
        val directory = File(context.cacheDir, "installer/${asset.hash}").apply {
            check(isDirectory || mkdirs()) { "Cannot create download directory" }
        }
        val apk = File(directory, "arlinux.apk")
        if (apk.isFile && hash(apk) == asset.hash) { verify(apk); return apk }
        val count = ((asset.size + CHUNK - 1) / CHUNK).toInt()
        val parts = (0 until count).map { File(directory, "chunk-$it.part") }
        fun length(index: Int) = minOf(CHUNK, asset.size - index * CHUNK)
        parts.forEachIndexed { index, part -> if (part.length() > length(index)) require(part.delete()) }
        val samples = if (parts.indices.all { parts[it].length() == length(it) }) emptyList() else {
            progress(InstallState(R.string.comparing, running = true))
            compare(asset)
        }
        if (samples.isNotEmpty()) Log.i("ARLinuxInstaller", "Selected ${samples[0].route.name}: ${samples[0].bytes.size} bytes in ${samples[0].elapsed} ms; four parallel chunks")
        val candidates = samples.map { it.route }
        if (samples.isNotEmpty() && parts[0].length() == 0L) parts[0].writeBytes(samples[0].bytes)
        val received = AtomicLong(parts.sumOf { it.length() })
        val lastUpdate = AtomicLong()
        fun update() {
            val now = SystemClock.elapsedRealtime()
            val before = lastUpdate.get()
            if (now - before >= 200 && lastUpdate.compareAndSet(before, now)) {
                val bytes = received.get()
                val percent = (bytes * 100 / asset.size).toInt()
                progress(InstallState(R.string.downloading, listOf(bytes / 1048576, asset.size / 1048576, percent), percent, true))
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        val futures = parts.mapIndexed { index, part -> pool.submit(Callable {
            var failure: Exception? = null
            for (attempt in 0 until candidates.size * 2) {
                check()
                if (part.length() == length(index)) return@Callable
                try {
                    val start = index * CHUNK + part.length()
                    val end = index * CHUNK + length(index) - 1
                    request(candidates[attempt % candidates.size], URL, start, end) { response ->
                        range(response, start, end, asset.size)
                        response.body!!.byteStream().use { input ->
                            FileOutputStream(part, true).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var remaining = end - start + 1
                                while (remaining > 0) {
                                    check()
                                    val bytes = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                    if (bytes < 0) throw IOException("Incomplete chunk")
                                    output.write(buffer, 0, bytes); remaining -= bytes
                                    received.addAndGet(bytes.toLong()); update()
                                }
                            }
                        }
                    }
                } catch (error: Exception) { check(); failure = error }
            }
            if (part.length() != length(index)) throw IOException("Chunk download failed", failure)
        }) }
        try { futures.forEach { it.get() } }
        catch (error: ExecutionException) { check(); throw error.cause ?: error }
        finally { futures.forEach { it.cancel(true) }; calls.forEach { it.cancel() }; pool.shutdownNow() }
        check()
        progress(InstallState(R.string.verifying, running = true))
        val temporary = File(directory, "arlinux-download.apk")
        FileOutputStream(temporary).use { output -> parts.forEach { part -> part.inputStream().use { it.copyTo(output) } } }
        if (temporary.length() != asset.size || hash(temporary) != asset.hash) {
            temporary.delete(); parts.forEach { it.delete() }
            throw IOException("APK checksum mismatch. The release may have changed; retry.")
        }
        verify(temporary)
        require(temporary.renameTo(apk)) { "Cannot save APK" }
        parts.forEach { it.delete() }
        return apk
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { check(); val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val pm = context.packageManager
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // Android 10 collects archive certificates only when GET_SIGNATURES is
        // also requested; GET_SIGNING_CERTIFICATES alone returns no SigningInfo.
        val target = pm.getPackageArchiveInfo(apk.path,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        requireNotNull(target) { "Android could not parse the downloaded APK" }
        Log.i("ARLinuxInstaller", "Archive=${target.packageName}/${target.longVersionCode}; installer=${own.packageName}/${own.longVersionCode}; archiveSigners=${target.signingInfo?.apkContentsSigners?.size}; installerSigners=${own.signingInfo?.apkContentsSigners?.size}")
        require(target.packageName == context.packageName) { "Unexpected APK package: ${target.packageName}" }
        require(target.longVersionCode > own.longVersionCode) { "Downloaded APK must be newer than the installer" }
        val targetSigners = target.signingInfo?.apkContentsSigners
        require(!targetSigners.isNullOrEmpty() && targetSigners.size == own.signingInfo?.apkContentsSigners?.size && targetSigners.all {
            pm.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }) { context.getString(R.string.signature_error) }
    }

    companion object {
        private const val URL = "https://github.com/taowen/arlinux-installer/releases/download/latest/arlinux-latest.apk"
        private const val CHUNK = 4L * 1024 * 1024
    }
}
