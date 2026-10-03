package io.github.maximus5.ffnotifyimporter.rules

/**
 * Direction is always relative to the rule's own account ([ParseRule.accountName]).
 * Convention for transfers: configure the rule on ONE side only (either TRANSFER_OUT on the
 * source account's rule or TRANSFER_IN on the destination account's rule) - no deduplication
 * is performed if both happen to match the same notification.
 */
enum class RuleKind { WITHDRAWAL, DEPOSIT, TRANSFER_OUT, TRANSFER_IN }
