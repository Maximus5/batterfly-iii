package io.github.maximus5.ffnotifyimporter.network

/** One Firefly account as returned by `GET /v1/accounts` - just enough to let the rule editor's
 * asset-account picker show a name and apply it (see TODO.md). */
data class FireflyAccount(val id: String, val name: String)
