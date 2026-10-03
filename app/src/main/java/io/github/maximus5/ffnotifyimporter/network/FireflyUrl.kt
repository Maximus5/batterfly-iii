package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * Pure helpers around the Firefly III base URL/API responses - kept separate from
 * [FireflyApiClient] (which needs a real network call) so this part is plain-Kotlin unit
 * testable.
 */

/** Strips a trailing slash so building "$baseUrl/api/v1/..." never ends up with a double "//". */
fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

/** The host portion of a base URL, for a short "connected to X" display - null if the URL isn't
 * parseable at all (the caller falls back to "not configured" in that case too). */
fun hostOf(url: String): String? = runCatching { URI(normalizeBaseUrl(url)).host }.getOrNull()

/** True for a `http://` URL - the one case that needs Android's cleartext-traffic allowance and
 * a "this isn't encrypted" warning in the UI, since everything else here assumes `https://`. */
fun isCleartextUrl(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/**
 * Firefly's `GET /api/v1/about` responds `{"data": {"version": "...", ...}}` - pulls just the
 * version string out, or null if the body isn't shaped like that (used to confirm a successful
 * test-connection call actually talked to a real Firefly instance, not just got a 200 from
 * something else answering on that URL).
 */
fun parseAboutVersion(body: String): String? = try {
    JSONObject(body).optJSONObject("data")?.optString("version")?.takeIf { it.isNotBlank() }
} catch (e: JSONException) {
    null
}
