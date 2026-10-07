package com.winschneid.adblocker.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.winschneid.adblocker.core.dns.DnsCodec
import com.winschneid.adblocker.core.engine.DnsProxyEngine
import com.winschneid.adblocker.core.engine.PendingQueryTable
import com.winschneid.adblocker.core.engine.Verdict
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The packet pump of an established VPN: one thread reads DNS queries from the TUN device, the other reads
 * answers from the upstream resolver. Blocked queries never leave the device.
 */
internal class VpnSession(
    service: VpnService,
    private val tun: ParcelFileDescriptor,
    private val engine: DnsProxyEngine,
    @Volatile var upstreams: List<InetSocketAddress>,
    @Volatile var logEnabled: Boolean,
) {
    private val pending = PendingQueryTable(random = SecureRandom())
    private val channel: DatagramChannel = DatagramChannel.open()
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()
    private val running = AtomicBoolean(true)
    private var tunThread: Thread? = null
    private var upstreamThread: Thread? = null

    init {
        // Traffic from this socket must bypass the VPN itself, otherwise it would loop back into the TUN device.
        if (!service.protect(channel.socket())) {
            channel.close()
            throw IOException("Could not protect the upstream DNS socket")
        }
        channel.configureBlocking(true)
    }

    fun start() {
        tunThread = Thread(::tunLoop, "adblock-tun").also { it.start() }
        upstreamThread = Thread(::upstreamLoop, "adblock-upstream").also { it.start() }
    }

    private fun tunLoop() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        var lastExpire = System.currentTimeMillis()
        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "TUN read failed", e)
                break
            }
            if (length < 0) break
            if (length == 0) continue
            try {
                handle(buffer, length)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Failed to handle packet", e)
            }
            val now = System.currentTimeMillis()
            if (now - lastExpire > EXPIRE_INTERVAL_MILLIS) {
                pending.expire(now)
                lastExpire = now
            }
        }
        Log.d(TAG, "TUN loop finished")
    }

    private fun handle(buffer: ByteArray, length: Int) {
        when (val verdict = engine.process(buffer, length)) {
            is Verdict.Blocked -> {
                writeToTun(verdict.packet)
                VpnStateHolder.record(verdict.host, verdict.type, verdict.decision, logEnabled, verdict.blockedAlias)
            }
            is Verdict.Forward -> {
                val targets = upstreams
                if (targets.isEmpty()) return
                val id = pending.register(verdict.context)
                val payload = engine.prepareUpstreamQuery(verdict, id)
                var sent = false
                for (target in targets) {
                    try {
                        channel.send(ByteBuffer.wrap(payload), target)
                        sent = true
                        break
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not send to $target: ${e.message}")
                    }
                }
                if (!sent) pending.remove(id)
                VpnStateHolder.record(verdict.host, verdict.type, verdict.decision, logEnabled)
            }
            is Verdict.Reply -> writeToTun(verdict.packet)
            is Verdict.Drop -> Unit
        }
    }

    private fun upstreamLoop() {
        val buffer = ByteBuffer.allocate(MAX_DNS_RESPONSE_SIZE)
        while (running.get()) {
            buffer.clear()
            try {
                channel.receive(buffer) ?: continue
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "Upstream receive failed", e)
                break
            }
            buffer.flip()
            val length = buffer.remaining()
            if (length < DnsCodec.HEADER_LENGTH) continue
            val response = ByteArray(length)
            buffer.get(response)
            val context = pending.remove(DnsCodec.transactionId(response)) ?: continue
            val reply = engine.handleUpstreamResponse(context, response, length)
            writeToTun(reply.packet)
            reply.blockedAlias?.let { target ->
                val question = context.query.question
                VpnStateHolder.recordCloaked(question.name, question.type, target, logEnabled)
            }
        }
        Log.d(TAG, "Upstream loop finished")
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(packet)
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "TUN write failed", e)
            }
        }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { channel.close() }
        runCatching { tun.close() } // unblocks the TUN reader
        runCatching { input.close() }
        runCatching { output.close() }
        tunThread?.interrupt()
        upstreamThread?.interrupt()
    }

    private companion object {
        const val TAG = "VpnSession"
        const val MAX_PACKET_SIZE = 32767
        const val MAX_DNS_RESPONSE_SIZE = 8192
        const val EXPIRE_INTERVAL_MILLIS = 5_000L
    }
}
