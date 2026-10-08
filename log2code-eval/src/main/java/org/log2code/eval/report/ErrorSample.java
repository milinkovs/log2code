package org.log2code.eval.report;

import org.log2code.eval.metrics.EventEvaluation;

/**
 * One kind of mistake: all evaluable events whose top-1 prediction is wrong (or missing) in the same way,
 * i.e. with the same predicted statement and the same correct statement. {@code event} is the first such
 * event in the dataset, shown as the example.
 *
 * @param events how many events in the dataset made this mistake
 */
public record ErrorSample(EventEvaluation event, int events) {
}
