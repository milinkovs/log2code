package org.log2code.eval.tune;

import java.util.Locale;
import org.log2code.eval.EvalUserException;

/**
 * Keeps the held-out dataset out of everything that tunes (T34 step 3: "test skup se ne koristi za
 * podešavanje"). By the dataset naming of K1/K3, a test dataset's id starts with {@code test-}; {@code ablate} and
 * {@code tune} refuse such a dataset, and {@code validate} is the only command that reads it.
 */
public final class TestSets {

    private static final String PREFIX = "test-";

    private TestSets() {
    }

    public static boolean isTestSet(String datasetId) {
        return datasetId != null && datasetId.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static void requireTuningSet(String datasetId, String command) {
        if (isTestSet(datasetId)) {
            throw new EvalUserException("'" + datasetId + "' is a test dataset: '" + command + "' would use it for tuning. "
                + "A test dataset is evaluated exactly once, with 'scripts/eval.sh validate'.");
        }
    }
}
