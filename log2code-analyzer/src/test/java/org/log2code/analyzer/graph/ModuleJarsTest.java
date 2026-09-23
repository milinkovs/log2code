package org.log2code.analyzer.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleJarsTest {

    @Test
    void missingManifestIsEmpty(@TempDir Path tempDir) {
        Optional<Map<String, List<Path>>> result = ModuleJars.load(tempDir.resolve("deps-manifest.json"));
        assertThat(result).isEmpty();
    }

    @Test
    void loadsEveryJarRegardlessOfSelectedFlag(@TempDir Path tempDir) throws IOException {
        Path realJar = tempDir.resolve("real.jar");
        Files.write(realJar, new byte[]{'P', 'K'}); // just needs to exist for isRegularFile
        Path manifest = tempDir.resolve("deps-manifest.json");
        Files.writeString(manifest, """
            {
              "project_commit": "abc",
              "modules": [
                { "module": "module-a", "service": "a", "artifacts": [
                  { "gav": "g:a:1", "jar": "%s", "selected": false, "logger_hits": 0, "sources_missing": false },
                  { "gav": "g:b:1", "jar": "%s/missing.jar", "selected": true, "logger_hits": 1, "sources_missing": false }
                ] }
              ]
            }
            """.formatted(realJar.toString().replace("\\", "\\\\"), tempDir.toString().replace("\\", "\\\\")));

        Optional<Map<String, List<Path>>> result = ModuleJars.load(manifest);

        assertThat(result).isPresent();
        // the unselected-but-existing jar is included; the selected-but-missing one is filtered out
        assertThat(result.get().get("module-a")).containsExactly(realJar);
    }
}
