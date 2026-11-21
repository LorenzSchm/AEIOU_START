package ai.aeiou.forwarder.tariff;

/** Lifecycle of a stored tariff version; the names match the {@code tariff.status} column. */
public enum TariffStatus {
    active, pending_review, superseded, rejected
}
