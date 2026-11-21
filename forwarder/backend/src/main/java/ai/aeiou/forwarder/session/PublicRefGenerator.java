package ai.aeiou.forwarder.session;

import java.security.SecureRandom;

/**
 * Customer-facing session references like {@code AEI-7K2Q9}. The alphabet leaves out characters
 * that are easy to confuse when read aloud or typed (0/O, 1/I/L, 5/S, 8/B, 2/Z).
 */
final class PublicRefGenerator {

    static final String PREFIX = "AEI-";
    static final String ALPHABET = "34679ACDEFGHJKMNPQRTUVWXY";
    static final int LENGTH = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicRefGenerator() {}

    static String next() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
