package com.vmarket.delivery.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Quy tac giao hang doc tu delivery-rules.json - ban sao sinh tu shared/delivery (mot nguon cho
 * backend, web va mobile; xem scripts/sync-delivery-rules.mjs).
 */
public final class DeliveryRules {
    public record FailureReason(
            String code,
            String label,
            boolean returnImmediately,
            boolean shipperFault,
            boolean noteRequired) {}

    private static final DeliveryRules INSTANCE = load();

    private final Map<String, FailureReason> reasons;
    private final Map<String, String> wardToZone;
    private final Map<String, String> zoneNames;
    private final int retryHours;
    private final int maxAttempts;
    private final int returnAutoCloseDays;

    private DeliveryRules(
            Map<String, FailureReason> reasons,
            Map<String, String> wardToZone,
            Map<String, String> zoneNames,
            int retryHours,
            int maxAttempts,
            int returnAutoCloseDays) {
        this.reasons = reasons;
        this.wardToZone = wardToZone;
        this.zoneNames = zoneNames;
        this.retryHours = retryHours;
        this.maxAttempts = maxAttempts;
        this.returnAutoCloseDays = returnAutoCloseDays;
    }

    public static DeliveryRules get() {
        return INSTANCE;
    }

    private static DeliveryRules load() {
        try (InputStream in = DeliveryRules.class.getResourceAsStream("/delivery-rules.json")) {
            if (in == null) throw new IllegalStateException("Thiếu delivery-rules.json");
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, FailureReason> reasons = new LinkedHashMap<>();
            for (JsonNode r : root.path("failureReasons")) {
                reasons.put(
                        r.path("code").asString(),
                        new FailureReason(
                                r.path("code").asString(),
                                r.path("label").asString(),
                                r.path("returnImmediately").asBoolean(),
                                r.path("shipperFault").asBoolean(),
                                r.path("noteRequired").asBoolean()));
            }
            Map<String, String> wards = new LinkedHashMap<>();
            Map<String, String> zones = new LinkedHashMap<>();
            for (JsonNode zone : root.path("zones").path("list")) {
                String name = zone.path("name").asString();
                zones.put(AreaNames.normalize(name), name);
                for (JsonNode ward : zone.path("wards"))
                    wards.put(AreaNames.normalize(ward.asString()), AreaNames.normalize(name));
            }
            return new DeliveryRules(
                    Collections.unmodifiableMap(reasons),
                    Collections.unmodifiableMap(wards),
                    Collections.unmodifiableMap(zones),
                    root.path("retry").path("minHoursBetweenAttempts").asInt(2),
                    root.path("retry").path("maxAttempts").asInt(3),
                    root.path("returnAutoCloseDays").asInt(3));
        } catch (IOException ex) {
            throw new IllegalStateException("Không đọc được delivery-rules.json", ex);
        }
    }

    public Optional<FailureReason> reason(String code) {
        return Optional.ofNullable(code == null ? null : reasons.get(code));
    }

    public Map<String, FailureReason> reasons() {
        return reasons;
    }

    /** Khu giao hang (khoa da chuan hoa) cua mot phuong/xa; phuong la thi rong. */
    public Optional<String> zoneOfWard(String ward) {
        return Optional.ofNullable(wardToZone.get(AreaNames.normalize(ward)));
    }

    /** Ten co dau cua khu tu khoa da chuan hoa. */
    public String zoneName(String key) {
        return key == null ? null : zoneNames.getOrDefault(AreaNames.normalize(key), key);
    }

    public int retryHours() {
        return retryHours;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int returnAutoCloseDays() {
        return returnAutoCloseDays;
    }
}
