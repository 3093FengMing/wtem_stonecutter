package me.fengming.wtem.common.config;

/**
 * Runs a test body against a modified {@link WtemConfig}.
 *
 * <p>The active config is a process-wide singleton, so anything a test changes has to be changed
 * back before the next test reads it. Going through here keeps the restore in a finally block and
 * keeps the list of record components in one place, so adding a setting does not mean touching every
 * test that overrides an unrelated one.
 */
public final class ConfigOverride {
    private ConfigOverride() {}

    /** Runs {@code body} with only the skip settings changed, restoring the defaults afterwards. */
    public static void withSkipped(WtemConfig.Skipped skipped, Runnable body) {
        WtemConfig defaults = WtemConfig.DEFAULT;
        run(
                new WtemConfig(
                        defaults.stages(),
                        defaults.resources(),
                        defaults.keyReuse(),
                        defaults.keyNaming(),
                        defaults.nbtMaxDepth(),
                        defaults.rebuildNestedKeys(),
                        skipped,
                        defaults.skippedPaths(),
                        defaults.builtinEntries(),
                        defaults.languageFile()),
                body);
    }

    /** Runs {@code body} with {@code config} active, restoring the defaults afterwards. */
    public static void run(WtemConfig config, Runnable body) {
        WtemConfig.initialize(config);
        try {
            body.run();
        } finally {
            WtemConfig.initialize(WtemConfig.DEFAULT);
        }
    }
}
