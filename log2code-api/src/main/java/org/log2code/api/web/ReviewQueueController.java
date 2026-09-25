package org.log2code.api.web;

import java.util.List;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.service.ReviewQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/review-queue}: unlabeled logs needing manual review (T25 step 2). */
@RestController
@RequestMapping("/api/review-queue")
public class ReviewQueueController {

    private final ReviewQueueService reviewQueueService;

    public ReviewQueueController(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    @GetMapping
    public List<LogSummary> get(
        @RequestParam(name = "datasetId", required = false) String datasetId,
        @RequestParam(name = "limit", defaultValue = "" + ReviewQueueService.DEFAULT_LIMIT) int limit,
        @RequestParam(name = "seed", defaultValue = "" + ReviewQueueService.DEFAULT_SEED) long seed
    ) {
        return reviewQueueService.reviewQueue(datasetId, limit, seed);
    }
}
