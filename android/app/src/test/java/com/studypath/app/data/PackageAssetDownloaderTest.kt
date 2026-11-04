package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.UUID

class PackageAssetDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun verified() = CompletePackageFixture.candidate().let { TextPackageVerifier.verify(it.first, it.second, it.third) }
    private fun downloader(status: Int = 200, mime: String? = "application/pdf", length: Long = CompletePackageFixture.pdf.size.toLong(),
        bytes: ByteArray = CompletePackageFixture.pdf, closed: () -> Unit = {}) = PackageAssetDownloader(PackageAssetTransport {
        PackageAssetResponse(status, mime, length, ByteArrayInputStream(bytes), closed)
    })

    @Test fun exactPdfBytesAreStagedAndAllTemporaryBytesDisappearOnClose() {
        var closed = false
        val result = downloader(closed = { closed = true }).download(verified(), temporary.root)
        val file = requireNotNull(result.files["source_pdf"])
        assertArrayEquals(CompletePackageFixture.pdf, file.readBytes()); assertTrue(closed)
        result.close(); assertFalse(file.exists()); assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongStatusTypeLengthHashOrPdfSignatureNeverReturnsCompleteFiles() {
        val invalid = listOf(downloader(status = 302), downloader(status = 206), downloader(mime = "text/html"),
            downloader(mime = null), downloader(length = 1), downloader(bytes = CompletePackageFixture.pdf.reversedArray()),
            downloader(length = -1, bytes = CompletePackageFixture.pdf + 0))
        invalid.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { source.download(verified(), temporary.root) }
            assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun unknownLengthIsStillBoundedByTheManifestAndCancellationRemovesPartialBytes() {
        downloader(length = -1).download(verified(), temporary.root).use { assertEquals(1, it.files.size) }
        var checks = 0
        assertThrows(CancellationException::class.java) { downloader().download(verified(), temporary.root) {
            if (++checks >= 2) throw CancellationException("Stopped by learner")
        } }
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun localPrivateLinkLocalMulticastAndUniqueLocalDnsAnswersAreRejected() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "192.168.1.2", "172.16.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "100.64.0.1", "198.18.0.1", "192.0.2.1", "::1", "fc00::1", "fe80::1")) {
            assertFalse(ip, PackageAssetDownloader.publicAddress(InetAddress.getByName(ip)))
        }
        assertTrue(PackageAssetDownloader.publicAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test fun interruptedPrivateStagingIsRemovedOnRetryWhileLiveReturnedFilesAreKept() {
        val abandoned = File(temporary.root, ".package-${UUID.randomUUID()}.tmp").apply { mkdir(); File(this, "source.pdf").writeText("partial") }
        downloader().download(verified(), temporary.root).use { first ->
            assertFalse("A process-interrupted partial download must not leak private storage on retry", abandoned.exists())
            val file = requireNotNull(first.files["source_pdf"])
            downloader().download(verified(), temporary.root).use { second ->
                assertTrue(file.exists()); assertTrue(requireNotNull(second.files["source_pdf"]).exists())
            }
            assertTrue(file.exists())
        }
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }
}
