package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonitorMetricFormulaEvaluatorTest {

    @Test
    void evaluatesPrecedenceParenthesesAndUnaryOperators() {
        assertEquals(7.0, MonitorMetricFormulaEvaluator.evaluate("-a + (b * 2)", 2, Map.of('a', 3.0, 'b', 5.0)));
    }

    @Test
    void rejectsUnknownSyntaxAliasesAndUnusedMetrics() {
        assertThrows(IllegalArgumentException.class, () -> MonitorMetricFormulaEvaluator.validate("a / c", 2));
        assertThrows(IllegalArgumentException.class, () -> MonitorMetricFormulaEvaluator.validate("a / b;drop", 2));
        assertThrows(IllegalArgumentException.class, () -> MonitorMetricFormulaEvaluator.validate("a + 1", 2));
    }

    @Test
    void omitsDivisionByZeroAndNonFiniteResults() {
        assertNull(MonitorMetricFormulaEvaluator.evaluate("a / b", 2, Map.of('a', 4.0, 'b', 0.0)));
        assertNull(MonitorMetricFormulaEvaluator.evaluate("a * b", 2, Map.of('a', Double.MAX_VALUE, 'b', 2.0)));
    }

    @Test
    void acceptsOnlyTheAliasesForSelectedMetricCount() {
        assertEquals(Set.of('a', 'b', 'c', 'd', 'e'),
                MonitorMetricFormulaEvaluator.validate("(a + b) / (c + d + e)", 5));
    }
}
