package ai.aeiou.forwarder.tariff.parse;

import static ai.aeiou.forwarder.tariff.parse.Numbers.NUM;

import ai.aeiou.forwarder.tariff.ExtraCharge;
import ai.aeiou.forwarder.tariff.ParsedLane;
import ai.aeiou.forwarder.tariff.ParsedTariff;
import ai.aeiou.forwarder.tariff.TariffParseException;
import ai.aeiou.forwarder.tariff.WeightBreak;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text lines of a tariff sheet into a {@link ParsedTariff}. One reader covers all kit layouts:
 * <ul>
 *   <li>row tables with a break header ("-45 +45 ...", "from 0 kg ...", "0 kg+ ...") and one lane per row
 *       (Helix, Quetzal, Yellowtail, Wayzata, Danube)</li>
 *   <li>tables grouped by "Origin: X" with the destination in the first column (Elbflug, Kestrel, Vega)</li>
 *   <li>one block per lane with "Transit / Minimum / from N kg / Fuel / Security" lines (Cobalt, Orion)</li>
 *   <li>surcharges inline (Kestrel columns, Cobalt blocks) or in a separate "Surcharges" section</li>
 * </ul>
 * Lanes can be written "FRA-SIN", "FRA Frankfurt > SIN Singapore", "Frankfurt (FRA) to Singapore (SIN)" or by
 * city name. Anything it cannot place is reported as a problem rather than guessed, so the caller can fall back
 * to the LLM or a human.
 */
public final class TariffLayoutReader {

    private static final Set<String> NOT_AIRPORTS = Set.of("EUR", "USD", "GBP", "CHF", "ALL", "THE", "AND", "PER", "FOR", "KGS");
    private static final String CITY_WORDS = "(?:[ ]+(?!D\\+|EUR\\b|USD\\b|[Ff]uel\\b|[Ss]ecurity\\b)[A-Za-z][A-Za-z.'-]*)*";
    private static final String LANE_SEP = "\\s*(?:>|→|–|\\bto\\b)\\s*";

    private static final Pattern LANE_PARENS = Pattern.compile("\\(([A-Z]{3})\\)\\s*(?:to|>|→|–|-)\\s*[^()\\t]*?\\(([A-Z]{3})\\)");
    private static final Pattern LANE_ARROW = Pattern.compile("\\b([A-Z]{3})\\b" + CITY_WORDS + LANE_SEP + "([A-Z]{3})\\b" + CITY_WORDS);
    private static final Pattern LANE_DASH = Pattern.compile("\\b([A-Z]{3})\\s*[-–/]\\s*([A-Z]{3})\\b");
    private static final Pattern LANE_CITIES = Pattern.compile(
            "^([A-Za-z][A-Za-z .'-]+?)\\s*(?:>|→|–|-|\\bto\\b)\\s*([A-Za-z][A-Za-z .'-]+?)(?=\\t|:|\\s{2,}|\\s+\\d|\\s+D\\+|$)");
    private static final Pattern DEST_CODE = Pattern.compile("^([A-Z]{3})(?=\\t|\\s)");
    private static final Pattern DEST_PARENS = Pattern.compile("^[A-Za-z][A-Za-z .'-]*?\\s*\\(([A-Z]{3})\\)");
    private static final Pattern DEST_CITY = Pattern.compile("^([A-Za-z][A-Za-z .'-]*?)(?=\\t|\\s{2,}|\\s+\\d)");

    private static final Pattern CURRENCY = Pattern.compile("(?i)\\bcurrency\\s*:?\\s*([A-Z]{3})\\b");
    private static final Pattern VALID_FROM = Pattern.compile("(?i)\\bvalid(?:ity)?\\s*from\\b");
    private static final Pattern VALID_TO = Pattern.compile("(?i)\\bvalid(?:ity)?\\s*(?:to|until|thru|through)\\b");
    private static final Pattern VALIDITY = Pattern.compile("(?i)^validity\\b");
    private static final Pattern FX_EQUATION = Pattern.compile("\\b1\\s*([A-Z]{3})\\s*=\\s*(" + NUM + ")\\s*([A-Z]{3})\\b");
    private static final Pattern FX_EUR_PER = Pattern.compile("(?i)EUR\\s*per\\s*([A-Z]{3})\\D{0,20}?(" + NUM + ")");
    private static final Pattern ORIGIN_GROUP = Pattern.compile("(?i)^origin\\s*:?\\s*(.+)$");
    private static final Pattern RATES_IN_CURRENCY = Pattern.compile("(?i)rates are .*\\bin ([A-Z]{3})\\b");

    private static final Pattern BREAK_TOKEN = Pattern.compile(
            "(?i)from\\s+(\\d+)\\s*kg|(\\d+)\\s*kg\\s*\\+|(?<![A-Za-z\\d])([-+<])\\s?(\\d+)(?![\\d.,])");
    private static final Pattern DECIMAL = Pattern.compile("\\d[.,]\\d{2}\\b");
    private static final Pattern TRANSIT = Pattern.compile("(?i)\\bD\\s*\\+\\s*(\\d+)\\b|\\b(\\d+)\\s*(?:days?|d)\\b");
    private static final Pattern BLOCK_KEY = Pattern.compile(
            "(?i)^(transit|minimum|min\\.?|fuel|security)\\s*:?\\s*(?:[A-Z]{3}\\s*)?(?:D\\s*\\+\\s*)?(" + NUM + ")\\s*(?:[A-Z]{3}|days?)?$");
    private static final Pattern BLOCK_BREAK = Pattern.compile(
            "(?i)^(?:from\\s+(\\d+)\\s*kg|(\\d+)\\s*kg\\s*\\+|([-+<])\\s?(\\d+)\\s*(?:kg)?)\\s*:?\\s*(?:[A-Z]{3}\\s*)?(" + NUM + ")$");
    private static final Pattern FUEL = Pattern.compile("(?i)\\bfuel\\D*?(" + NUM + ")\\s*(%)?");
    private static final Pattern SECURITY = Pattern.compile("(?i)\\bsecurity\\D*?(" + NUM + ")\\s*(%)?");
    private static final Pattern GLOBAL_SURCHARGE = Pattern.compile(
            "(?i)^(fuel|security)(?:\\s+surcharge)?\\s*[:=]?\\s*(?:[A-Z]{3}\\s*)?(" + NUM + ")\\s*(?:[A-Z]{3}\\s*)?per\\s*kg");
    private static final Pattern EXTRA = Pattern.compile(
            "(?i)^\\W*([A-Za-z][A-Za-z /&'()-]*?(?:fee|charge|surcharge|handling|screening|documentation|awb)[A-Za-z /&'()-]*?)"
                    + "\\s*[:=]?\\s*(?:[A-Z]{3}\\s*)?(" + NUM + ")\\s*(?:[A-Z]{3}\\s*)?"
                    + "(%|per\\s+(?:shipment|awb|consignment|booking)|flat|per\\s+kg(?:\\s+of\\s+(chargeable|billed|actual)\\s+weight)?)");
    private static final Pattern POUNDS = Pattern.compile("(?i)\\b(?:per\\s+lb|lbs?)\\b");

    private final AirportResolver airports;
    private final List<String> notes = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, LaneBuilder> lanes = new LinkedHashMap<>();
    private final Map<String, BigDecimal[]> sectionSurcharges = new HashMap<>();
    private final Map<String, List<ExtraCharge>> laneExtras = new HashMap<>();
    private final List<ExtraCharge> globalExtras = new ArrayList<>();

    private String title;
    private String currency;
    private String currencyHint;
    private BigDecimal fxEurPerUnit;
    private LocalDate validFrom;
    private LocalDate validTo;
    private BigDecimal globalFuel;
    private BigDecimal globalSecurity;

    private List<Integer> headerBreaks;
    private boolean headerTransit;
    private boolean headerFuelSecurity;
    private boolean pendingTransitColumn;
    private boolean surchargeSection;
    private boolean surchargeSecurityFirst;
    private String groupOrigin;
    private LaneBuilder block;

    private TariffLayoutReader(AirportResolver airports) {
        this.airports = airports == null ? AirportResolver.NONE : airports;
    }

    public static ParsedTariff read(List<String> lines) throws TariffParseException {
        return read(lines, AirportResolver.NONE);
    }

    public static ParsedTariff read(List<String> lines, AirportResolver airports) throws TariffParseException {
        TariffLayoutReader reader = new TariffLayoutReader(airports);
        for (String line : lines) reader.accept(line);
        return reader.build();
    }

    private void accept(String raw) {
        String line = raw.replace(' ', ' ').strip();
        if (line.isEmpty()) return;
        if (title == null) {
            title = line;
            return;
        }
        if (POUNDS.matcher(line).find()) problems.add("Weights in pounds are not supported: " + line);
        if (metadata(line)) return;
        if (block != null && blockLine(line)) return;
        if (columnHeader(line)) return;
        if (breakHeader(line)) return;
        if (sectionHeader(line)) return;
        Optional<LaneMatch> lane = findLane(line);
        if (lane.isPresent()) {
            laneLine(lane.get());
            return;
        }
        if (globalLine(line)) return;
        if (line.chars().anyMatch(Character::isLetter)) notes.add(line);
    }

    // ---- metadata --------------------------------------------------------------------------------------

    /** Currency and validity; a docx/xlsx row may hold several of them ("Currency | EUR | Valid from | ..."). */
    private boolean metadata(String line) {
        boolean matched = false;
        Matcher m;
        if ((m = CURRENCY.matcher(line)).find()) {
            currency = m.group(1).toUpperCase(Locale.ROOT);
            matched = true;
        }
        Matcher to = VALID_TO.matcher(line);
        boolean hasTo = to.find();
        if ((m = VALID_FROM.matcher(line)).find()) {
            String fromText = hasTo && to.start() > m.end() ? line.substring(m.end(), to.start()) : line.substring(m.end());
            List<LocalDate> dates = Numbers.dates(fromText);
            if (!dates.isEmpty()) validFrom = dates.getFirst();
            if (!hasTo && dates.size() > 1) validTo = dates.get(1);
            matched = true;
        }
        if (hasTo) {
            Numbers.date(line.substring(to.end())).ifPresent(d -> validTo = d);
            matched = true;
        }
        if (!matched && (m = VALIDITY.matcher(line)).find()) {
            List<LocalDate> dates = Numbers.dates(line.substring(m.end()));
            if (dates.size() == 2) {
                validFrom = dates.get(0);
                validTo = dates.get(1);
            }
            matched = true;
        }
        if (matched) return true;
        if ((m = FX_EQUATION.matcher(line)).find()) {
            BigDecimal value = Numbers.parse(m.group(2));
            if ("EUR".equals(m.group(3)) && !"EUR".equals(m.group(1))) {
                fxEurPerUnit = value;
            } else if ("EUR".equals(m.group(1)) && value.signum() > 0) {
                fxEurPerUnit = BigDecimal.ONE.divide(value, MathContext.DECIMAL64);
            }
            notes.add(line);
            return true;
        }
        if ((m = FX_EUR_PER.matcher(line)).find()) {
            fxEurPerUnit = Numbers.parse(m.group(2));
            notes.add(line);
            return true;
        }
        if ((m = RATES_IN_CURRENCY.matcher(line)).find()) {
            currencyHint = m.group(1).toUpperCase(Locale.ROOT);
            notes.add(line);
            return true;
        }
        return false;
    }

    // ---- headers and sections --------------------------------------------------------------------------

    /** "Destination | Transit | Minimum | Rate per kg ..." without break numbers: remembers the transit column. */
    private boolean columnHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.contains("minimum") || Numbers.containsNumber(line)) return false;
        if (!(lower.startsWith("lane") || lower.startsWith("destination") || lower.startsWith("route") || lower.contains("transit"))) {
            return false;
        }
        closeBlock();
        pendingTransitColumn = lower.contains("transit");
        return true;
    }

    private boolean breakHeader(String line) {
        if (DECIMAL.matcher(line).find()) return false;
        List<Integer> breaks = new ArrayList<>();
        Matcher m = BREAK_TOKEN.matcher(line);
        while (m.find()) breaks.add(breakKg(m.group(1), m.group(2), m.group(3), m.group(4)));
        if (breaks.size() < 3) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        closeBlock();
        headerBreaks = List.copyOf(breaks);
        headerTransit = lower.contains("transit") || pendingTransitColumn;
        headerFuelSecurity = lower.contains("fuel") && lower.contains("security");
        pendingTransitColumn = false;
        surchargeSection = false;
        return true;
    }

    private boolean sectionHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("surcharge") && !Numbers.containsNumber(line)) {
            closeBlock();
            surchargeSection = true;
            notes.add(line);
            return true;
        }
        if (surchargeSection && lower.contains("fuel") && lower.contains("security") && !Numbers.containsNumber(line)) {
            surchargeSecurityFirst = lower.indexOf("security") < lower.indexOf("fuel");
            return true;
        }
        Matcher m = ORIGIN_GROUP.matcher(line);
        if (m.find()) {
            closeBlock();
            groupOrigin = airportCode(m.group(1)).orElse(null);
            if (groupOrigin == null) problems.add("Unknown origin group: " + line);
            return true;
        }
        return false;
    }

    // ---- lanes -----------------------------------------------------------------------------------------

    private record LaneMatch(String origin, String destination, String rest, boolean grouped) {}

    private Optional<LaneMatch> findLane(String line) {
        for (Pattern p : List.of(LANE_PARENS, LANE_ARROW, LANE_DASH)) {
            Matcher m = p.matcher(line);
            if (m.find() && isAirport(m.group(1)) && isAirport(m.group(2)) && !m.group(1).equals(m.group(2))) {
                return Optional.of(new LaneMatch(m.group(1), m.group(2), line.substring(m.end()), false));
            }
        }
        Matcher cities = LANE_CITIES.matcher(line);
        if (cities.find()) {
            Optional<String> from = airports.iata(cities.group(1).trim());
            Optional<String> to = airports.iata(cities.group(2).trim());
            if (from.isPresent() && to.isPresent()) {
                return Optional.of(new LaneMatch(from.get(), to.get(), line.substring(cities.end()), false));
            }
        }
        if (groupOrigin != null && headerBreaks != null) {
            for (Pattern p : List.of(DEST_CODE, DEST_PARENS)) {
                Matcher m = p.matcher(line);
                if (m.find() && isAirport(m.group(1)) && Numbers.containsNumber(line.substring(m.end()))) {
                    return Optional.of(new LaneMatch(groupOrigin, m.group(1), line.substring(m.end()), true));
                }
            }
            Matcher m = DEST_CITY.matcher(line);
            if (m.find() && Numbers.containsNumber(line.substring(m.end()))) {
                Optional<String> dest = airports.iata(m.group(1).trim());
                if (dest.isPresent()) return Optional.of(new LaneMatch(groupOrigin, dest.get(), line.substring(m.end()), true));
            }
        }
        return Optional.empty();
    }

    private void laneLine(LaneMatch lane) {
        String key = lane.origin() + "-" + lane.destination();
        String rest = lane.rest();
        String lower = rest.toLowerCase(Locale.ROOT);

        if (lower.contains("fuel") || lower.contains("security")) {
            laneSurcharges(key, rest);
            return;
        }
        Optional<ExtraCharge> extra = extra(rest.replaceFirst("^\\s*[:-]\\s*", ""));
        if (extra.isPresent()) {
            laneExtras.computeIfAbsent(key, k -> new ArrayList<>()).add(extra.get());
            return;
        }

        Integer transit = null;
        Matcher t = TRANSIT.matcher(rest);
        if (t.find()) {
            transit = Integer.parseInt(t.group(1) != null ? t.group(1) : t.group(2));
            rest = rest.substring(0, t.start()) + " " + rest.substring(t.end());
        }
        List<BigDecimal> nums = Numbers.all(rest);

        if (surchargeSection && nums.size() == 2) {
            BigDecimal first = nums.get(0);
            BigDecimal second = nums.get(1);
            sectionSurcharges.put(key, surchargeSecurityFirst ? new BigDecimal[] {second, first} : new BigDecimal[] {first, second});
            return;
        }
        if (nums.isEmpty() && transit == null) {
            closeBlock();
            block = laneBuilder(lane);
            return;
        }
        if (headerBreaks == null) {
            problems.add("Lane row before any weight-break header: " + key);
            return;
        }
        boolean transitColumn = transit == null && headerTransit;
        int expected = headerBreaks.size() + 1 + (transitColumn ? 1 : 0) + (headerFuelSecurity ? 2 : 0);
        if (nums.size() != expected) {
            problems.add("Lane " + key + " has " + nums.size() + " numbers, expected " + expected);
            return;
        }
        closeBlock();
        LaneBuilder b = laneBuilder(lane);
        int i = 0;
        if (transitColumn) b.transit = nums.get(i++).intValue();
        else b.transit = transit;
        b.minimum = nums.get(i++);
        for (Integer kg : headerBreaks) b.breaks.add(new WeightBreak(kg, nums.get(i++)));
        if (headerFuelSecurity) {
            b.fuel = nums.get(i++);
            b.security = nums.get(i);
        }
    }

    private LaneBuilder laneBuilder(LaneMatch lane) {
        String key = lane.origin() + "-" + lane.destination();
        if (lanes.containsKey(key)) problems.add("Lane " + key + " appears twice");
        LaneBuilder b = new LaneBuilder(lane.origin(), lane.destination());
        lanes.put(key, b);
        return b;
    }

    private void laneSurcharges(String key, String text) {
        Matcher fuel = FUEL.matcher(text);
        Matcher security = SECURITY.matcher(text);
        BigDecimal[] values = sectionSurcharges.computeIfAbsent(key, k -> new BigDecimal[2]);
        if (fuel.find()) {
            if (fuel.group(2) != null) laneExtras.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new ExtraCharge("fuel", ExtraCharge.Basis.PERCENT_OF_FREIGHT, Numbers.parse(fuel.group(1))));
            else values[0] = Numbers.parse(fuel.group(1));
        }
        if (security.find()) {
            if (security.group(2) != null) laneExtras.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new ExtraCharge("security", ExtraCharge.Basis.PERCENT_OF_FREIGHT, Numbers.parse(security.group(1))));
            else values[1] = Numbers.parse(security.group(1));
        }
    }

    /** "Transit 5", "Minimum 79.00", "from 45 kg 4.17", "Fuel 0.70" inside a per-lane block. */
    private boolean blockLine(String line) {
        Matcher m = BLOCK_KEY.matcher(line);
        if (m.find()) {
            BigDecimal value = Numbers.parse(m.group(2));
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "transit" -> block.transit = value.intValue();
                case "fuel" -> block.fuel = value;
                case "security" -> block.security = value;
                default -> block.minimum = value;
            }
            return true;
        }
        m = BLOCK_BREAK.matcher(line);
        if (m.find()) {
            block.breaks.add(new WeightBreak(breakKg(m.group(1), m.group(2), m.group(3), m.group(4)), Numbers.parse(m.group(5))));
            return true;
        }
        return false;
    }

    private void closeBlock() {
        block = null;
    }

    // ---- sheet-wide rules ------------------------------------------------------------------------------

    private boolean globalLine(String line) {
        Matcher m = GLOBAL_SURCHARGE.matcher(line);
        if (m.find()) {
            BigDecimal value = Numbers.parse(m.group(2));
            if (m.group(1).equalsIgnoreCase("fuel")) globalFuel = value;
            else globalSecurity = value;
            notes.add(line);
            return true;
        }
        Optional<ExtraCharge> extra = extra(line);
        if (extra.isPresent()) {
            globalExtras.add(extra.get());
            notes.add(line);
            return true;
        }
        return false;
    }

    private static Optional<ExtraCharge> extra(String text) {
        Matcher m = EXTRA.matcher(text.strip());
        if (!m.find()) return Optional.empty();
        String name = m.group(1).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("minimum")) return Optional.empty();
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        ExtraCharge.Basis basis;
        if (unit.equals("%")) basis = ExtraCharge.Basis.PERCENT_OF_FREIGHT;
        else if (unit.startsWith("per kg")) basis = "chargeable".equalsIgnoreCase(m.group(4)) ? ExtraCharge.Basis.PER_KG_CHARGEABLE : ExtraCharge.Basis.PER_KG_BILLED;
        else basis = ExtraCharge.Basis.PER_SHIPMENT;
        return Optional.of(new ExtraCharge(name, basis, Numbers.parse(m.group(2))));
    }

    // ---- result ----------------------------------------------------------------------------------------

    private ParsedTariff build() throws TariffParseException {
        if (currency == null) currency = currencyHint;
        if (currency == null) problems.add("No currency stated");
        for (String key : sectionSurcharges.keySet()) {
            if (!lanes.containsKey(key)) problems.add("Surcharges given for unknown lane " + key);
        }
        for (String key : laneExtras.keySet()) {
            if (!lanes.containsKey(key)) problems.add("Extra charge given for unknown lane " + key);
        }
        List<ParsedLane> result = new ArrayList<>();
        for (Map.Entry<String, LaneBuilder> e : lanes.entrySet()) {
            buildLane(e.getKey(), e.getValue()).ifPresent(result::add);
        }
        if (result.isEmpty()) problems.add("No lanes found");
        if (!problems.isEmpty()) throw new TariffParseException(problems);
        return new ParsedTariff(airlineName(), null, currency, fxEurPerUnit, validFrom, validTo, result, notes);
    }

    private Optional<ParsedLane> buildLane(String key, LaneBuilder b) {
        BigDecimal[] section = sectionSurcharges.getOrDefault(key, new BigDecimal[2]);
        List<ExtraCharge> extras = new ArrayList<>(laneExtras.getOrDefault(key, List.of()));
        extras.addAll(globalExtras);
        // a surcharge given as a percentage lives in extras; its per-kg part is then zero
        BigDecimal fuel = firstNonNull(b.fuel, section[0], globalFuel, percentOnly(extras, "fuel"));
        BigDecimal security = firstNonNull(b.security, section[1], globalSecurity, percentOnly(extras, "security"));
        int before = problems.size();
        if (b.minimum == null) problems.add("Lane " + key + " has no minimum");
        if (b.breaks.isEmpty()) problems.add("Lane " + key + " has no weight breaks");
        if (fuel == null) problems.add("Lane " + key + " has no fuel surcharge");
        if (security == null) problems.add("Lane " + key + " has no security surcharge");
        for (int i = 1; i < b.breaks.size(); i++) {
            if (b.breaks.get(i).fromKg() <= b.breaks.get(i - 1).fromKg()) {
                problems.add("Lane " + key + " has weight breaks out of order");
                break;
            }
        }
        if (problems.size() > before) return Optional.empty();
        return Optional.of(new ParsedLane(b.origin, b.destination, b.transit, b.minimum, b.breaks, fuel, security, extras));
    }

    private String airlineName() {
        String name = title.contains("|") ? title.substring(0, title.indexOf('|')) : title.replaceAll("(?i)\\b(air\\s+)?(export\\s+)?tariff.*$", "");
        return name.strip();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static int breakKg(String fromKg, String kgPlus, String sign, String signedKg) {
        if (fromKg != null) return Integer.parseInt(fromKg);
        if (kgPlus != null) return Integer.parseInt(kgPlus);
        // "-45" / "<45" is the bracket below 45 kg, i.e. from 0; "+45" starts at 45.
        return "+".equals(sign) ? Integer.parseInt(signedKg) : 0;
    }

    private boolean isAirport(String code) {
        return code.length() == 3 && !NOT_AIRPORTS.contains(code);
    }

    private Optional<String> airportCode(String text) {
        Matcher parens = Pattern.compile("\\(([A-Z]{3})\\)").matcher(text);
        if (parens.find()) return Optional.of(parens.group(1));
        Matcher code = Pattern.compile("^([A-Z]{3})\\b").matcher(text.strip());
        if (code.find() && isAirport(code.group(1))) return Optional.of(code.group(1));
        return airports.iata(text.strip());
    }

    private static BigDecimal percentOnly(List<ExtraCharge> extras, String name) {
        boolean present = extras.stream()
                .anyMatch(e -> e.basis() == ExtraCharge.Basis.PERCENT_OF_FREIGHT && e.name().toLowerCase(Locale.ROOT).contains(name));
        return present ? BigDecimal.ZERO : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private static final class LaneBuilder {
        final String origin;
        final String destination;
        final List<WeightBreak> breaks = new ArrayList<>();
        Integer transit;
        BigDecimal minimum;
        BigDecimal fuel;
        BigDecimal security;

        LaneBuilder(String origin, String destination) {
            this.origin = origin;
            this.destination = destination;
        }
    }
}
