package org.log2code.api.web;

import java.util.List;
import org.log2code.api.dto.CodeUnitSummary;
import org.log2code.api.dto.DatasetSummary;
import org.log2code.api.service.MetaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/meta/*}: filter metadata for the web app (T23 step 3). */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final MetaService metaService;

    public MetaController(MetaService metaService) {
        this.metaService = metaService;
    }

    @GetMapping("/datasets")
    public List<DatasetSummary> datasets() {
        return metaService.datasets();
    }

    @GetMapping("/services")
    public List<String> services(@RequestParam(name = "datasetId", required = false) String datasetId) {
        return metaService.services(datasetId);
    }

    @GetMapping("/code-units")
    public List<CodeUnitSummary> codeUnits() {
        return metaService.codeUnits();
    }
}
