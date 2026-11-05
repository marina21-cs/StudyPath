package com.studypath.app.data

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PdfTransferTest {
    private fun response(request:Request,code:Int,body:ResponseBody,location:String?=null)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).apply{location?.let{header("Location",it)}}.build()
    @Test fun streamsBytesAndReportsActualProgress()=runBlocking {
        val bytes="%PDF-test-bytes".toByteArray();val target=Files.createTempFile("pdf-transfer", ".tmp").toFile()
        try { var progress=0L
            val client=OkHttpClient.Builder().addInterceptor{response(it.request(),200,bytes.toResponseBody())}.build()
            PdfLinkDownloader(client).download("https://example.edu/a.pdf",target){count,_->progress=count}
            assertArrayEquals(bytes,target.readBytes());assertEquals(bytes.size.toLong(),progress)
        } finally { target.delete() }
    }
    @Test fun rejectsRedirectDowngradeAndDeletesPartialFile()=runBlocking {
        val target=Files.createTempFile("pdf-transfer", ".tmp").toFile()
        try {
            val client=OkHttpClient.Builder().followRedirects(false).addInterceptor{response(it.request(),302,"".toResponseBody(),"http://example.edu/a.pdf")}.build()
            val failure=runCatching{PdfLinkDownloader(client).download("https://example.edu/a.pdf",target){_,_->}}
            assertTrue(failure.isFailure);assertFalse(target.exists())
        } finally { target.delete() }
    }
    @Test fun rejectsOversizedPayloadBeforeWriting()=runBlocking {
        val target=Files.createTempFile("pdf-transfer", ".tmp").toFile()
        try {
            val huge=object:ResponseBody(){override fun contentType():MediaType?=null;override fun contentLength()=PersonalStudyStore.MAX_PDF_BYTES+1;override fun source():okio.BufferedSource=okio.Buffer().writeUtf8("ignored")}
            val client=OkHttpClient.Builder().addInterceptor{response(it.request(),200,huge)}.build()
            assertTrue(runCatching{PdfLinkDownloader(client).download("https://example.edu/a.pdf",target){_,_->}}.isFailure);assertFalse(target.exists())
        } finally { target.delete() }
    }
}
