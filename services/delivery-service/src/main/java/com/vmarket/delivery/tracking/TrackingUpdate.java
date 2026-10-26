package com.vmarket.delivery.tracking;

import com.vmarket.delivery.domain.Assignment;

import java.util.Map;

public final class TrackingUpdate {
    private TrackingUpdate() {}

    public static Map<String, Object> from(Assignment a) {
        return Map.of(
                "assignmentId",
                a.getId(),
                "version",
                a.getVersion(),
                "sequence",
                a.getTrackingSequence(),
                "locationSequence",
                a.getLocationSequence(),
                "status",
                a.getStatus().name(),
                "active",
                a.getStatus().tracking(),
                "location",
                a.getLatitude() == null
                        ? Map.of()
                        : Map.of(
                                "latitude",
                                a.getLatitude(),
                                "longitude",
                                a.getLongitude(),
                                "recordedAt",
                                a.getLocationAt().toString()));
    }
}
