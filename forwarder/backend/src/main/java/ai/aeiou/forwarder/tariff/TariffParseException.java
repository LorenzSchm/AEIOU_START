package ai.aeiou.forwarder.tariff;

import java.util.List;

/** The sheet could not be read deterministically; {@code problems} says why (shown to a human). */
public class TariffParseException extends Exception {

    private final List<String> problems;

    public TariffParseException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public TariffParseException(String problem, Throwable cause) {
        super(problem, cause);
        this.problems = List.of(problem);
    }

    public List<String> problems() {
        return problems;
    }
}
