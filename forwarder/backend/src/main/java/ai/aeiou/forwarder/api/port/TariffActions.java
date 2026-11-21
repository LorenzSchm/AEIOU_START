package ai.aeiou.forwarder.api.port;

/** Review decisions on an ingested tariff version. */
public interface TariffActions {

    /** Makes the tariff active (superseding the airline's previous active version) and reloads pricing. */
    void activate(long tariffId);

    void reject(long tariffId);
}
