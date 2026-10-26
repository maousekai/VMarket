package com.vmarket.delivery.tracking;

/** Empty actor revokes every viewer of an assignment; otherwise only that actor is revoked. */
public record TrackingRevoked(String assignmentId, String actor) {}
