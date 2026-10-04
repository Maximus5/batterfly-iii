package io.github.maximus5.ffnotifyimporter.network

/** One Firefly category as returned by `GET /v1/categories` - just enough to let
 * `ui.CreateTransactionFragment`'s category picker show a name and apply it (see TODO.md). */
data class FireflyCategory(val id: String, val name: String)
