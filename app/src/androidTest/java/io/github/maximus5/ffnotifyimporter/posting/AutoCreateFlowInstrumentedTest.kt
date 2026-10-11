package io.github.maximus5.ffnotifyimporter.posting

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole real path, on a device: listener-style capture -> `AutoCreateScheduler` -> WorkManager ->
 * `AutoCreateWorker` -> the Firefly HTTP client -> notifications. Needs a stand-in Firefly on the
 * host: a tiny HTTP server on port [PORT] reachable through `adb reverse tcp:18080 tcp:18080`
 * (see ARCHITECTURE.md). Replaces and restores the app's connection settings and removes its own
 * rules/messages, but it does touch the real app data of the device it runs on - use a test device.
 */
@RunWith(AndroidJUnit4::class)
class AutoCreateFlowInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = FireflyPrefs(context)
    private val rulesDb = RulesDb.getInstance(context)
    private val addedRuleIds = mutableListOf<String>()
    private var addedCurrency = false

    private val savedBaseUrl = prefs.getBaseUrl()
    private val savedToken = prefs.getToken()
    private val savedAutoCreate = prefs.isAutoCreateEnabled()
    private val savedCleartext = prefs.isAllowCleartext()
    private val savedBuiltIn = prefs.isBuiltInRulesEnabled()
    private val savedDiagnostic = DiagnosticLog.isEnabled(context)

    @Before
    fun setUp() {
        DiagnosticLog.setEnabled(context, true)
        prefs.setBaseUrl("http://localhost:$PORT")
        prefs.setToken("<token>")
        prefs.setAllowCleartext(true)
        prefs.setAutoCreateEnabled(true)
        prefs.setBuiltInRulesEnabled(false)
        // A real install learns "€" from its Firefly; this device has no such table.
        if (CurrenciesDb.getInstance(context).loadAll().none { it.symbol == "€" }) {
            CurrenciesDb.getInstance(context).add(CurrencyEntry("EUR", "€"))
            addedCurrency = true
        }
        NotificationPrefs(context).setNotificationsEnabled(true)
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        DiagnosticLog.setEnabled(context, savedDiagnostic)
        addedRuleIds.forEach { rulesDb.delete(it) }
        if (addedCurrency) CurrenciesDb.getInstance(context).delete("EUR")
        MessageStore.shared.snapshot().filter { it.packageName.startsWith("test.") }.forEach { MessageStore.shared.remove(it) }
        manager.cancelAll()
        prefs.setBaseUrl(savedBaseUrl)
        prefs.setToken(savedToken)
        prefs.setAllowCleartext(savedCleartext)
        prefs.setAutoCreateEnabled(savedAutoCreate)
        prefs.setBuiltInRulesEnabled(savedBuiltIn)
    }

    private fun addRule(label: String, pkg: String, account: String, approximate: Boolean, pattern: String) {
        val added = rulesDb.add(
            ParseRule(
                id = "",
                label = label,
                packageNames = setOf(pkg),
                kind = RuleKind.WITHDRAWAL,
                sourceAccountName = account,
                homeCurrency = "EUR",
                pattern = pattern,
                descriptionTemplate = "Shop1234",
                accountApproximate = approximate,
            ),
        )
        addedRuleIds += added.id
    }

    private fun capture(pkg: String, text: String, timestampMillis: Long) {
        val recorded = MessageStore.shared.add(pkg, pkg, "Payment", text, timestampMillis)!!
        AutoCreateScheduler.scheduleIfEnabled(context, recorded)
    }

    private fun titles() = manager.activeNotifications.map { it.notification.extras.getString("android.title") }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        throw AssertionError("timed out waiting for $what; notifications now: ${titles()}")
    }

    @Test
    fun bank_review_notification_is_dismissed_when_the_wallet_transaction_is_auto_created() {
        addRule("Bank rule", "test.bank", "Account A", approximate = true, pattern = """(?<amount>\d+,\d+) (?<currency>€)""")
        addRule("Wallet rule", "test.wallet", "Account B", approximate = false, pattern = """(?<currency>€)(?<amount>\d+\.\d+)""")
        val now = System.currentTimeMillis()
        // A different amount every run: the dedup log of earlier runs would otherwise (correctly) hide it.
        val cents = 10 + (now % 89).toInt()

        capture("test.bank", "You paid 4,$cents € to Shop1234", now)
        waitFor("the bank review notification") { "Transaction to review" in titles() }

        capture("test.wallet", "€4.$cents with Abo", now + 26_000)
        waitFor("the wallet transaction to be linked") {
            MessageStore.shared.snapshot().any { it.packageName == "test.wallet" && it.fireflyTransactionId != null }
        }
        Thread.sleep(2_000)

        assertEquals("notifications after the wallet transaction: ${titles()}", false, "Transaction to review" in titles())
        assertEquals(true, "Transaction created" in titles())
    }

    companion object {
        private const val PORT = 18080
    }
}
