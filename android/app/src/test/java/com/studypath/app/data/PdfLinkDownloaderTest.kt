package com.studypath.app.data
import org.junit.Test
import org.junit.Assert.*
class PdfLinkDownloaderTest{
 @Test fun acceptsDirectHttps(){assertEquals("example.edu",PdfLinkDownloader.validate("https://example.edu/file.pdf").host)}
 @Test(expected=IllegalArgumentException::class) fun rejectsPhonePath(){PdfLinkDownloader.validate("/Downloads/file.pdf")}
 @Test(expected=IllegalArgumentException::class) fun rejectsCleartext(){PdfLinkDownloader.validate("http://example.edu/file.pdf")}
 @Test(expected=IllegalArgumentException::class) fun rejectsEmbeddedCredentials(){PdfLinkDownloader.validate("https://user:secret@example.edu/file.pdf")}
}
