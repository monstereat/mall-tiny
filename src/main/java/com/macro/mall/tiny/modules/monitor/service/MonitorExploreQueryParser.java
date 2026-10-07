package com.macro.mall.tiny.modules.monitor.service;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MonitorExploreQueryParser {
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int MAX_TERMS = 20;
    private static final Pattern TAG_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Set<String> FILTER_FIELDS = Set.of(
            "environment", "release", "trace", "trace_id", "user", "user.id",
            "url", "event", "event.id", "level", "tag", "text", "message"
    );

    public record Term(String field, String tagKey, String value, boolean negated) { }
    public sealed interface Node permits Filter, Junction { }
    public record Filter(Term term) implements Node { }
    public record Junction(boolean or, List<Node> children) implements Node { }
    public record Parsed(String text, List<Term> terms, Node expression) { }

    private MonitorExploreQueryParser() { }

    public static Parsed parse(String query) {
        if (!StringUtils.hasText(query)) return new Parsed("", List.of(), null);
        if (query.length() > MAX_QUERY_LENGTH) {
            throw invalid("query must be at most 512 characters");
        }
        List<String> tokens = tokenize(query);
        boolean grouped = tokens.stream().anyMatch(token -> "OR".equalsIgnoreCase(token)
                || "AND".equalsIgnoreCase(token) || "(".equals(token) || ")".equals(token));
        if (grouped) {
            ExpressionParser parser = new ExpressionParser(tokens);
            Node expression = parser.parse();
            return new Parsed("", List.copyOf(parser.terms), expression);
        }
        List<String> text = new ArrayList<>();
        List<Term> terms = new ArrayList<>();
        for (String token : tokens) {
            Term term = parseFilter(token);
            if (term == null) {
                int separator = token.indexOf(':');
                String rawField = separator > 0 ? token.substring(0, separator) : "";
                if ("text".equalsIgnoreCase(rawField) || "message".equalsIgnoreCase(rawField)) {
                    String value = token.substring(separator + 1);
                    if (value.isEmpty()) throw invalid("filter value is missing for " + rawField);
                    text.add(value);
                } else {
                    text.add(token);
                }
                continue;
            }
            terms.add(term);
            if (terms.size() > MAX_TERMS) throw invalid("query supports at most 20 field filters");
        }
        return new Parsed(String.join(" ", text), List.copyOf(terms), null);
    }

    private static Term parseFilter(String token) {
        int separator = token.indexOf(':');
        String rawField = separator > 0 ? token.substring(0, separator) : "";
        String field = rawField.toLowerCase(Locale.ROOT);
        boolean tagFilter = field.startsWith("tag.");
        if (!FILTER_FIELDS.contains(field) && !tagFilter) return null;
        if (separator == token.length() - 1) throw invalid("filter value is missing for " + rawField);
        String value = token.substring(separator + 1);
        if ("text".equals(field) || "message".equals(field)) return null;
        boolean negated = value.startsWith("!");
        if (negated) value = value.substring(1);
        if (value.isEmpty()) throw invalid("filter value is missing for " + rawField);
        String tagKey = null;
        if ("tag".equals(field) || tagFilter) {
            int dot = rawField.indexOf('.');
            if (dot <= 0 || !TAG_KEY.matcher(rawField.substring(dot + 1)).matches()) {
                throw invalid("tag filters must use tag.<key>:value");
            }
            tagKey = rawField.substring(dot + 1);
            field = "tag";
        } else if ("trace_id".equals(field)) {
            field = "trace";
        } else if ("user.id".equals(field)) {
            field = "user";
        } else if ("event.id".equals(field)) {
            field = "event";
        }
        return new Term(field, tagKey, value, negated);
    }

    private static final class ExpressionParser {
        private final List<String> tokens;
        private final List<Term> terms = new ArrayList<>();
        private int index;

        private ExpressionParser(List<String> tokens) {
            this.tokens = tokens;
        }

        private Node parse() {
            Node result = parseOr();
            if (index != tokens.size()) throw invalid("unexpected token in field expression: " + tokens.get(index));
            if (terms.size() > MAX_TERMS) throw invalid("query supports at most 20 field filters");
            return result;
        }

        private Node parseOr() {
            List<Node> children = new ArrayList<>();
            children.add(parseAnd());
            while (hasNext() && "OR".equalsIgnoreCase(peek())) {
                index++;
                children.add(parseAnd());
            }
            return children.size() == 1 ? children.get(0) : new Junction(true, List.copyOf(children));
        }

        private Node parseAnd() {
            List<Node> children = new ArrayList<>();
            children.add(parsePrimary());
            while (hasNext() && !")".equals(peek()) && !"OR".equalsIgnoreCase(peek())) {
                if ("AND".equalsIgnoreCase(peek())) index++;
                children.add(parsePrimary());
            }
            return children.size() == 1 ? children.get(0) : new Junction(false, List.copyOf(children));
        }

        private Node parsePrimary() {
            if (!hasNext()) throw invalid("field expression is incomplete");
            String token = tokens.get(index++);
            if ("(".equals(token)) {
                Node nested = parseOr();
                if (!hasNext() || !")".equals(peek())) throw invalid("field expression has an unmatched parenthesis");
                index++;
                return nested;
            }
            if (")".equals(token) || "OR".equalsIgnoreCase(token) || "AND".equalsIgnoreCase(token)) {
                throw invalid("field expression is incomplete");
            }
            Term term = parseFilter(token);
            if (term == null) throw invalid("OR and parentheses support field filters only");
            terms.add(term);
            return new Filter(term);
        }

        private boolean hasNext() { return index < tokens.size(); }
        private String peek() { return tokens.get(index); }
    }

    private static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < query.length(); i++) {
            char current = query.charAt(i);
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (quoted && current == '\\') {
                escaped = true;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (!quoted && (current == '(' || current == ')')) {
                addToken(tokens, token);
                tokens.add(String.valueOf(current));
            } else if (Character.isWhitespace(current) && !quoted) {
                addToken(tokens, token);
            } else {
                token.append(current);
            }
        }
        if (quoted || escaped) throw invalid("query contains an unterminated quoted value");
        addToken(tokens, token);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (token.length() == 0) return;
        tokens.add(token.toString());
        token.setLength(0);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
