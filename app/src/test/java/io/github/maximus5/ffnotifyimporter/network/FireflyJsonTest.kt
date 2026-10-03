package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FireflyJsonTest {

    @Test
    fun `parseAccountList extracts id and name from a real-shaped response`() {
        val body = """
            {"data": [
                {"id": "2", "attributes": {"name": "Bank Account A", "type": "asset"}},
                {"id": "7", "attributes": {"name": "Bank Account B", "type": "asset"}}
            ], "meta": {"pagination": {"total_pages": 1}}}
        """.trimIndent()

        val accounts = parseAccountList(body)

        assertEquals(listOf(FireflyAccount("2", "Bank Account A"), FireflyAccount("7", "Bank Account B")), accounts)
    }

    @Test
    fun `parseAccountList skips an entry missing id or name rather than failing the whole list`() {
        val body = """
            {"data": [
                {"id": "2", "attributes": {"name": "Bank Account A"}},
                {"id": "", "attributes": {"name": "No id"}},
                {"id": "9", "attributes": {}}
            ]}
        """.trimIndent()

        assertEquals(listOf(FireflyAccount("2", "Bank Account A")), parseAccountList(body))
    }

    @Test
    fun `parseAccountList returns empty for malformed input instead of throwing`() {
        assertTrue(parseAccountList("not json").isEmpty())
        assertTrue(parseAccountList("""{"nope": true}""").isEmpty())
    }

    @Test
    fun `totalPages reads meta pagination total_pages`() {
        val body = """{"data": [], "meta": {"pagination": {"total_pages": 3}}}"""
        assertEquals(3, totalPages(body))
    }

    @Test
    fun `totalPages defaults to 1 when the response isn't paginated`() {
        assertEquals(1, totalPages("""{"data": []}"""))
        assertEquals(1, totalPages("not json"))
    }

    @Test
    fun `buildTransactionRequestBody includes every field set on the request`() {
        val request = FireflyTransactionRequest(
            type = "withdrawal",
            dateIso = "2026-03-01T12:00:00+00:00",
            amount = "12.50",
            description = "Shop1234",
            currencyCode = "EUR",
            sourceName = "Bank Account A",
            destinationName = "Shop1234",
            categoryName = "Groceries",
            tags = listOf("recurring", "groceries"),
            notes = "raw notification text",
            foreignAmount = "13.90",
            foreignCurrencyCode = "CHF",
            externalId = "msg-123",
        )

        val json = JSONObject(buildTransactionRequestBody(request))
        val split = json.getJSONArray("transactions").getJSONObject(0)

        assertEquals(true, json.getBoolean("error_if_duplicate_hash"))
        assertEquals("withdrawal", split.getString("type"))
        assertEquals("2026-03-01T12:00:00+00:00", split.getString("date"))
        assertEquals("12.50", split.getString("amount"))
        assertEquals("Shop1234", split.getString("description"))
        assertEquals("EUR", split.getString("currency_code"))
        assertEquals("Bank Account A", split.getString("source_name"))
        assertEquals("Shop1234", split.getString("destination_name"))
        assertEquals("Groceries", split.getString("category_name"))
        assertEquals(listOf("recurring", "groceries"), split.getJSONArray("tags").let { (0 until it.length()).map(it::getString) })
        assertEquals("raw notification text", split.getString("notes"))
        assertEquals("13.90", split.getString("foreign_amount"))
        assertEquals("CHF", split.getString("foreign_currency_code"))
        assertEquals("msg-123", split.getString("external_id"))
    }

    @Test
    fun `buildTransactionRequestBody omits unset optional fields rather than sending them null`() {
        val request = FireflyTransactionRequest(
            type = "deposit",
            dateIso = "2026-03-01T12:00:00+00:00",
            amount = "100.00",
            description = "Salary",
            currencyCode = "EUR",
        )

        val split = JSONObject(buildTransactionRequestBody(request)).getJSONArray("transactions").getJSONObject(0)

        assertTrue(!split.has("source_id"))
        assertTrue(!split.has("source_name"))
        assertTrue(!split.has("destination_id"))
        assertTrue(!split.has("destination_name"))
        assertTrue(!split.has("category_name"))
        assertTrue(!split.has("tags"))
        assertTrue(!split.has("notes"))
        assertTrue(!split.has("foreign_amount"))
        assertTrue(!split.has("foreign_currency_code"))
        assertTrue(!split.has("external_id"))
    }

    @Test
    fun `buildTransactionRequestBody sends an id instead of a name when both are set, not both`() {
        val request = FireflyTransactionRequest(
            type = "withdrawal",
            dateIso = "2026-03-01T12:00:00+00:00",
            amount = "12.50",
            description = "Shop1234",
            currencyCode = "EUR",
            sourceId = "2",
            sourceName = "Bank Account A",
            destinationId = "9",
            destinationName = "Shop1234",
        )

        val split = JSONObject(buildTransactionRequestBody(request)).getJSONArray("transactions").getJSONObject(0)

        assertEquals("2", split.getString("source_id"))
        assertTrue(!split.has("source_name"))
        assertEquals("9", split.getString("destination_id"))
        assertTrue(!split.has("destination_name"))
    }

    @Test
    fun `parseCreatedTransactionId extracts data id from a real-shaped response`() {
        val body = """{"data": {"type": "transactions", "id": "42", "attributes": {}}}"""
        assertEquals("42", parseCreatedTransactionId(body))
    }

    @Test
    fun `parseCreatedTransactionId returns null for malformed input instead of throwing`() {
        assertNull(parseCreatedTransactionId("not json"))
        assertNull(parseCreatedTransactionId("""{"nope": true}"""))
    }
}
