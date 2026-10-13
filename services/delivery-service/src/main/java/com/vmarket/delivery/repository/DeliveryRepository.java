package com.vmarket.delivery.repository;

import com.vmarket.delivery.domain.*;
import com.vmarket.delivery.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository @RequiredArgsConstructor
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    public static List<Map<String,Object>> lowercaseKeys(List<Map<String,Object>> rows) {
        return rows.stream().map(row -> {
            Map<String,Object> result = new LinkedHashMap<>();
            row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
            return result;
        }).toList();
    }
    private static Instant instant(ResultSet row, String col) throws SQLException {
        var value = row.getTimestamp(col); return value == null ? null : value.toInstant();
    }
    private Assignment map(ResultSet r, int index) throws SQLException {
        Assignment a = new Assignment();
        a.setId(r.getString("id")); a.setOrderId(r.getString("order_id")); a.setBuyerId(r.getString("buyer_id"));
        a.setShipperId(r.getString("shipper_id")); a.setStatus(DeliveryStatus.valueOf(r.getString("status")));
        a.setAttempt(r.getInt("attempt")); a.setPaymentMethod(r.getString("payment_method")); a.setCodAmount(r.getLong("cod_amount"));
        a.setProvince(r.getString("province")); a.setDistrict(r.getString("district")); a.setOrderSnapshot(r.getString("order_snapshot"));
        a.setVersion(r.getLong("version")); a.setLatitude(r.getObject("latitude", Double.class)); a.setLongitude(r.getObject("longitude", Double.class));
        a.setLocationAt(instant(r, "location_at")); a.setCreatedAt(instant(r, "created_at")); a.setUpdatedAt(instant(r, "updated_at"));
        return a;
    }
    public Optional<Assignment> findOrder(String order, boolean lock) {
        return jdbc.query("select * from delivery_assignments where order_id = ?" + (lock ? " for update" : ""), this::map, order).stream().findFirst();
    }
    public Assignment find(String id, boolean lock) {
        return jdbc.query("select * from delivery_assignments where id = ?" + (lock ? " for update" : ""), this::map, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", "Không có chuyến giao"));
    }
    public void insert(Assignment a) {
        jdbc.update("""
            insert into delivery_assignments(id, order_id, buyer_id, status, attempt, payment_method, cod_amount,
                province, district, order_snapshot, version, created_at, updated_at)
            values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, a.getId(), a.getOrderId(), a.getBuyerId(), a.getStatus().name(), a.getPaymentMethod(), a.getCodAmount(), a.getProvince(), a.getDistrict(), a.getOrderSnapshot());
    }
    public void save(Assignment a) {
        jdbc.update("""
            update delivery_assignments set shipper_id=?, status=?, attempt=?, version=?, latitude=?, longitude=?,
                location_at=?, updated_at=CURRENT_TIMESTAMP where id=?
            """, a.getShipperId(), a.getStatus().name(), a.getAttempt(), a.getVersion(), a.getLatitude(), a.getLongitude(),
                a.getLocationAt() == null ? null : java.sql.Timestamp.from(a.getLocationAt()), a.getId());
    }
    public List<Assignment> assigned(String shipper, int page, int size) {
        return jdbc.query("select * from delivery_assignments where shipper_id = ? order by created_at desc, id limit ? offset ?", this::map, shipper, size, (long)page * size);
    }
    public List<Map<String,Object>> handoffs(String assignment) {
        return lowercaseKeys(jdbc.queryForList("select shop_id, seller_id, pickup_snapshot, created_at from delivery_handoffs where assignment_id=? order by shop_id", assignment));
    }
    public void handoff(String assignment, String shop, String seller, String snapshot) {
        if (jdbc.queryForObject("select count(*) from delivery_handoffs where assignment_id=? and shop_id=?", Integer.class, assignment, shop) == 0) {
            jdbc.update("insert into delivery_handoffs values (?, ?, ?, ?, CURRENT_TIMESTAMP)", assignment, shop, seller, snapshot);
        }
    }
    public Map<String,Object> shipper(String id) {
        return lowercaseKeys(jdbc.queryForList("select * from shippers where id=? for update", id)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("SHIPPER_NOT_FOUND", "Không có shipper"));
    }
    public List<String> candidates(String province, String district) {
        return jdbc.queryForList("""
            select s.id from shippers s left join delivery_assignments d on d.shipper_id=s.id
                and d.status not in ('DELIVERED','RETURNED')
            where s.active=true and s.province=? and s.district=?
            group by s.id order by count(d.id), s.id
            """, String.class, province, district);
    }
    public void history(Assignment a, String actor, String reason) {
        jdbc.update("insert into delivery_status_history values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), a.getId(), a.getStatus().name(), actor, reason, a.getAttempt());
    }
    public List<Map<String,Object>> history(String id) {
        return lowercaseKeys(jdbc.queryForList("select status, actor_id, reason, attempt, occurred_at from delivery_status_history where assignment_id=? order by occurred_at, id", id));
    }
    public List<Map<String,Object>> route(String id, int page, int size) {
        return lowercaseKeys(jdbc.queryForList("select sequence, latitude, longitude, recorded_at from route_logs where assignment_id=? order by sequence limit ? offset ?", id, size, (long)page*size));
    }
    public void route(Assignment a) {
        jdbc.update("insert into route_logs values (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), a.getId(), a.getVersion(), a.getLatitude(), a.getLongitude(), java.sql.Timestamp.from(a.getLocationAt()));
    }
    public void outbox(Assignment a, String type, String body) {
        jdbc.update("insert into delivery_outbox (id, assignment_id, event_type, payload, created_at) values (?, ?, ?, ?, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), a.getId(), type, body);
    }
}
