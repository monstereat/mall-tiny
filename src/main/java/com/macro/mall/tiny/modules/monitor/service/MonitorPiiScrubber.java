package com.macro.mall.tiny.modules.monitor.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MonitorPiiScrubber {

    private static final Pattern PHONE_CANDIDATE = Pattern.compile("(?<![\\d+])(?:\\+?86[ -]?)?(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern CHINESE_ID_CANDIDATE = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private MonitorPiiScrubber() {
    }

    static String scrubPhoneNumbers(String value, String replacement) {
        Matcher matcher = PHONE_CANDIDATE.matcher(value);
        return matcher.replaceAll(Matcher.quoteReplacement(replacement));
    }

    static String scrubChineseIdNumbers(String value, String replacement) {
        Matcher matcher = CHINESE_ID_CANDIDATE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String candidate = matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    isValidChineseId(candidate) ? replacement : candidate));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isValidChineseId(String value) {
        try {
            int year = Integer.parseInt(value.substring(6, 10));
            if (year == 0) return false;
            LocalDate.of(year, Integer.parseInt(value.substring(10, 12)),
                    Integer.parseInt(value.substring(12, 14)));
        } catch (DateTimeException | NumberFormatException invalidDate) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < ID_WEIGHTS.length; index++) {
            sum += (value.charAt(index) - '0') * ID_WEIGHTS[index];
        }
        char expected = ID_CHECK_CODES[sum % 11];
        return Character.toUpperCase(value.charAt(17)) == expected;
    }
}
