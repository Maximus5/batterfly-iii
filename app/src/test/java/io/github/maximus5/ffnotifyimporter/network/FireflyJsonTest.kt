package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

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
    fun `parseAccountList skips accounts deactivated in Firefly`() {
        val body = """
            {"data": [
                {"id": "2", "attributes": {"name": "Bank Account A", "active": true}},
                {"id": "7", "attributes": {"name": "Closed Account", "active": false}},
                {"id": "9", "attributes": {"name": "No flag"}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(FireflyAccount("2", "Bank Account A"), FireflyAccount("9", "No flag")),
            parseAccountList(body),
        )
    }

    @Test
    fun `parseAccountList returns empty for malformed input instead of throwing`() {
        assertTrue(parseAccountList("not json").isEmpty())
        assertTrue(parseAccountList("""{"nope": true}""").isEmpty())
    }

    @Test
    fun `parseCurrencyList extracts code and enabled from a real-shaped response`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"code": "EUR", "enabled": true}},
                {"id": "2", "attributes": {"code": "USD", "enabled": false}}
            ], "meta": {"pagination": {"total_pages": 1}}}
        """.trimIndent()

        val currencies = parseCurrencyList(body)

        assertEquals(listOf(FireflyCurrency("EUR", true), FireflyCurrency("USD", false)), currencies)
    }

    @Test
    fun `parseCurrencyList extracts name and symbol`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"code": "EUR", "name": "Euro", "symbol": "€", "enabled": true}}
            ]}
        """.trimIndent()

        assertEquals(listOf(FireflyCurrency("EUR", true, "Euro", "€")), parseCurrencyList(body))
    }

    @Test
    fun `parseCurrencyList reads the primary flag from primary or the older default key`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"code": "EUR", "enabled": true, "primary": true, "default": true}},
                {"id": "2", "attributes": {"code": "USD", "enabled": true, "default": true}},
                {"id": "3", "attributes": {"code": "PLN", "enabled": true, "primary": false, "default": false}}
            ]}
        """.trimIndent()

        assertEquals(listOf(true, true, false), parseCurrencyList(body).map { it.primary })
    }

    @Test
    fun `parseCurrencyList skips an entry missing a code rather than failing the whole list`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"code": "EUR", "enabled": true}},
                {"id": "2", "attributes": {"enabled": true}}
            ]}
        """.trimIndent()

        assertEquals(listOf(FireflyCurrency("EUR", true)), parseCurrencyList(body))
    }

    @Test
    fun `parseCurrencyList returns empty for malformed input instead of throwing`() {
        assertTrue(parseCurrencyList("not json").isEmpty())
        assertTrue(parseCurrencyList("""{"nope": true}""").isEmpty())
    }

    @Test
    fun `parseCategoryList extracts id and name from a real-shaped response`() {
        val body = """
            {"data": [
                {"id": "3", "attributes": {"name": "Groceries", "notes": null}},
                {"id": "5", "attributes": {"name": "Transport", "notes": null}}
            ], "meta": {"pagination": {"total_pages": 1}}}
        """.trimIndent()

        val categories = parseCategoryList(body)

        assertEquals(listOf(FireflyCategory("3", "Groceries"), FireflyCategory("5", "Transport")), categories)
    }

    @Test
    fun `parseTagList reads the tag name from a real-shaped response`() {
        val body = """
            {"data": [
                {"type": "tags", "id": "1", "attributes": {"tag": "travel", "date": null, "description": null}},
                {"type": "tags", "id": "2", "attributes": {"tag": "shared", "date": null, "description": null}},
                {"type": "tags", "id": "3", "attributes": {"tag": "", "date": null}},
                {"type": "tags", "id": "4", "attributes": {}}
            ], "meta": {"pagination": {"total_pages": 1}}}
        """.trimIndent()

        assertEquals(listOf("travel", "shared"), parseTagList(body))
    }

    @Test
    fun `parseTagList returns an empty list for a body that is not JSON`() {
        assertEquals(emptyList<String>(), parseTagList("<html>"))
    }

    @Test
    fun `parseCategoryList skips an entry missing id or name rather than failing the whole list`() {
        val body = """
            {"data": [
                {"id": "3", "attributes": {"name": "Groceries"}},
                {"id": "", "attributes": {"name": "No id"}},
                {"id": "9", "attributes": {}}
            ]}
        """.trimIndent()

        assertEquals(listOf(FireflyCategory("3", "Groceries")), parseCategoryList(body))
    }

    @Test
    fun `parseCategoryList returns empty for malformed input instead of throwing`() {
        assertTrue(parseCategoryList("not json").isEmpty())
        assertTrue(parseCategoryList("""{"nope": true}""").isEmpty())
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

    @Test
    fun `parseTransactionList extracts every field from a real-shaped response`() {
        val body = """
            {"data": [
                {"id": "99", "attributes": {"transactions": [
                    {"date": "2026-03-01T12:00:00+01:00", "amount": "12.50", "currency_code": "EUR",
                     "description": "Shop1234", "source_id": "2", "source_name": "Bank Account A",
                     "destination_id": "9", "destination_name": "Shop1234"}
                ]}}
            ]}
        """.trimIndent()

        val candidates = parseTransactionList(body)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("99", candidate.groupId)
        assertEquals(Instant.parse("2026-03-01T11:00:00Z"), candidate.date)
        assertEquals(BigDecimal("12.50"), candidate.amount)
        assertEquals("EUR", candidate.currencyCode)
        assertEquals("Shop1234", candidate.description)
        assertEquals("2", candidate.sourceId)
        assertEquals("Bank Account A", candidate.sourceName)
        assertEquals("9", candidate.destinationId)
        assertEquals("Shop1234", candidate.destinationName)
    }

    @Test
    fun `parseTransactionList flattens every split of every group`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"transactions": [
                    {"date": "2026-03-01T12:00:00Z", "amount": "5.00", "currency_code": "EUR", "description": "a"}
                ]}},
                {"id": "2", "attributes": {"transactions": [
                    {"date": "2026-03-01T13:00:00Z", "amount": "6.00", "currency_code": "EUR", "description": "b"},
                    {"date": "2026-03-01T13:00:00Z", "amount": "7.00", "currency_code": "EUR", "description": "c"}
                ]}}
            ]}
        """.trimIndent()

        val candidates = parseTransactionList(body)

        assertEquals(3, candidates.size)
        assertEquals(setOf("a", "b", "c"), candidates.map { it.description }.toSet())
    }

    @Test
    fun `parseTransactionList treats a zero account id as unknown, not a real Firefly id`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"transactions": [
                    {"date": "2026-03-01T12:00:00Z", "amount": "5.00", "currency_code": "EUR",
                     "description": "a", "source_id": "0", "destination_id": "9"}
                ]}}
            ]}
        """.trimIndent()

        val candidate = parseTransactionList(body).single()

        assertNull(candidate.sourceId)
        assertEquals("9", candidate.destinationId)
    }

    @Test
    fun `parseTransactionList skips a split missing a required field rather than failing the whole list`() {
        val body = """
            {"data": [
                {"id": "1", "attributes": {"transactions": [
                    {"amount": "5.00", "currency_code": "EUR", "description": "no date"},
                    {"date": "2026-03-01T12:00:00Z", "currency_code": "EUR", "description": "no amount"},
                    {"date": "2026-03-01T12:00:00Z", "amount": "5.00", "currency_code": "EUR", "description": "fine"}
                ]}}
            ]}
        """.trimIndent()

        assertEquals(listOf("fine"), parseTransactionList(body).map { it.description })
    }

    @Test
    fun `parseTransactionList returns empty for malformed input instead of throwing`() {
        assertTrue(parseTransactionList("not json").isEmpty())
        assertTrue(parseTransactionList("""{"nope": true}""").isEmpty())
    }

    @Test
    fun `parseTransactionList extracts foreign_amount and foreign_currency_code when present`() {
        val body = """
            {"data": [
                {"id": "99", "attributes": {"transactions": [
                    {"date": "2026-03-01T12:00:00Z", "amount": "123.45", "currency_code": "EUR",
                     "foreign_amount": "987.65", "foreign_currency_code": "HKD",
                     "description": "Shop1234", "source_name": "Amex"}
                ]}}
            ]}
        """.trimIndent()

        val candidate = parseTransactionList(body).single()

        assertEquals(BigDecimal("987.65"), candidate.foreignAmount)
        assertEquals("HKD", candidate.foreignCurrencyCode)
    }

    @Test
    fun `parseTransactionList leaves foreignAmount-foreignCurrencyCode null when absent`() {
        val body = """
            {"data": [
                {"id": "99", "attributes": {"transactions": [
                    {"date": "2026-03-01T12:00:00Z", "amount": "12.50", "currency_code": "EUR", "description": "Shop1234"}
                ]}}
            ]}
        """.trimIndent()

        val candidate = parseTransactionList(body).single()

        assertNull(candidate.foreignAmount)
        assertNull(candidate.foreignCurrencyCode)
    }

    @Test
    fun `parseSingleTransaction extracts fields from the singular GET transactions-id response shape`() {
        val body = """
            {"data": {"id": "42", "attributes": {"transactions": [
                {"date": "2026-03-01T12:00:00+01:00", "amount": "12.50", "currency_code": "EUR",
                 "description": "Shop1234", "source_id": "2", "source_name": "Bank Account A",
                 "destination_id": "9", "destination_name": "Shop1234"}
            ]}}}
        """.trimIndent()

        val candidate = parseSingleTransaction(body)

        assertEquals("42", candidate?.groupId)
        assertEquals(Instant.parse("2026-03-01T11:00:00Z"), candidate?.date)
        assertEquals(BigDecimal("12.50"), candidate?.amount)
        assertEquals("Shop1234", candidate?.description)
    }

    @Test
    fun `parseSingleTransaction returns null when data isn't a single object or has no splits`() {
        assertNull(parseSingleTransaction("not json"))
        assertNull(parseSingleTransaction("""{"nope": true}"""))
        assertNull(parseSingleTransaction("""{"data": {"id": "42", "attributes": {"transactions": []}}}"""))
        // The array-shaped list response, not the singular show-endpoint shape - must not be
        // mistaken for one by accident.
        assertNull(parseSingleTransaction("""{"data": [{"id": "42"}]}"""))
    }
}
