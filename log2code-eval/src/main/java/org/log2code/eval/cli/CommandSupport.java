package org.log2code.eval.cli;

import java.nio.file.Path;
import org.log2code.eval.EvalUserException;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigException;
import org.log2code.ingester.match.MatchingConfigLoader;

/** What {@code ablate}, {@code tune} and {@code validate} share. */
final class CommandSupport {

    /** The configuration {@code ingester ingest} uses; also where the ablation and the tuning start from. */
    static final Path CONFIG_DIR = Path.of("config");
    static final Path MATCHING_CONFIG = CONFIG_DIR.resolve("matching.yml");

    private CommandSupport() {
    }

    static MatchingConfig loadConfig(Path file) {
        try {
            return MatchingConfigLoader.load(file);
        } catch (MatchingConfigException e) {
            throw new EvalUserException(e.getMessage());
        }
    }
}
