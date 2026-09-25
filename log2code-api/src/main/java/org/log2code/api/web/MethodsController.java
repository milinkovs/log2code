package org.log2code.api.web;

import java.util.List;
import org.log2code.api.dto.CallerDto;
import org.log2code.api.dto.MethodDetailDto;
import org.log2code.api.service.MethodGraphService;
import org.log2code.core.model.MethodInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/methods}: a project method's detail and its callers, for lazy tree expansion (T24 step 1, level 3 / T13). */
@RestController
@RequestMapping("/api/methods")
public class MethodsController {

    private final MethodGraphService service;

    public MethodsController(MethodGraphService service) {
        this.service = service;
    }

    @GetMapping("/{methodId}")
    public MethodDetailDto get(@PathVariable("methodId") String methodId) {
        return service.toDetail(fetchOrThrow(methodId));
    }

    @GetMapping("/{methodId}/callers")
    public List<CallerDto> callers(@PathVariable("methodId") String methodId) {
        return service.callers(fetchOrThrow(methodId));
    }

    private MethodInfo fetchOrThrow(String methodId) {
        MethodInfo info = service.fetchMethod(methodId);
        if (info == null) {
            throw new NotFoundException("method not found: " + methodId);
        }
        return info;
    }
}
