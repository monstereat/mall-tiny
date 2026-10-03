package com.macro.mall.tiny.modules.monitor.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

public final class MonitorMetricFormulaEvaluator {
    private MonitorMetricFormulaEvaluator() { }

    public static Set<Character> validate(String expression, int metricCount) {
        return parse(expression, metricCount).variables();
    }

    public static Double evaluate(String expression, int metricCount, Map<Character, Double> values) {
        try {
            double result = parse(expression, metricCount).root().applyAsDouble(values);
            return Double.isFinite(result) ? result : null;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static Parsed parse(String expression, int metricCount) {
        if (expression == null || expression.isBlank() || expression.length() > 128 || metricCount < 2 || metricCount > 5) {
            throw new IllegalArgumentException("invalid metric formula");
        }
        Parser parser = new Parser(expression, metricCount);
        ToDoubleFunction<Map<Character, Double>> root = parser.expression();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw new IllegalArgumentException("invalid metric formula syntax");
        Set<Character> required = new HashSet<>();
        for (int i = 0; i < metricCount; i++) required.add((char) ('a' + i));
        if (!parser.variables.equals(required)) throw new IllegalArgumentException("formula must use every selected metric alias");
        return new Parsed(root, Set.copyOf(parser.variables));
    }

    private record Parsed(ToDoubleFunction<Map<Character, Double>> root, Set<Character> variables) { }

    private static final class Parser {
        private final String source;
        private final int metricCount;
        private final Set<Character> variables = new HashSet<>();
        private int cursor;

        private Parser(String source, int metricCount) {
            this.source = source;
            this.metricCount = metricCount;
        }

        private ToDoubleFunction<Map<Character, Double>> expression() {
            ToDoubleFunction<Map<Character, Double>> left = term();
            while (true) {
                skipWhitespace();
                if (take('+')) {
                    ToDoubleFunction<Map<Character, Double>> right = term();
                    ToDoubleFunction<Map<Character, Double>> previous = left;
                    left = values -> previous.applyAsDouble(values) + right.applyAsDouble(values);
                } else if (take('-')) {
                    ToDoubleFunction<Map<Character, Double>> right = term();
                    ToDoubleFunction<Map<Character, Double>> previous = left;
                    left = values -> previous.applyAsDouble(values) - right.applyAsDouble(values);
                } else {
                    return left;
                }
            }
        }

        private ToDoubleFunction<Map<Character, Double>> term() {
            ToDoubleFunction<Map<Character, Double>> left = unary();
            while (true) {
                skipWhitespace();
                if (take('*')) {
                    ToDoubleFunction<Map<Character, Double>> right = unary();
                    ToDoubleFunction<Map<Character, Double>> previous = left;
                    left = values -> previous.applyAsDouble(values) * right.applyAsDouble(values);
                } else if (take('/')) {
                    ToDoubleFunction<Map<Character, Double>> right = unary();
                    ToDoubleFunction<Map<Character, Double>> previous = left;
                    left = values -> {
                        double divisor = right.applyAsDouble(values);
                        if (divisor == 0) throw new ArithmeticException("division by zero");
                        return previous.applyAsDouble(values) / divisor;
                    };
                } else {
                    return left;
                }
            }
        }

        private ToDoubleFunction<Map<Character, Double>> unary() {
            skipWhitespace();
            if (take('+')) return unary();
            if (take('-')) {
                ToDoubleFunction<Map<Character, Double>> nested = unary();
                return values -> -nested.applyAsDouble(values);
            }
            return primary();
        }

        private ToDoubleFunction<Map<Character, Double>> primary() {
            skipWhitespace();
            if (take('(')) {
                ToDoubleFunction<Map<Character, Double>> nested = expression();
                skipWhitespace();
                if (!take(')')) throw new IllegalArgumentException("unclosed formula parenthesis");
                return nested;
            }
            if (atEnd()) throw new IllegalArgumentException("incomplete metric formula");
            char current = source.charAt(cursor);
            if (current >= 'a' && current <= 'e') {
                cursor++;
                if (current - 'a' >= metricCount) throw new IllegalArgumentException("formula references an unknown metric alias");
                variables.add(current);
                return values -> {
                    Double value = values.get(current);
                    if (value == null) throw new IllegalArgumentException("metric value is missing");
                    return value;
                };
            }
            return number();
        }

        private ToDoubleFunction<Map<Character, Double>> number() {
            int start = cursor;
            boolean decimal = false;
            while (!atEnd()) {
                char c = source.charAt(cursor);
                if (c >= '0' && c <= '9') cursor++;
                else if (c == '.' && !decimal) { decimal = true; cursor++; }
                else break;
            }
            if (start == cursor || source.charAt(cursor - 1) == '.') {
                throw new IllegalArgumentException("invalid metric formula token");
            }
            double value;
            try { value = Double.parseDouble(source.substring(start, cursor)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("invalid metric formula number", e); }
            if (!Double.isFinite(value)) throw new IllegalArgumentException("formula number must be finite");
            return ignored -> value;
        }

        private boolean take(char expected) {
            if (atEnd() || source.charAt(cursor) != expected) return false;
            cursor++;
            return true;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        }

        private boolean atEnd() { return cursor >= source.length(); }
    }
}
