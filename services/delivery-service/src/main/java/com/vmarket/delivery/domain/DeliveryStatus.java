package com.vmarket.delivery.domain;

import java.util.EnumSet;
import java.util.Set;

/** Vong doi chuyen giao - so do o review PR 68 lan 1, muc 1.2. */
public enum DeliveryStatus {
    WAITING_HANDOFF,
    WAITING_ASSIGNMENT,
    ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    FAILED,
    RETURNING,
    /** Shipper da tra hang cho moi shop, cho seller xac nhan nhan lai (hoac tu dong sau 3 ngay). */
    RETURN_DROPPED_OFF,
    RETURNED,
    DELIVERED;

    private static final Set<DeliveryStatus> SHIPPER_ACTIVE =
            EnumSet.of(ASSIGNED, PICKED_UP, IN_TRANSIT, FAILED, RETURNING);

    /** Shipper gui GPS o cac trang thai nay. */
    public boolean tracking() {
        return this == PICKED_UP || this == IN_TRANSIT || this == RETURNING;
    }

    /** Nguoi mua chi xem vi tri shipper khi hang dang tren duong toi (NFR-SEC-07). */
    public boolean buyerTracking() {
        return this == PICKED_UP || this == IN_TRANSIT;
    }

    /** Shipper con viec phai lam (tab "Dang xu ly"). */
    public boolean shipperActive() {
        return SHIPPER_ACTIVE.contains(this);
    }

    /** Hang da roi khoi shop: khong con doi shipper duoc, chi chuyen hoan hang. */
    public boolean pickedUp() {
        return this == PICKED_UP || this == IN_TRANSIT || this == FAILED || this == RETURNING;
    }

    public boolean terminal() {
        return this == RETURNED || this == DELIVERED;
    }
}
