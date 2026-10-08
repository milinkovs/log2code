package org.log2code.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvTest {

    @Test
    void fieldsAreQuotedOnlyWhenTheyNeedToBe() {
        assertThat(Csv.field("plain")).isEqualTo("plain");
        assertThat(Csv.field("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.field("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(Csv.field(null)).isEmpty();
        assertThat(Csv.field(42)).isEqualTo("42");
    }

    @Test
    void doublesUseAFixedLocaleIndependentFormat() {
        assertThat(Csv.field(0.5)).isEqualTo("0.5000");
        assertThat(Csv.field(1.0 / 3)).isEqualTo("0.3333");
    }

    @Test
    void writesHeaderAndRowsAsUtf8WithLfLineEnds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.csv");

        Csv.write(file, List.of("a", "b"), List.of(Arrays.asList("šđčćž", "x,y"), Arrays.asList(null, 1)));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("a,b\nšđčćž,\"x,y\"\n,1\n");
    }
}
