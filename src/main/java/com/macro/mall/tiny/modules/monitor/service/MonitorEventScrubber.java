package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MonitorEventScrubber {

    private static final String FILTERED = "[Filtered]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "clientsecret", "privatekey", "apikey",
            "authorization", "cookie", "setcookie", "token", "accesstoken", "refreshtoken",
            "session", "sessionid", "sessiontoken", "xapikey", "proxyauthorization",
            "cardnumber", "creditcard", "creditcardnumber", "ccnumber");
    private static final String SENSITIVE_NAME = "(?:token|access[_-]?token|refresh[_-]?token|session[_-]?token|"
            + "authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|client[_-]?secret|private[_-]?key|"
            + "api[_-]?key|x-api-key)";
    private static final String SENSITIVE_QUERY_NAME = "(?:" + SENSITIVE_NAME + "|session(?:id)?|code)";
    private static final Pattern AUTH_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization)\\b\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[^,\\s;&]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b" + SENSITIVE_NAME + "\\b\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^,\\s;&]+)");
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&#]" + SENSITIVE_QUERY_NAME + "=)[^&#\\s]*");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern IPV4_CANDIDATE = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern IPV6_CANDIDATE = Pattern.compile("(?i)(?<![0-9a-f:.])[0-9a-f:.]{2,39}(?:%[a-z0-9_.-]+)?(?![0-9a-f:.])");

    public void scrub(MonitorEventEnvelope event) {
        scrub(event, false, false, false);
    }

    public void scrub(MonitorEventEnvelope event, boolean scrubEmails, boolean scrubCreditCards) {
        scrub(event, scrubEmails, scrubCreditCards, false);
    }

    public void scrub(MonitorEventEnvelope event, boolean scrubEmails, boolean scrubCreditCards,
                      boolean scrubIpAddresses) {
        scrub(event, scrubEmails, scrubCreditCards, scrubIpAddresses, List.of());
    }

    public void scrub(MonitorEventEnvelope event, boolean scrubEmails, boolean scrubCreditCards,
                      boolean scrubIpAddresses, List<String> customSensitiveFields) {
        scrub(event, scrubEmails, scrubCreditCards, scrubIpAddresses, false, false, customSensitiveFields);
    }

    public void scrub(MonitorEventEnvelope event, boolean scrubEmails, boolean scrubCreditCards,
                      boolean scrubIpAddresses, boolean scrubPhoneNumbers, boolean scrubChineseIdNumbers,
                      List<String> customSensitiveFields) {
        Set<String> customKeys = MonitorSensitiveFieldNames.normalizedSet(customSensitiveFields);
        event.setPageUrl(sanitize(event.getPageUrl(), scrubEmails, scrubCreditCards, scrubIpAddresses,
                scrubPhoneNumbers, scrubChineseIdNumbers));
        event.setDevice(scrubMap(event.getDevice(), scrubEmails, scrubCreditCards, scrubIpAddresses,
                scrubPhoneNumbers, scrubChineseIdNumbers, customKeys));
        event.setData(scrubMap(event.getData(), scrubEmails, scrubCreditCards, scrubIpAddresses,
                scrubPhoneNumbers, scrubChineseIdNumbers, customKeys));
    }

    private Map<String, Object> scrubMap(Map<String, Object> source, boolean scrubEmails,
                                         boolean scrubCreditCards, boolean scrubIpAddresses,
                                         boolean scrubPhoneNumbers, boolean scrubChineseIdNumbers, Set<String> customKeys) {
        if (source == null || source.isEmpty()) return source;
        Map<String, Object> scrubbed = new LinkedHashMap<>();
        source.forEach((key, value) -> scrubbed.put(key,
                isSensitiveKey(key, customKeys) ? FILTERED
                        : scrubValue(value, scrubEmails, scrubCreditCards, scrubIpAddresses,
                        scrubPhoneNumbers, scrubChineseIdNumbers, customKeys)));
        return scrubbed;
    }

    private Object scrubValue(Object value, boolean scrubEmails, boolean scrubCreditCards, boolean scrubIpAddresses,
                              boolean scrubPhoneNumbers, boolean scrubChineseIdNumbers, Set<String> customKeys) {
        if (value instanceof String text) return sanitize(text, scrubEmails, scrubCreditCards, scrubIpAddresses,
                scrubPhoneNumbers, scrubChineseIdNumbers);
        if (scrubCreditCards && value instanceof Number number) {
            String digits = number.toString();
            if (digits.matches("\\d{13,19}") && passesLuhn(digits)) return FILTERED;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> scrubbed = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                scrubbed.put(name, isSensitiveKey(name, customKeys) ? FILTERED
                        : scrubValue(nested, scrubEmails, scrubCreditCards, scrubIpAddresses,
                        scrubPhoneNumbers, scrubChineseIdNumbers, customKeys));
            });
            return scrubbed;
        }
        if (value instanceof List<?> list) {
            List<Object> scrubbed = new ArrayList<>(list.size());
            list.forEach(item -> scrubbed.add(scrubValue(item, scrubEmails, scrubCreditCards, scrubIpAddresses,
                    scrubPhoneNumbers, scrubChineseIdNumbers, customKeys)));
            return scrubbed;
        }
        return value;
    }

    private String sanitize(String value, boolean scrubEmails, boolean scrubCreditCards, boolean scrubIpAddresses,
                            boolean scrubPhoneNumbers, boolean scrubChineseIdNumbers) {
        if (value == null || value.isEmpty()) return value;
        String sanitized = URL_USER_INFO.matcher(value).replaceAll("$1[Filtered]@");
        sanitized = SENSITIVE_QUERY.matcher(sanitized).replaceAll("$1" + FILTERED);
        sanitized = AUTH_ASSIGNMENT.matcher(sanitized).replaceAll("$1" + FILTERED);
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1" + FILTERED);
        sanitized = AUTH_SCHEME.matcher(sanitized).replaceAll("$1 " + FILTERED);
        if (scrubEmails) sanitized = EMAIL.matcher(sanitized).replaceAll(FILTERED);
        if (scrubCreditCards) sanitized = scrubCardNumbers(sanitized);
        if (scrubIpAddresses) sanitized = scrubIpAddresses(sanitized);
        if (scrubPhoneNumbers) sanitized = MonitorPiiScrubber.scrubPhoneNumbers(sanitized, FILTERED);
        if (scrubChineseIdNumbers) sanitized = MonitorPiiScrubber.scrubChineseIdNumbers(sanitized, FILTERED);
        return sanitized;
    }

    private String scrubIpAddresses(String value) {
        Matcher ipv4Matcher = IPV4_CANDIDATE.matcher(value);
        StringBuffer ipv4Result = new StringBuffer();
        while (ipv4Matcher.find()) {
            String candidate = ipv4Matcher.group();
            boolean valid = true;
            for (String octet : candidate.split("\\.")) {
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    valid = false;
                    break;
                }
            }
            ipv4Matcher.appendReplacement(ipv4Result,
                    Matcher.quoteReplacement(valid ? FILTERED : candidate));
        }
        ipv4Matcher.appendTail(ipv4Result);

        Matcher ipv6Matcher = IPV6_CANDIDATE.matcher(ipv4Result.toString());
        StringBuffer result = new StringBuffer();
        while (ipv6Matcher.find()) {
            String candidate = ipv6Matcher.group();
            String literal = candidate.replaceFirst("%[a-zA-Z0-9_.-]+$", "");
            boolean valid = candidate.chars().filter(character -> character == ':').count() >= 2
                    && isIpv6Literal(literal);
            ipv6Matcher.appendReplacement(result, Matcher.quoteReplacement(valid ? FILTERED : candidate));
        }
        ipv6Matcher.appendTail(result);
        return result.toString();
    }

    private boolean isIpv6Literal(String literal) {
        try {
            return InetAddress.getByName(literal) instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private String scrubCardNumbers(String value) {
        Matcher matcher = CARD_CANDIDATE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("\\D", "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    digits.length() >= 13 && digits.length() <= 19 && passesLuhn(digits) ? FILTERED : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int value = digits.charAt(index) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) value -= 9;
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private boolean isSensitiveKey(String key, Set<String> customKeys) {
        String normalized = normalizeKey(key);
        return SENSITIVE_KEYS.contains(normalized) || customKeys.contains(normalized);
    }
}
