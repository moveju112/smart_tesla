package com.wemade.teslamacro.data.update

import android.content.ContextWrapper
import app.cash.paparazzi.Paparazzi
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDownloadTest {
    @get:Rule val paparazzi = Paparazzi()
    @get:Rule val temporary = TemporaryFolder()

    /** 테스트 다운로드는 임시 캐시에만 기록한다. */
    private fun context() = object : ContextWrapper(paparazzi.context) {
        override fun getCacheDir(): File = temporary.root
    }

    /** 전역 진행 상태를 다른 업데이트 테스트와 분리한다. */
    @After fun resetState() { AppUpdater.state.value = null }

    /** 정상 수신은 바이트를 보존하고 HTTP 연결을 해제한다. */
    @Test fun successfulDownloadClosesConnection() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val connection = FakeConnection { ByteArrayInputStream(bytes) }
        val file = AppUpdater.download(context(), "1", "https://example.invalid/app.apk") { connection }
        assertNotNull(file)
        assertArrayEquals(bytes, file!!.readBytes())
        assertTrue(connection.disconnected)
    }

    /** 입력 스트림 오류가 나도 이전·부분 APK를 설치 대상으로 남기지 않는다. */
    @Test fun failedDownloadDeletesFileAndClosesConnection() = runTest {
        val file = File(temporary.root, "update.apk").apply { writeBytes(byteArrayOf(9)) }
        val connection = FakeConnection { throw IOException("offline") }
        assertNull(AppUpdater.download(context(), "1", "https://example.invalid/app.apk") { connection })
        assertFalse(file.exists())
        assertTrue(connection.disconnected)
    }

    /** 수신 중 취소되면 다음 청크를 읽지 않고 부분 파일과 연결을 정리한다. */
    @Test fun cancelledDownloadStopsReadingAndDeletesPartialFile() = runTest {
        var reads = 0
        lateinit var job: Job
        val connection = FakeConnection {
            object : InputStream() {
                override fun read(): Int = error("청크 단위 읽기만 사용")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    check(reads == 1) { "취소 뒤 추가 읽기" }
                    buffer[offset] = 1
                    job.cancel()
                    return 1
                }
            }
        }
        job = launch {
            AppUpdater.download(context(), "1", "https://example.invalid/app.apk") { connection }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, reads)
        assertFalse(File(temporary.root, "update.apk").exists())
        assertTrue(connection.disconnected)
    }

    /** 실제 서버 대신 스트림과 종료 여부만 제공한다. */
    private class FakeConnection(private val stream: () -> InputStream) :
        HttpURLConnection(URL("https://example.invalid/app.apk")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream = stream()
    }
}
