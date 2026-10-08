package org.log2code.eval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunCommandTest {

    @Test
    void aBareIdAndADatasetFolderMeanTheSameDataset() {
        assertThat(RunCommand.datasetId("tune-02")).isEqualTo("tune-02");
        assertThat(RunCommand.datasetId("datasets/tune-02")).isEqualTo("tune-02");
        assertThat(RunCommand.datasetId("datasets/tune-02/")).isEqualTo("tune-02");
    }
}
