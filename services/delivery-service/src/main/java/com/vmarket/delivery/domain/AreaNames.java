package com.vmarket.delivery.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Matching key for free-text Vietnamese addresses; the original address stays in the snapshot. */
public final class AreaNames {
    private AreaNames() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String key =
                Normalizer.normalize(value, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                        .toLowerCase(Locale.ROOT)
                        .replace('đ', 'd')
                        .replaceAll("[\\p{Z}\\s]+", " ")
                        .strip();
        return key.replaceFirst(
                        "^(thanh pho|thi xa|tinh|quan|huyen|tp|tx|q|h)(?:\\s*\\.\\s*|\\s+)", "")
                .strip();
    }
}
