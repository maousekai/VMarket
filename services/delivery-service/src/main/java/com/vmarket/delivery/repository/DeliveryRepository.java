package com.vmarket.delivery.repository;

import com.vmarket.delivery.domain.*;
import com.vmarket.delivery.exception.ApiException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class DeliveryRepository {
    private final JdbcTemplate jdbc;

    public static List<Map<String, Object>> lowercaseKeys(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(
                        row -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            row.forEach(
                                    (key, value) ->
                                            result.put(key.toLowerCase(Locale.ROOT), value));
                            return result;
                        })
                .toList();
    }

    private static Instant instant(ResultSet row, String col) throws SQLException {
        var value = row.getTimestamp(col);
        return value == null ? null : value.toInstant();
    }

    private Assignment map(ResultSet r, int index) throws SQLException {
        Assignment a = new Assignment();
        a.setId(r.getString("id"));
        a.setOrderId(r.getString("order_id"));
        a.setBuyerId(r.getString("buyer_id"));
        a.setShipperId(r.getString("shipper_id"));
        a.setStatus(DeliveryStatus.valueOf(r.getString("status")));
        a.setAttempt(r.getInt("attempt"));
        a.setPaymentMethod(r.getString("payment_method"));
        a.setCodAmount(r.getLong("cod_amount"));
        a.setProvince(r.getString("province"));
        a.setDistrict(r.getString("district"));
        a.setOrderSnapshot(r.getString("order_snapshot"));
        a.setVersion(r.getLong("version"));
        a.setLatitude(r.getObject("latitude", Double.class));
        a.setLongitude(r.getObject("longitude", Double.class));
        a.setLocationAt(instant(r, "location_at"));
        a.setCreatedAt(instant(r, "created_at"));
        a.setUpdatedAt(instant(r, "updated_at"));
        a.setLocationSequence(r.getLong("location_sequence"));
        a.setTrackingSequence(r.getLong("tracking_sequence"));
        a.setWard(r.getString("ward"));
        a.setFailureReasonCode(r.getString("failure_reason_code"));
        a.setLastFailedAt(instant(r, "last_failed_at"));
        a.setNeedsAttention(r.getBoolean("needs_attention"));
        a.setGpsSource(r.getString("gps_source"));
        a.setGpsSourceAt(instant(r, "gps_source_at"));
        a.setDroppedOffAt(instant(r, "dropped_off_at"));
        return a;
    }

    public Optional<Assignment> findOrder(String order, boolean lock) {
        return jdbc
                .query(
                        "select * from delivery_assignments where order_id = ?"
                                + (lock ? " for update" : ""),
                        this::map,
                        order)
                .stream()
                .findFirst();
    }

    public boolean assignmentClaimValid(String id, String token, Instant now) {
        return jdbc.queryForObject(
                        """
select count(*) from delivery_assignments where id=? and assignment_claim_token=?
  and assignment_claim_until > ?
""",
                        Integer.class,
                        id,
                        token,
                        Timestamp.from(now))
                == 1;
    }

    public Assignment find(String id, boolean lock) {
        return jdbc
                .query(
                        "select * from delivery_assignments where id = ?"
                                + (lock ? " for update" : ""),
                        this::map,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> ApiException.notFound("DELIVERY_NOT_FOUND", "Không có chuyến giao"));
    }

    public void insert(Assignment a) {
        jdbc.update(
                """
insert into delivery_assignments(id, order_id, buyer_id, status, attempt, payment_method, cod_amount,
    province, district, ward, order_snapshot, version, created_at, updated_at)
values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
""",
                a.getId(),
                a.getOrderId(),
                a.getBuyerId(),
                a.getStatus().name(),
                a.getPaymentMethod(),
                a.getCodAmount(),
                a.getProvince(),
                a.getDistrict(),
                a.getWard(),
                a.getOrderSnapshot());
    }

    public void save(Assignment a) {
        jdbc.update(
                """
update delivery_assignments set shipper_id=?, status=?, attempt=?, version=?, latitude=?, longitude=?,
    location_at=?, location_sequence=?, tracking_sequence=?, failure_reason_code=?, last_failed_at=?,
    needs_attention=?, gps_source=?, gps_source_at=?, dropped_off_at=?, updated_at=CURRENT_TIMESTAMP
where id=?
""",
                a.getShipperId(),
                a.getStatus().name(),
                a.getAttempt(),
                a.getVersion(),
                a.getLatitude(),
                a.getLongitude(),
                ts(a.getLocationAt()),
                a.getLocationSequence(),
                a.getTrackingSequence(),
                a.getFailureReasonCode(),
                ts(a.getLastFailedAt()),
                a.isNeedsAttention(),
                a.getGpsSource(),
                ts(a.getGpsSourceAt()),
                ts(a.getDroppedOffAt()),
                a.getId());
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public List<Assignment> assigned(String shipper, int page, int size) {
        return jdbc.query(
                "select * from delivery_assignments where shipper_id = ? order by created_at desc,"
                        + " id limit ? offset ?",
                this::map,
                shipper,
                size,
                (long) page * size);
    }

    private static final String ACTIVE_STATUSES =
            "('ASSIGNED','PICKED_UP','IN_TRANSIT','FAILED','RETURNING')";

    /**
     * Chuyen cua shipper theo pham vi: active = moi chuyen con viec (khong phan trang, toi da 200)
     * de khong chuyen dang chay nao roi sang trang sau; finished = da xong, phan trang.
     */
    public List<Assignment> assigned(String shipper, String scope, int page, int size) {
        if ("active".equals(scope))
            return jdbc.query(
                    "select * from delivery_assignments where shipper_id = ? and status in "
                            + ACTIVE_STATUSES
                            + " order by created_at, id limit 200",
                    this::map,
                    shipper);
        return jdbc.query(
                "select * from delivery_assignments where shipper_id = ? and status not in "
                        + ACTIVE_STATUSES
                        + " order by updated_at desc, id limit ? offset ?",
                this::map,
                shipper,
                size,
                (long) page * size);
    }

    /** Lan cuoi chuyen roi vao trang thai nay (moc thoi gian hien tren card). */
    public Instant lastTransition(String assignment, String status) {
        var rows =
                jdbc.queryForList(
                        "select max(occurred_at) from delivery_status_history where"
                                + " assignment_id=? and status=?",
                        Timestamp.class,
                        assignment,
                        status);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant();
    }

    public List<Map<String, Object>> handoffs(String assignment) {
        return lowercaseKeys(
                jdbc.queryForList(
                        "select shop_id, seller_id, pickup_snapshot, created_at, picked_up_at,"
                                + " returned_at, return_received_at from delivery_handoffs where"
                                + " assignment_id=? order by shop_id",
                        assignment));
    }

    public void handoff(String assignment, String shop, String seller, String snapshot) {
        if (jdbc.queryForObject(
                        "select count(*) from delivery_handoffs where assignment_id=? and"
                                + " shop_id=?",
                        Integer.class,
                        assignment,
                        shop)
                == 0) {
            jdbc.update(
                    "insert into delivery_handoffs (assignment_id, shop_id, seller_id,"
                        + " pickup_snapshot, created_at) values (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    assignment,
                    shop,
                    seller,
                    snapshot);
        }
    }

    /** Danh dau mot moc theo tung shop (picked_up_at / returned_at / return_received_at). */
    public boolean markHandoff(String assignment, String shop, String column, Instant at) {
        if (!java.util.Set.of("picked_up_at", "returned_at", "return_received_at").contains(column))
            throw new IllegalArgumentException(column);
        return jdbc.update(
                        "update delivery_handoffs set "
                                + column
                                + "=? where assignment_id=? and shop_id=? and "
                                + column
                                + " is null",
                        Timestamp.from(at),
                        assignment,
                        shop)
                == 1;
    }

    public boolean allHandoffs(String assignment, String column) {
        if (!java.util.Set.of("picked_up_at", "returned_at", "return_received_at").contains(column))
            throw new IllegalArgumentException(column);
        return jdbc.queryForObject(
                        "select count(*) from delivery_handoffs where assignment_id=? and "
                                + column
                                + " is null",
                        Integer.class,
                        assignment)
                == 0;
    }

    public Map<String, Object> shipper(String id) {
        return lowercaseKeys(jdbc.queryForList("select * from shippers where id=?", id)).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SHIPPER_NOT_FOUND", "Không có shipper"));
    }

    public List<String> candidates(String province, String district) {
        // Khong xac dinh duoc khu (phuong la): khong tu gan bua, de admin phan cong tay.
        if (district == null || district.isBlank()) return List.of();
        return jdbc.queryForList(
                """
                select s.id from shippers s left join delivery_assignments d on d.shipper_id=s.id
                    and d.status not in ('DELIVERED','RETURNED')
                where s.active=true and s.on_shift=true and s.province=?
                    and (s.district=? or s.district is null or s.district='')
                group by s.id, s.district order by case when s.district=? then 0 else 1 end, count(d.id), s.id
                """,
                String.class,
                province,
                district,
                district);
    }

    public void history(Assignment a, String actor, String reason) {
        history(a, actor, reason, null, false, null, null);
    }

    public void history(
            Assignment a,
            String actor,
            String reason,
            String previousShipper,
            boolean areaOverride) {
        history(a, actor, reason, previousShipper, areaOverride, null, null);
    }

    /**
     * @param internalNote ghi chu noi bo (shipper / admin), nguoi mua khong thay
     * @param reasonCode ma ly do chuan (giao that bai)
     * @param publicReason nhan cong khai cua ly do, nguoi mua thay
     */
    public void history(
            Assignment a,
            String actor,
            String internalNote,
            String previousShipper,
            boolean areaOverride,
            String reasonCode,
            String publicReason) {
        jdbc.update(
                """
insert into delivery_status_history
    (id, assignment_id, status, actor_id, reason, attempt, occurred_at, shipper_id, previous_shipper_id,
     area_override, reason_code, public_reason)
values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?)
""",
                UUID.randomUUID().toString(),
                a.getId(),
                a.getStatus().name(),
                actor,
                internalNote,
                a.getAttempt(),
                a.getShipperId(),
                previousShipper,
                areaOverride,
                reasonCode,
                publicReason);
    }

    public List<Map<String, Object>> history(String id) {
        return lowercaseKeys(
                jdbc.queryForList(
                        "select status, actor_id, reason, attempt, occurred_at, shipper_id,"
                                + " previous_shipper_id, area_override, reason_code, public_reason"
                                + " from delivery_status_history"
                                + " where assignment_id=? order by occurred_at, id",
                        id));
    }

    public List<Map<String, Object>> route(String id, int page, int size) {
        return lowercaseKeys(
                jdbc.queryForList(
                        "select sequence, latitude, longitude, recorded_at from route_logs where"
                                + " assignment_id=? order by sequence limit ? offset ?",
                        id,
                        size,
                        (long) page * size));
    }

    public void route(Assignment a) {
        jdbc.update(
                "insert into route_logs (id, assignment_id, sequence, latitude, longitude,"
                        + " recorded_at) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                a.getId(),
                a.getLocationSequence(),
                a.getLatitude(),
                a.getLongitude(),
                Timestamp.from(a.getLocationAt()));
    }

    public void outbox(Assignment a, String type, String body) {
        jdbc.update(
                "insert into delivery_outbox (id, assignment_id, event_type, payload, created_at)"
                        + " values (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),
                a.getId(),
                type,
                body);
    }
}
