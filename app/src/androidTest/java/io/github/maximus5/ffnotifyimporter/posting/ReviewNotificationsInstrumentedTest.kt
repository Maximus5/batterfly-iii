package io.github.maximus5.ffnotifyimporter.posting

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import java.math.BigDecimal
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real device: the shade bookkeeping of "account is a guess" notifications -
 * `ReviewNotifications` against the real `NotificationManager.activeNotifications`, which is what
 * the JVM unit tests (`ShadeDedupTest`) cannot cover. Needs POST_NOTIFICATIONS granted
 * (`adb shell pm grant io.github.maximus5.ffnotifyimporter android.permission.POST_NOTIFICATIONS`).
 */
@RunWith(AndroidJUnit4::class)
class ReviewNotificationsInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        NotificationPrefs(context).setNotificationsEnabled(true)
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    private fun draft(sourceName: String, approximate: Boolean, millis: Long) = TransactionDraft(
        kind = RuleKind.WITHDRAWAL,
        date = Instant.ofEpochMilli(millis),
        amount = BigDecimal("4.09"),
        currencyCode = "EUR",
        foreignAmount = null,
        foreignCurrencyCode = null,
        sourceName = sourceName,
        sourceAccountId = null,
        destinationName = null,
        destinationAccountId = null,
        description = "Shop1234",
        notes = "",
        category = null,
        tags = emptyList(),
        issues = if (approximate) setOf(DraftIssue.ACCOUNT_APPROXIMATE) else emptySet(),
    )

    private fun message(app: String, millis: Long) =
        CapturedMessage("test.$app", app, "Payment", "text of $app", millis)

    private fun reviewNotificationCount() = manager.activeNotifications.count { it.packageName == context.packageName }

    @Test
    fun a_precise_review_notification_replaces_the_approximate_one() {
        val bank = message("bank", 1_000_000L)
        val wallet = message("wallet", 1_026_000L)

        ReviewNotifications.notifyMatch(context, bank, draft("Account A", approximate = true, millis = 1_000_000L))
        assertEquals(1, reviewNotificationCount())

        ReviewNotifications.notifyMatch(context, wallet, draft("Account B", approximate = false, millis = 1_026_000L))
        assertEquals("only the precise one should remain", 1, reviewNotificationCount())
    }

    @Test
    fun a_created_precise_transaction_dismisses_the_approximate_notification() {
        val bank = message("bank", 2_000_000L)
        ReviewNotifications.notifyMatch(context, bank, draft("Account A", approximate = true, millis = 2_000_000L))
        assertEquals(1, reviewNotificationCount())

        ReviewNotifications.supersedeApproximate(context, draft("Account B", approximate = false, millis = 2_026_000L))

        assertEquals(0, reviewNotificationCount())
    }

    @Test
    fun an_approximate_notification_is_not_posted_when_a_precise_one_is_already_there() {
        val wallet = message("wallet", 3_000_000L)
        val bank = message("bank", 3_026_000L)

        ReviewNotifications.notifyMatch(context, wallet, draft("Account B", approximate = false, millis = 3_000_000L))
        ReviewNotifications.notifyMatch(context, bank, draft("Account A", approximate = true, millis = 3_026_000L))

        assertEquals(1, reviewNotificationCount())
    }
}
