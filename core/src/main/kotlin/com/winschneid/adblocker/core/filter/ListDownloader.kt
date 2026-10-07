package com.winschneid.adblocker.core.filter

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a blocklist to a file, verifying that it actually contains domain rules. */
class ListDownloader(
    private val userAgent: String = "AndroidAdBlocker (https://github.com/winschneid/AndroidAdBlocker)",
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    class Result(val bytes: Long, val entries: Int)

    /**
     * Downloads [url] into [destination] (atomically, through a temporary file) and returns the size and the
     * number of parsed entries. Throws [IOException] on any failure; the previous file is left untouched.
     */
    @Throws(IOException::class)
    fun download(url: String, destination: File): Result {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            val bytes = openStream(url).use { input -> copyLimited(input, temp) }
            var entries = 0
            temp.bufferedReader().use { reader -> entries = HostsParser.parse(reader) { } }
            if (entries == 0) throw IOException("The downloaded file contains no domain rules")
            if (!temp.renameTo(destination)) {
                destination.delete()
                if (!temp.renameTo(destination)) throw IOException("Could not replace ${destination.name}")
            }
            return Result(bytes, entries)
        } finally {
            temp.delete()
        }
    }

    private fun openStream(startUrl: String): InputStream {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val url = URL(current)
            if (url.protocol != "https" && url.protocol != "http") throw IOException("Unsupported URL scheme: ${url.protocol}")
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/plain, */*")
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> return connection.inputStream
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER, 307, 308,
                -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    connection.disconnect()
                    current = URL(url, location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun copyLimited(input: InputStream, target: File): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("List is larger than $maxBytes bytes")
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
