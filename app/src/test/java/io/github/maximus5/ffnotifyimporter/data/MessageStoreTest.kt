package io.github.maximus5.ffnotifyimporter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {

    @Test
    fun `identical notification within the dedup window is dropped`() {
        var now = 0L
        val store = MessageStore(dedupWindowMillis = 1000, clock = { now })

        assertNotNull(store.add("bank.app", "Bank", "Payment", "You spent 100"))
        now += 500
        assertNull(store.add("bank.app", "Bank", "Payment", "You spent 100"))

        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `identical notification after the dedup window is kept as a new entry`() {
        var now = 0L
        val store = MessageStore(dedupWindowMillis = 1000, clock = { now })

        assertNotNull(store.add("bank.app", "Bank", "Payment", "You spent 100"))
        now += 1001
        assertNotNull(store.add("bank.app", "Bank", "Payment", "You spent 100"))

        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun `add returns the recorded message on success, null when dropped as a duplicate`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        val recorded = store.add("bank.a", "Bank A", "Payment", "text", timestampMillis = 42L)

        assertEquals(CapturedMessage("bank.a", "Bank A", "Payment", "text", timestampMillis = 42L), recorded)
        assertNull(store.add("bank.a", "Bank A", "Payment", "text", timestampMillis = 42L))
    }

    @Test
    fun `add stores the given timestampMillis instead of clock() when supplied`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 999_999L })

        store.add("bank.a", "Bank A", "Payment", "text", timestampMillis = 42L)

        assertEquals(42L, store.snapshot().single().timestampMillis)
    }

    @Test
    fun `add's dedup window compares against the given timestampMillis, not clock()`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        assertNotNull(store.add("bank.a", "Bank A", "Payment", "text", timestampMillis = 10_000L))
        // Same content, a backdated timestamp just outside the window from the first one -
        // clock() never changed, so this only works if the window compares against the supplied
        // timestamps themselves, not against clock().
        assertNotNull(store.add("bank.a", "Bank A", "Payment", "text", timestampMillis = 8_999L))
        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun `different app, title or text is never a duplicate`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        assertNotNull(store.add("bank.a", "Bank A", "Payment", "You spent 100"))
        assertNotNull(store.add("bank.b", "Bank B", "Payment", "You spent 100"))
        assertNotNull(store.add("bank.a", "Bank A", "Refund", "You spent 100"))
        assertNotNull(store.add("bank.a", "Bank A", "Payment", "You spent 200"))

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
    fun `a message captured later but timestamped earlier is inserted in sorted position, not at the front`() {
        // A real on-device case: timestampMillis is sbn.postTime, not "now" - a notification
        // still sitting unswiped in the shade gets replayed (and re-captured) when the listener
        // service reconnects, well after a genuinely newer notification was already recorded.
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        store.add("bank.a", "Bank A", "Newest", "text 1", timestampMillis = 3000)
        store.add("bank.a", "Bank A", "Middle", "text 2", timestampMillis = 2000)
        // Captured last, but its own (replayed) postTime is the oldest of the three.
        store.add("bank.a", "Bank A", "Oldest, captured last", "text 3", timestampMillis = 1000)

        assertEquals(listOf("Newest", "Middle", "Oldest, captured last"), store.snapshot().map { it.title })
    }

    @Test
    fun `an out-of-order insert lands between the correct neighbors, not just not-at-the-front`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })

        store.add("bank.a", "Bank A", "A", "text 1", timestampMillis = 3000)
        store.add("bank.a", "Bank A", "B", "text 2", timestampMillis = 1000)
        // Belongs strictly between A (3000) and B (1000) despite being captured last.
        store.add("bank.a", "Bank A", "C", "text 3", timestampMillis = 2000)

        assertEquals(listOf("A", "C", "B"), store.snapshot().map { it.title })
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

    @Test
    fun `remove deletes a matching message and notifies listeners`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        store.add("bank.a", "Bank A", "Keep", "text")
        store.add("bank.b", "Bank B", "Also keep", "text")
        val target = store.snapshot().first { it.title == "Keep" }

        assertTrue(store.remove(target))

        assertEquals(1, store.snapshot().size)
        assertEquals("Also keep", store.snapshot().single().title)
    }

    @Test
    fun `remove returns false and does nothing for a message that is not present`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        store.add("bank.a", "Bank A", "Keep", "text")
        val notPresent = CapturedMessage("bank.x", "Bank X", "Nope", "text", timestampMillis = 999)

        assertFalse(store.remove(notPresent))
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `remove invokes onMessageRemoved only when something was actually removed`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val removed = mutableListOf<CapturedMessage>()
        store.onMessageRemoved = { removed.add(it) }
        store.add("bank.a", "Bank A", "Keep", "text")
        val target = store.snapshot().single()

        store.remove(CapturedMessage("bank.x", "Bank X", "Nope", "text", timestampMillis = 999))
        assertTrue(removed.isEmpty())

        store.remove(target)
        assertEquals(listOf(target), removed)
    }

    @Test
    fun `setTransactionId updates the matching entry and keeps its position`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        store.add("bank.a", "Bank A", "First", "text")
        store.add("bank.b", "Bank B", "Second", "text")
        val target = store.snapshot().first { it.title == "First" }

        val updated = store.setTransactionId(target, "42")

        assertEquals("42", updated?.fireflyTransactionId)
        assertEquals(listOf("Second", "First"), store.snapshot().map { it.title })
        assertEquals("42", store.snapshot().first { it.title == "First" }.fireflyTransactionId)
    }

    @Test
    fun `setTransactionId matches by identity, ignoring a stale fireflyTransactionId on the argument`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        store.add("bank.a", "Bank A", "Payment", "text")
        val staleCopy = store.snapshot().single().copy(fireflyTransactionId = "old-and-wrong")

        val updated = store.setTransactionId(staleCopy, "99")

        assertEquals("99", updated?.fireflyTransactionId)
    }

    @Test
    fun `setTransactionId returns null and changes nothing for a message that is not present`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        store.add("bank.a", "Bank A", "Keep", "text")
        val notPresent = CapturedMessage("bank.x", "Bank X", "Nope", "text", timestampMillis = 999)

        assertEquals(null, store.setTransactionId(notPresent, "1"))
        assertEquals(null, store.snapshot().single().fireflyTransactionId)
    }

    @Test
    fun `setTransactionId invokes onMessageUpdated with the updated message`() {
        val store = MessageStore(dedupWindowMillis = 1000, clock = { 0L })
        val updated = mutableListOf<CapturedMessage>()
        store.onMessageUpdated = { updated.add(it) }
        store.add("bank.a", "Bank A", "Payment", "text")
        val target = store.snapshot().single()

        store.setTransactionId(target, "7")

        assertEquals("7", updated.single().fireflyTransactionId)
    }
}
