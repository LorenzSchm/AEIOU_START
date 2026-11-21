package ai.aeiou.forwarder.tariff.parse;

import java.util.Optional;

/** Maps a city name such as "Frankfurt" to its IATA code, for sheets that name lanes by city only. */
@FunctionalInterface
public interface AirportResolver {

    AirportResolver NONE = text -> Optional.empty();

    Optional<String> iata(String cityOrCode);
}
