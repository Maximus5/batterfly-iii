package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/** What [autofillDefaults] actually wrote - null for a field that was left alone. */
data class DefaultsAutofill(val currency: String? = null, val sourceAccount: String? = null)

/** The onboarding guess, from whatever Firefly returned (null = that lookup failed or wasn't
 * needed): the primary *enabled* currency, and the only asset account - with several accounts
 * there's no honest default, so the source account stays for the user to pick. */
fun chooseDefaults(currencies: List<FireflyCurrency>?, accounts: List<FireflyAccount>?): DefaultsAutofill =
    DefaultsAutofill(
        currency = currencies?.firstOrNull { it.primary && it.enabled }?.code?.uppercase(),
        sourceAccount = accounts?.singleOrNull()?.name,
    )

/** Fills the default account currency / default source account from Firefly, **only** where the
 * user hasn't set one yet - never overwrites a value. Read-only calls; failures just leave the
 * fields blank. */
suspend fun autofillDefaults(prefs: FireflyPrefs, client: FireflyApiClient): DefaultsAutofill {
    val needCurrency = prefs.getDefaultAccountCurrency().isBlank()
    val needAccount = prefs.getDefaultSourceAccount().isBlank()
    if (!needCurrency && !needAccount) return DefaultsAutofill()

    val currencies = if (needCurrency) (client.listCurrencies() as? FireflyApiClient.Result.Success)?.value else null
    val accounts = if (needAccount) (client.listAssetAccounts() as? FireflyApiClient.Result.Success)?.value else null

    val chosen = chooseDefaults(currencies, accounts)
    chosen.currency?.let { prefs.setDefaultAccountCurrency(it) }
    chosen.sourceAccount?.let { prefs.setDefaultSourceAccount(it) }
    return chosen
}
