package ai.aeiou.forwarder.reference;

import java.util.List;

/**
 * An airline or trucker from carrier-list.csv. {@code serves} holds IATA destinations for airlines
 * and two-digit zip zones for truckers. {@code code} is the two-letter airline code once known.
 */
public record Carrier(long id, String name, String code, Kind kind, String email, List<String> serves) {

    public enum Kind { airline, trucker }

    public boolean isAirline() {
        return kind == Kind.airline;
    }

    public boolean serves(String value) {
        return value != null && serves.contains(value.trim().toUpperCase());
    }
}
