package io.github.maximus5.ffnotifyimporter.rules

/**
 * Direction comes from [ParseRule.sourceAccountName]/[ParseRule.destinationAccountName]
 * directly, not from the kind - kind is just which Firefly III transaction type this becomes.
 */
enum class RuleKind { WITHDRAWAL, DEPOSIT, TRANSFER }
