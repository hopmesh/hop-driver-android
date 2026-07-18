package sh.hopme.driver

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class MediaDiskReference(
    val name: String,
    val peer: String,
    val conversation: String,
)

internal data class MediaDiskBlob(
    val bytes: ByteArray,
    val peer: String,
    val conversation: String,
)

internal data class MediaDiskSnapshot(
    val references: List<MediaDiskReference>,
    val valid: Boolean = true,
)

internal enum class MediaDiskResult {
    COMMITTED,
    QUOTA,
    IO_ERROR,
}

/**
 * Owns media-directory accounting and mutation. A transaction holds one monitor across reconciliation,
 * quota admission, blob writes, the caller's durable commit, rollback, and eviction cleanup.
 */
internal class MediaDisk(
    private val directory: File,
    private val limits: RetentionLimits,
    private val encode: (ByteArray) -> ByteArray,
    private val decode: (ByteArray) -> ByteArray?,
) {
    private val transactionDirectory = File(directory.parentFile, ".${directory.name}.transaction")
    private val quarantineDirectory = File(directory.parentFile, "${directory.name}.quarantine")

    fun name(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING,
        )
    }

    fun commit(
        durableSnapshot: () -> MediaDiskSnapshot,
        blobs: List<MediaDiskBlob>,
        resultingReferences: List<MediaDiskReference>,
        durableCommit: () -> Boolean,
    ): MediaDiskResult = commit(durableSnapshot, blobs, { resultingReferences }, durableCommit)

    @Synchronized
    fun commit(
        durableSnapshot: () -> MediaDiskSnapshot,
        blobs: List<MediaDiskBlob>,
        resultingReferences: () -> List<MediaDiskReference>,
        durableCommit: () -> Boolean,
    ): MediaDiskResult {
        if (blobs.any { it.bytes.size.toLong() > limits.attachmentBytes }) return MediaDiskResult.QUOTA
        val before = runCatching(durableSnapshot).getOrNull()
            ?.takeIf { it.valid } ?: return MediaDiskResult.IO_ERROR
        val beforeNames = before.references.mapTo(HashSet()) { it.name }
        if (!ensureDirectory() || !recoverTransaction(beforeNames)) return MediaDiskResult.IO_ERROR
        val files = scanAndReconcile(beforeNames) ?: return MediaDiskResult.IO_ERROR

        val projectedReferences = runCatching(resultingReferences).getOrNull()
            ?: return MediaDiskResult.IO_ERROR
        val resultingNames = projectedReferences.mapTo(HashSet()) { it.name }
        val prepared = LinkedHashMap<String, ByteArray>()
        for (blob in blobs) {
            val blobName = name(blob.bytes)
            if (blobName in resultingNames && blobName !in prepared && blobName !in files) {
                prepared[blobName] = runCatching { encode(blob.bytes) }.getOrNull()
                    ?: return MediaDiskResult.IO_ERROR
            }
        }

        val projectedSizes = HashMap<String, Long>()
        for ((fileName, size) in files) if (fileName in resultingNames) projectedSizes[fileName] = size
        for ((fileName, bytes) in prepared) projectedSizes[fileName] = bytes.size.toLong()
        if (!withinQuota(projectedReferences, projectedSizes)) return MediaDiskResult.QUOTA

        val staged = ArrayList<String>()
        val created = ArrayList<String>()
        try {
            check(transactionDirectory.mkdirs() || transactionDirectory.isDirectory)
            for (fileName in files.keys.sorted()) {
                if (fileName in resultingNames) continue
                atomicMove(File(directory, fileName), File(transactionDirectory, fileName))
                staged.add(fileName)
            }
            for ((fileName, bytes) in prepared) {
                writeAtomic(File(directory, fileName), bytes)
                created.add(fileName)
            }
        } catch (_: Exception) {
            finishTransaction(beforeNames, staged, created)
            return MediaDiskResult.IO_ERROR
        }

        val committed = runCatching(durableCommit).getOrDefault(false)
        val target = if (committed) {
            resultingNames
        } else {
            runCatching(durableSnapshot).getOrNull()?.takeIf { it.valid }
                ?.references?.mapTo(HashSet()) { it.name } ?: beforeNames
        }
        val cleaned = finishTransaction(target, staged, created)
        return if (committed && cleaned) MediaDiskResult.COMMITTED else MediaDiskResult.IO_ERROR
    }

    @Synchronized
    fun reconcile(snapshot: MediaDiskSnapshot): MediaDiskResult {
        if (!snapshot.valid) return MediaDiskResult.IO_ERROR
        return commit({ snapshot }, emptyList(), snapshot.references) { true }
    }

    @Synchronized
    fun read(name: String): ByteArray? {
        if (!isOwnedName(name) || !ensureDirectory()) return null
        val file = File(directory, name)
        val attributes = attributes(file) ?: return null
        if (!attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() > limits.attachmentBytes + MAX_STORAGE_OVERHEAD) {
            quarantine(file)
            return null
        }
        val stored = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plaintext = decode(stored) ?: stored
        if (plaintext.size.toLong() > limits.attachmentBytes || this.name(plaintext) != name) {
            quarantine(file)
            return null
        }
        return plaintext
    }

    @Synchronized
    internal fun usageBytesForTest(): Long {
        if (!ensureDirectory()) return Long.MAX_VALUE
        return Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.sumOf { path -> attributes(path.toFile())?.takeIf { it.isRegularFile }?.size() ?: 0L }
        }
    }

    private fun withinQuota(references: List<MediaDiskReference>, sizes: Map<String, Long>): Boolean {
        val global = runCatching {
            sizes.values.fold(0L) { total, size -> Math.addExact(total, size) }
        }.getOrNull() ?: return false
        if (global > limits.globalMediaBytes) return false
        for (peer in references.groupBy { it.peer }.values) {
            val bytes = peer.map { it.name }.toSet().sumOf { sizes[it] ?: 0L }
            if (bytes > limits.peerMediaBytes) return false
        }
        for (conversation in references.groupBy { it.conversation }.values) {
            val bytes = conversation.map { it.name }.toSet().sumOf { sizes[it] ?: 0L }
            if (bytes > limits.conversationMediaBytes) return false
        }
        return true
    }

    private fun scanAndReconcile(referenced: Set<String>): MutableMap<String, Long>? {
        val files = LinkedHashMap<String, Long>()
        var entries = 0
        var inspectedBytes = 0L
        return runCatching {
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (path in stream) {
                    entries += 1
                    check(entries <= limits.mediaDirectoryFiles)
                    val file = path.toFile()
                    val fileName = file.name
                    val attributes = attributes(file)
                    if (attributes?.isRegularFile == true) {
                        inspectedBytes = Math.addExact(inspectedBytes, attributes.size())
                        check(inspectedBytes <= limits.mediaDirectoryScanBytes)
                    }
                    if (!isOwnedName(fileName) || attributes == null || !attributes.isRegularFile ||
                        attributes.isSymbolicLink || attributes.size() > limits.attachmentBytes + MAX_STORAGE_OVERHEAD) {
                        check(quarantine(file))
                    } else if (fileName !in referenced) {
                        check(file.delete())
                    } else {
                        files[fileName] = attributes.size()
                    }
                }
            }
            files
        }.getOrNull()
    }

    private fun recoverTransaction(referenced: Set<String>): Boolean {
        if (!transactionDirectory.exists()) return true
        val attributes = attributes(transactionDirectory)
        if (attributes == null || !attributes.isDirectory || attributes.isSymbolicLink) {
            return quarantine(transactionDirectory) && ensureTransactionAbsent()
        }
        var entries = 0
        var inspectedBytes = 0L
        val recovered = runCatching {
            Files.newDirectoryStream(transactionDirectory.toPath()).use { stream ->
                for (path in stream) {
                    entries += 1
                    check(entries <= limits.mediaDirectoryFiles)
                    val staged = path.toFile()
                    val stagedAttributes = attributes(staged)
                    if (stagedAttributes != null) {
                        inspectedBytes = Math.addExact(inspectedBytes, stagedAttributes.size())
                        check(inspectedBytes <= limits.mediaDirectoryScanBytes)
                    }
                    check(isOwnedName(staged.name) && stagedAttributes != null &&
                        stagedAttributes.isRegularFile && !stagedAttributes.isSymbolicLink)
                    val target = File(directory, staged.name)
                    if (staged.name in referenced && !target.exists()) atomicMove(staged, target)
                    else check(staged.delete())
                }
            }
            transactionDirectory.delete()
        }.getOrDefault(false)
        if (!recovered) quarantine(transactionDirectory)
        return recovered
    }

    private fun finishTransaction(target: Set<String>, staged: List<String>, created: List<String>): Boolean {
        var success = true
        for (fileName in created) {
            if (fileName !in target) success = File(directory, fileName).delete() && success
        }
        for (fileName in staged) {
            val backup = File(transactionDirectory, fileName)
            if (!backup.exists()) continue
            val destination = File(directory, fileName)
            success = if (fileName in target && !destination.exists()) {
                runCatching { atomicMove(backup, destination); true }.getOrDefault(false) && success
            } else {
                backup.delete() && success
            }
        }
        if (transactionDirectory.exists()) success = transactionDirectory.delete() && success
        return success
    }

    private fun ensureDirectory(): Boolean = runCatching {
        directory.parentFile?.mkdirs()
        if (directory.exists()) {
            val attributes = attributes(directory)
            if (attributes == null || !attributes.isDirectory || attributes.isSymbolicLink) {
                check(quarantine(directory))
            }
        }
        directory.mkdirs() || directory.isDirectory
    }.getOrDefault(false)

    private fun ensureTransactionAbsent(): Boolean = !transactionDirectory.exists() || transactionDirectory.delete()

    private fun writeAtomic(destination: File, bytes: ByteArray) {
        val temporary = File(directory, ".${destination.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            atomicMove(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun quarantine(file: File): Boolean = runCatching {
        quarantineDirectory.mkdirs()
        val target = File(quarantineDirectory, "${UUID.randomUUID()}-${file.name}")
        Files.move(file.toPath(), target.toPath(), LinkOption.NOFOLLOW_LINKS)
        true
    }.getOrDefault(false)

    private fun attributes(file: File): BasicFileAttributes? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    private fun isOwnedName(name: String): Boolean =
        name.length == 43 && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val MAX_STORAGE_OVERHEAD = 256L
    }
}
