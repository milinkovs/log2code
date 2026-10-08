package org.log2code.eval.replay;

import java.util.Map;
import org.log2code.core.model.CodeVersion;

/** Lets tests outside this package use the package-private {@link SyntheticDataset}. */
public final class SyntheticDatasetAccess {

    private SyntheticDatasetAccess() {
    }

    public static ReplayDataset dataset() {
        return SyntheticDataset.dataset(Map.of());
    }

    public static CodeVersion code() {
        return SyntheticDataset.CODE;
    }

    public static org.log2code.eval.truth.CatalogView catalog() {
        return SyntheticDataset.view();
    }
}
