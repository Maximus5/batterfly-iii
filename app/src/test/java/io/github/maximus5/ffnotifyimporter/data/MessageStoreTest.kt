package io.github.maximus5.ffnotifyimporter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {

    @Test
    fun `identical notification within the dedup window is dropped`() {
        var now = 0L
        val store = MessageStore(dedupWindowMillis = 1000, clock = { now })

        assertTrue(store.add("bank.app", "Bank", "Payment", "You spent 100"))
        now += 500
        assertFalse(store.add("bank.app", "Bank", "Payment", "You spent 100"))

        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `identical notification after the dedup window is kept as a new entry`() {
        var now = 0L
        val store = MessageStore(dedupWindowMillis = 1000, clock = { now })

        assertTrue(store.add("bank.app", "Bank", "Payment", "You spent 100"))
        now += 1001
        assertTrue(store.add("bank.app", "Bank", "Payment", "You spent 100"))

        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun `different app, title or text is never a duplicate`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        assertTrue(store.add("bank.a", "Bank A", "Payment", "You spent 100"))
        assertTrue(store.add("bank.b", "Bank B", "Payment", "You spent 100"))
        assertTrue(store.add("bank.a", "Bank A", "Refund", "You spent 100"))
        assertTrue(store.add("bank.a", "Bank A", "Payment", "You spent 200"))

        assertEquals(4, store.snapshot().size)
    }

    @Test
    fun `newest message is first in the snapshot`() {
        var now = 0L
        val store = MessageStore(dedupWindowMillis = 1000, clock = { now })

        store.add("bank.a", "Bank A", "First", "text")
        now += 2000
        store.add("bank.a", "Bank A", "Second", "text")

        assertEquals("Second", store.snapshot().first().title)
    }

    @Test
    fun `listener receives the current snapshot immediately and on every change`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val received = mutableListOf<List<CapturedMessage>>()

        store.addListener { received.add(it) }
        assertEquals(1, received.size)
        assertTrue(received.first().isEmpty())

        store.add("bank.a", "Bank A", "Payment", "You spent 100")
        assertEquals(2, received.size)
        assertEquals(1, received.last().size)
    }

    @Test
    fun `add invokes onNewMessage only for genuinely new entries`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val notified = mutableListOf<CapturedMessage>()
        store.onNewMessage = { notified.add(it) }

        store.add("bank.a", "Bank A", "Payment", "You spent 100")
        store.add("bank.a", "Bank A", "Payment", "You spent 100") // duplicate, no callback

        assertEquals(1, notified.size)
        assertEquals("Payment", notified.single().title)
    }

    @Test
    fun `restore seeds oldest-first input with newest ending up first`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val oldestFirst = listOf(
            CapturedMessage("bank.a", "Bank A", "Oldest", "text", timestampMillis = 1),
            CapturedMessage("bank.a", "Bank A", "Middle", "text", timestampMillis = 2),
            CapturedMessage("bank.a", "Bank A", "Newest", "text", timestampMillis = 3),
        )

        store.restore(oldestFirst)

        assertEquals(listOf("Newest", "Middle", "Oldest"), store.snapshot().map { it.title })
    }

    @Test
    fun `restore does not invoke onNewMessage`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val notified = mutableListOf<CapturedMessage>()
        store.onNewMessage = { notified.add(it) }

        store.restore(listOf(CapturedMessage("bank.a", "Bank A", "Payment", "text", 1)))

        assertTrue(notified.isEmpty())
    }
}
