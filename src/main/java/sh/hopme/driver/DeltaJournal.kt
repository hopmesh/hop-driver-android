package sh.hopme.driver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class DeltaRecord(val id: ByteArray, val payload: ByteArray?)
internal data class DeltaReplay(val records: List<DeltaRecord>, val quarantined: Boolean)

/** Length-framed, checksummed append-only records. Each append is flushed and fd-synced. */
internal class DeltaJournal(
    private val file: File,
    private val dbKey: ByteArray,
    private val maximumBytes: Long,
    private val maximumRecords: Int,
    private val maximumRecordBytes: Int,
) {
    private var knownRecords: Int? = null

    @Synchronized
    fun append(id: ByteArray, payload: ByteArray?): Boolean {
        if (id.isEmpty() || id.size > 64 || (payload?.size ?: 0) > maximumRecordBytes) return false
        if (knownRecords == null) {
            val replay = replayInternal()
            knownRecords = replay.records.size
            if (replay.quarantined) return false
        }
        if (knownRecords!! >= maximumRecords) return false
        val plaintext = encodeRecord(id, payload)
        val sealed = MirrorCrypto.seal(dbKey, plaintext)
        val added = 4L + sealed.size
        val current = if (file.exists()) file.length() else MAGIC.size.toLong()
        if (added > maximumBytes - current) return false
        return runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                FileOutputStream(file).use { stream ->
                    stream.write(MAGIC)
                    stream.fd.sync()
                }
            }
            FileOutputStream(file, true).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(sealed.size)
                out.write(sealed)
                out.flush()
                stream.fd.sync()
            }
            knownRecords = knownRecords!! + 1
            true
        }.getOrDefault(false)
    }

    @Synchronized fun replay(): DeltaReplay = replayInternal().also { knownRecords = it.records.size }

    @Synchronized
    fun reset(): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.reset")
        FileOutputStream(temporary).use { stream ->
            stream.write(MAGIC)
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        knownRecords = 0
        true
    }.getOrDefault(false)

    private fun replayInternal(): DeltaReplay {
        if (!file.exists()) return DeltaReplay(emptyList(), false)
        if (!file.isFile || file.length() > maximumBytes || file.length() < MAGIC.size) {
            quarantine()
            return DeltaReplay(emptyList(), true)
        }
        val records = ArrayList<DeltaRecord>()
        val valid = runCatching {
            FileInputStream(file).use { stream ->
                val input = DataInputStream(stream)
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                check(magic.contentEquals(MAGIC))
                while (input.available() > 0) {
                    if (records.size >= maximumRecords) error("journal record limit exceeded")
                    val length = try { input.readInt() } catch (_: EOFException) { error("truncated journal frame") }
                    if (length <= 0 || length > maximumRecordBytes + CRYPTO_OVERHEAD) error("invalid journal frame")
                    if (length > input.available()) error("truncated journal payload")
                    val sealed = ByteArray(length)
                    input.readFully(sealed)
                    val plaintext = if (dbKey.isEmpty()) sealed else MirrorCrypto.open(dbKey, sealed)
                        ?: error("journal authentication failed")
                    records.add(decodeRecord(plaintext))
                }
            }
            true
        }.getOrDefault(false)
        if (valid) return DeltaReplay(records, false)
        quarantine()
        return DeltaReplay(records, true)
    }

    private fun encodeRecord(id: ByteArray, payload: ByteArray?): ByteArray {
        val core = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeByte(VERSION)
                out.writeByte(if (payload == null) 0 else 1)
                out.writeShort(id.size)
                out.writeInt(payload?.size ?: 0)
                out.write(id)
                payload?.let(out::write)
            }
        }.toByteArray()
        return core + MessageDigest.getInstance("SHA-256").digest(core)
    }

    private fun decodeRecord(bytes: ByteArray): DeltaRecord {
        check(bytes.size >= 1 + 1 + 2 + 4 + 1 + DIGEST_BYTES)
        val core = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val digest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        check(MessageDigest.getInstance("SHA-256").digest(core).contentEquals(digest))
        return DataInputStream(ByteArrayInputStream(core)).use { input ->
            check(input.readUnsignedByte() == VERSION)
            val hasPayload = input.readUnsignedByte()
            check(hasPayload == 0 || hasPayload == 1)
            val idLength = input.readUnsignedShort()
            val payloadLength = input.readInt()
            check(idLength in 1..64 && payloadLength in 0..maximumRecordBytes)
            check(core.size == 1 + 1 + 2 + 4 + idLength + payloadLength)
            val id = ByteArray(idLength).also(input::readFully)
            val payload = ByteArray(payloadLength).also(input::readFully).takeIf { hasPayload == 1 }
            check(hasPayload == 1 || payloadLength == 0)
            DeltaRecord(id, payload)
        }
    }

    private fun quarantine() {
        runCatching {
            val target = File(file.parentFile, "${file.name}.quarantine")
            if (target.exists()) target.delete()
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        knownRecords = null
    }

    private companion object {
        val MAGIC = "HOPDELTA1\n".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val DIGEST_BYTES = 32
        const val CRYPTO_OVERHEAD = 128
    }
}
