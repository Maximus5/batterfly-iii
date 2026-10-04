package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NetworkRetryTest {

    @Test
    fun `returns the first successful result without retrying`() = runBlocking {
        var calls = 0
        val result = withNetworkRetry {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries on IOException and returns the eventual success`() = runBlocking {
        var calls = 0
        val result = withNetworkRetry {
            calls++
            if (calls < 3) throw IOException("boom")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `gives up after maxAttempts and rethrows the last IOException`() = runBlocking {
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                withNetworkRetry(maxAttempts = 3) {
                    calls++
                    throw IOException("still boom")
                }
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun `does not retry a non-IOException`() = runBlocking {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withNetworkRetry {
                    calls++
                    throw IllegalStateException("not a network error")
                }
            }
        }
        assertEquals(1, calls)
    }
}
