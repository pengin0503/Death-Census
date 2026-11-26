package com.pengin0503.deathcensus;

/**
 * The single decision point for the double gate on demo data (DESIGN 1.3.0 §8,
 * §12 question 20): the config flag <em>and</em> the JVM system property must
 * both be open. A process-wide system property cannot be toggled at runtime;
 * the command tree is built once at server start.
 *
 * <p>Pure class: no Minecraft, config or logging dependencies so the whole
 * gate matrix is unit-testable.
 */
public final class DemoGate {
    /** JVM flag required in addition to {@code devMode}: {@code -Ddeathcensus.dev=true}. */
    public static final String SYSTEM_PROPERTY = "deathcensus.dev";

    private DemoGate() {
    }

    /**
     * @param configFlag     value of the {@code devMode} config key (never defaulted to true)
     * @param systemProperty value of {@link #SYSTEM_PROPERTY}; null/blank/anything but
     *                       {@code true} (case-insensitive, surrounding whitespace ignored) is closed
     */
    public static boolean enabled(boolean configFlag, String systemProperty) {
        if (!configFlag || systemProperty == null) {
            return false;
        }
        return "true".equalsIgnoreCase(systemProperty.trim());
    }

    /** Convenience for version code: reads the process-wide JVM property. */
    public static boolean enabled(boolean configFlag) {
        return enabled(configFlag, System.getProperty(SYSTEM_PROPERTY));
    }
}
