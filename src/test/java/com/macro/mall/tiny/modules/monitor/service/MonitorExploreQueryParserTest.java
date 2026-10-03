package com.macro.mall.tiny.modules.monitor.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorExploreQueryParserTest {
    @Test
    void parsesQuotedFieldFiltersAndLeavesFreeTextSearch() {
        var query = MonitorExploreQueryParser.parse(
                "environment:production release:\"web build 42\" trace_id:!abc123 tag.region:cn-east text:\"checkout failed\"");

        assertEquals("checkout failed", query.text());
        assertEquals(4, query.terms().size());
        assertEquals(new MonitorExploreQueryParser.Term("environment", null, "production", false), query.terms().get(0));
        assertEquals(new MonitorExploreQueryParser.Term("release", null, "web build 42", false), query.terms().get(1));
        assertEquals(new MonitorExploreQueryParser.Term("trace", null, "abc123", true), query.terms().get(2));
        assertEquals(new MonitorExploreQueryParser.Term("tag", "region", "cn-east", false), query.terms().get(3));
    }

    @Test
    void preservesPlainTextAndUrlSearches() {
        var query = MonitorExploreQueryParser.parse("checkout failed https://example.test/orders");

        assertEquals("checkout failed https://example.test/orders", query.text());
        assertEquals(0, query.terms().size());
    }

    @Test
    void parsesOrGroupsWithImplicitAndAndExplicitParentheses() {
        var query = MonitorExploreQueryParser.parse(
                "(environment:production OR release:\"staging build\") level:error");

        assertEquals(3, query.terms().size());
        assertTrue(query.expression() instanceof MonitorExploreQueryParser.Junction outer && !outer.or());
        var outer = (MonitorExploreQueryParser.Junction) query.expression();
        assertTrue(outer.children().get(0) instanceof MonitorExploreQueryParser.Junction inner && inner.or());
    }

    @Test
    void rejectsIncompleteGroupsAndFreeTextMixedWithBooleanFieldExpressions() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse("environment:prod OR")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse("(environment:prod release:stage")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse("checkout OR level:error")).getStatusCode().value());
    }

    @Test
    void rejectsMalformedOrExcessiveFieldExpressions() {
        ResponseStatusException unterminated = assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse("release:\"unfinished"));
        assertEquals(400, unterminated.getStatusCode().value());

        ResponseStatusException missingTag = assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse("tag:value"));
        assertEquals(400, missingTag.getStatusCode().value());

        String manyTerms = "environment:a ".repeat(21);
        ResponseStatusException tooMany = assertThrows(ResponseStatusException.class,
                () -> MonitorExploreQueryParser.parse(manyTerms));
        assertEquals(400, tooMany.getStatusCode().value());
    }
}
