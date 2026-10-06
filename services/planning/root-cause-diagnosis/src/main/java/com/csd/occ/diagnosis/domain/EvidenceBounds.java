package com.csd.occ.diagnosis.domain;

import java.util.List;
import java.util.Objects;

final class EvidenceBounds {
    private EvidenceBounds() { }

    static <T> List<T> copy(List<T> values, int maxCount, String field) {
        if (values == null || values.size() > maxCount || values.stream().anyMatch(Objects::isNull))
            throw new EvidenceLimitExceededException(field);
        return List.copyOf(values);
    }

    static List<String> copyStrings(List<String> values, int maxCount, int maxLength, String field) {
        List<String> copy = copy(values, maxCount, field);
        if (copy.stream().anyMatch(value -> value.isBlank() || value.length() > maxLength))
            throw new EvidenceLimitExceededException(field);
        return copy;
    }

    static String bounded(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) throw new EvidenceLimitExceededException(field);
        return value;
    }
}
