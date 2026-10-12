package com.vmarket.delivery.service;

import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.domain.*;
import com.vmarket.delivery.dto.DeliveryRequests;
import com.vmarket.delivery.exception.ApiException;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.events.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service @RequiredArgsConstructor
public class DeliveryService {
    private final DeliveryClients clients;
    private final DeliveryRepository repo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;

    public static String area(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static ApiException forbidden() { return new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Không có quyền với chuyến giao này"); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private void event(Assignment a, String type, Object payload) { repo.outbox(a, type, json.writeValueAsString(payload)); }

    public Map<String,Object> createShipper(String actor, String key, DeliveryRequests.Shipper request) {
        String id = clients.createShipper(actor, key, request.account());
        return tx().execute(ignored -> {
            var old = jdbc.queryForList("select * from shippers where provision_key=?", key);
            if (!old.isEmpty()) {
                var s = old.get(0);
                if (!s.get("id").equals(id) || !s.get("name").equals(request.name().trim())
                        || !s.get("phone").equals(request.phone().trim()) || !s.get("province").equals(area(request.province()))
                        || !s.get("district").equals(area(request.district()))) {
                    throw ApiException.conflict("PROVISION_KEY_REUSED", "Khoá tạo shipper đã được dùng với hồ sơ khác");
                }
                return DeliveryRepository.lowercaseKeys(List.of(s)).get(0);
            }
            jdbc.update("insert into shippers values (?, ?, ?, ?, ?, ?, true, CURRENT_TIMESTAMP)", id, key,
                    request.name().trim(), request.phone().trim(), area(request.province()), area(request.district()));
            return repo.shipper(id);
        });
    }

    public List<Map<String,Object>> shippers(String actor, String province, String district, int page, int size) {
        clients.admin(actor);
        return DeliveryRepository.lowercaseKeys(jdbc.queryForList("""
            select s.id, s.name, s.phone, s.province, s.district, s.active,
                count(d.id) as total_orders,
                coalesce(sum(case when d.status='DELIVERED' then 1 else 0 end),0) as delivered_orders,
                coalesce(sum(case when d.status in ('DELIVERED','RETURNED') then 1 else 0 end),0) as completed_orders,
                case when sum(case when d.status in ('DELIVERED','RETURNED') then 1 else 0 end) > 0
                     then 100.0 * sum(case when d.status='DELIVERED' then 1 else 0 end)
                        / sum(case when d.status in ('DELIVERED','RETURNED') then 1 else 0 end)
                     else 0 end as success_rate_percent
            from shippers s left join delivery_assignments d on d.shipper_id=s.id
            where (?='' or s.province=?) and (?='' or s.district=?)
            group by s.id, s.name, s.phone, s.province, s.district, s.active
            order by s.id limit ? offset ?
            """, area(province), area(province), area(district), area(district), size, (long)page*size));
    }

    public List<Map<String,Object>> assignments(String actor, int page, int size) {
        clients.admin(actor);
        return DeliveryRepository.lowercaseKeys(jdbc.queryForList("select id, order_id, shipper_id, status, attempt, province, district, created_at from delivery_assignments order by created_at desc, id limit ? offset ?", size, (long)page*size));
    }
    public void lock(String actor, String shipper, boolean locked) {
        clients.admin(actor);
        // Auth owns login/refresh revocation; local state additionally fences old access tokens.
        tx().executeWithoutResult(ignored -> repo.shipper(shipper));
        clients.lock(actor, shipper, locked);
        tx().executeWithoutResult(ignored -> {
            repo.shipper(shipper);
            jdbc.update("update shippers set active=? where id=?", !locked, shipper);
        });
    }

    public Assignment handoff(String actor, String bearer, String orderId) {
        JsonNode shop = clients.shop(bearer);
        if (!actor.equals(shop.path("ownerId").asString()) || !"ACTIVE".equals(shop.path("status").asString())) throw forbidden();
        JsonNode snapshot = clients.order(orderId, false);
        String shopId = shop.path("id").asString();
        Set<String> shops = shops(snapshot);
        if (!shops.contains(shopId)) throw forbidden();
        var existing = repo.findOrder(orderId, false);
        if (existing.isPresent() && existing.get().getStatus() != DeliveryStatus.WAITING_HANDOFF
                && existing.get().getStatus() != DeliveryStatus.WAITING_ASSIGNMENT) return existing.get();
        // Claim is repeatable; the remote Order lock rejects cancelled/unpaid/unreserved orders.
        snapshot = clients.order(orderId, true);
        if (!snapshot.path("stockReserved").asBoolean() || !Set.of("PENDING", "PROCESSING").contains(snapshot.path("status").asString())) {
            throw ApiException.conflict("ORDER_NOT_READY", "Đơn chưa sẵn sàng giao");
        }
        if (snapshot.path("userId").asString().isBlank()) throw ApiException.unavailable("INVALID_ORDER", "Đơn thiếu người mua");
        final JsonNode claimed = snapshot;
        try {
            tx().executeWithoutResult(ignored -> {
                if (repo.findOrder(orderId, true).isEmpty()) {
                    Assignment a = new Assignment();
                    a.setId(UUID.randomUUID().toString()); a.setOrderId(orderId); a.setBuyerId(claimed.path("userId").asString());
                    a.setStatus(DeliveryStatus.WAITING_HANDOFF); a.setOrderSnapshot(json.writeValueAsString(claimed));
                    a.setPaymentMethod(claimed.path("paymentMethod").asString("COD"));
                    if (!Set.of("COD", "PAYOS").contains(a.getPaymentMethod())) throw ApiException.conflict("PAYMENT_METHOD_UNSUPPORTED", "Sai phương thức thanh toán");
                    a.setCodAmount("COD".equals(a.getPaymentMethod()) ? claimed.path("totalAmount").decimalValue().longValueExact() : 0);
                    a.setProvince(area(claimed.path("province").asString())); a.setDistrict(area(claimed.path("district").asString()));
                    repo.insert(a);
                }
            });
        } catch (DuplicateKeyException concurrentInsert) {
            // A separate transaction won initialization. Start a fresh transaction after rollback.
        }
        return tx().execute(ignored -> {
            Assignment a = repo.findOrder(orderId, true).orElseThrow();
            if (a.getStatus() != DeliveryStatus.WAITING_HANDOFF && a.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT) return a;
            repo.handoff(a.getId(), shopId, actor, json.writeValueAsString(shop));
            if (repo.handoffs(a.getId()).size() == shops(json.readTree(a.getOrderSnapshot())).size()) {
                if (a.getStatus() == DeliveryStatus.WAITING_HANDOFF) {
                    a.setStatus(DeliveryStatus.WAITING_ASSIGNMENT); a.setVersion(a.getVersion()+1);
                    repo.history(a, actor, null); repo.save(a);
                }
                autoAssign(a, actor);
            }
            return repo.find(a.getId(), false);
        });
    }
    private Set<String> shops(JsonNode snapshot) {
        Set<String> result = new HashSet<>();
        for (var item : snapshot.path("items")) result.add(item.path("shopId").asString());
        if (result.isEmpty() || result.contains("")) throw ApiException.conflict("INVALID_ORDER", "Đơn không có shop hợp lệ");
        return result;
    }
    private void autoAssign(Assignment a, String actor) {
        for (String id : repo.candidates(a.getProvince(), a.getDistrict())) {
            var shipper = repo.shipper(id);
            if (Boolean.TRUE.equals(shipper.get("active")) && clients.activeShipper(id)) {
                assign(a, id, actor); return;
            }
        }
    }
    private void assign(Assignment a, String shipper, String actor) {
        a.setShipperId(shipper); a.setStatus(DeliveryStatus.ASSIGNED); a.setVersion(a.getVersion()+1);
        repo.save(a); repo.history(a, actor, null);
        event(a, EventType.DELIVERY_ASSIGNED, new DeliveryAssigned(a.getId(), a.getOrderId(), a.getBuyerId(), shipper));
        tracking(a);
    }
    public Assignment assign(String actor, String id, String shipper, long expectedVersion) {
        clients.admin(actor);
        return tx().execute(ignored -> {
            Assignment a = repo.find(id, true);
            if (a.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT && a.getStatus() != DeliveryStatus.ASSIGNED) {
                throw ApiException.conflict("CANNOT_REASSIGN", "Chỉ phân công trước khi shipper lấy hàng");
            }
            var s = repo.shipper(shipper);
            if (!Boolean.TRUE.equals(s.get("active")) || !clients.activeShipper(shipper)) throw ApiException.conflict("SHIPPER_INACTIVE", "Shipper chưa kích hoạt hoặc bị khoá");
            if (!s.get("province").equals(a.getProvince()) || !s.get("district").equals(a.getDistrict())) {
                throw ApiException.conflict("AREA_MISMATCH", "Shipper không phụ trách khu vực này");
            }
            if (shipper.equals(a.getShipperId())) return a;
            fence(a, expectedVersion);
            assign(a, shipper, actor);
            return repo.find(id, false);
        });
    }
    private void requireShipper(Assignment a, String actor) {
        if (!actor.equals(a.getShipperId())) throw forbidden();
        if (!Boolean.TRUE.equals(repo.shipper(actor).get("active")) || !clients.activeShipper(actor)) {
            throw new ApiException("SHIPPER_INACTIVE", HttpStatus.FORBIDDEN, "Shipper đã bị khoá hoặc chưa kích hoạt");
        }
    }
    public Assignment status(String actor, String id, DeliveryRequests.Status request) {
        return tx().execute(ignored -> {
            Assignment a = repo.find(id, true); requireShipper(a, actor);
            DeliveryStatus next = request.status();
            if (next == DeliveryStatus.DELIVERED) {
                if (request.codCollected() == null || request.codCollected() != a.getCodAmount()) {
                    throw ApiException.badRequest("COD_AMOUNT_MISMATCH", "Phải xác nhận đúng số COD (0 cho đơn trả trước)");
                }
            } else if (request.codCollected() != null) throw ApiException.badRequest("COD_NOT_APPLICABLE", "Chỉ thu COD khi giao thành công");
            if (next == DeliveryStatus.FAILED && (request.reason() == null || request.reason().isBlank())) {
                throw ApiException.badRequest("FAILURE_REASON_REQUIRED", "Cần lý do giao thất bại");
            }
            if (a.getStatus() == next && (a.getVersion() == request.expectedVersion()
                    || a.getVersion() == request.expectedVersion()+1)) return a;
            if (a.getStatus() == DeliveryStatus.RETURNING && a.getAttempt() == 3 && next == DeliveryStatus.FAILED
                    && a.getVersion() == request.expectedVersion()+1) return a;
            fence(a, request.expectedVersion());
            boolean valid = switch (a.getStatus()) {
                case ASSIGNED -> next == DeliveryStatus.PICKED_UP;
                case PICKED_UP -> next == DeliveryStatus.IN_TRANSIT;
                case IN_TRANSIT -> next == DeliveryStatus.DELIVERED || next == DeliveryStatus.FAILED;
                case RETURNING -> next == DeliveryStatus.RETURNED;
                default -> false;
            };
            if (!valid) throw ApiException.conflict("INVALID_TRANSITION", "Không thể chuyển trạng thái giao hàng");
            if (next == DeliveryStatus.FAILED && a.getAttempt() == 3) {
                // Keep the third failure in audit before moving to the return journey.
                a.setStatus(DeliveryStatus.FAILED); repo.history(a, actor, request.reason());
                next = DeliveryStatus.RETURNING;
            }
            a.setStatus(next); a.setVersion(a.getVersion()+1);
            repo.save(a); repo.history(a, actor, request.reason());
            event(a, EventType.DELIVERY_STATUS_CHANGED, new DeliveryStatusChanged(a.getOrderId(), next.name(), a.getVersion()));
            if (next == DeliveryStatus.DELIVERED && "COD".equals(a.getPaymentMethod())) {
                event(a, EventType.COD_COLLECTED, new CodCollected(a.getOrderId(), a.getBuyerId(), a.getCodAmount(), a.getId()));
            }
            tracking(a);
            return repo.find(id, false);
        });
    }
    public Assignment retry(String actor, String id, boolean admin, long expectedVersion) {
        if (admin) clients.admin(actor);
        return tx().execute(ignored -> {
            Assignment a = repo.find(id, true);
            if (!admin) requireShipper(a, actor);
            else {
                if (a.getShipperId() == null) throw ApiException.conflict("NOT_ASSIGNED", "Chưa phân công");
                requireShipper(a, a.getShipperId());
            }
            if (a.getStatus() == DeliveryStatus.IN_TRANSIT && a.getAttempt() > 1 && a.getVersion() == expectedVersion+1) return a;
            fence(a, expectedVersion);
            if (a.getStatus() != DeliveryStatus.FAILED || a.getAttempt() >= 3) throw ApiException.conflict("RETRY_LIMIT", "Chỉ giao lại tối đa hai lần");
            a.setAttempt(a.getAttempt()+1); a.setStatus(DeliveryStatus.IN_TRANSIT); a.setVersion(a.getVersion()+1);
            repo.save(a); repo.history(a, actor, "Giao lại");
            event(a, EventType.DELIVERY_STATUS_CHANGED, new DeliveryStatusChanged(a.getOrderId(), "IN_TRANSIT", a.getVersion()));
            tracking(a); return repo.find(id, false);
        });
    }
    public Assignment location(String actor, String id, DeliveryRequests.Location point) {
        if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude())) throw ApiException.badRequest("INVALID_LOCATION", "GPS không hợp lệ");
        return tx().execute(ignored -> {
            Assignment a = repo.find(id, true); requireShipper(a, actor);
            if (!a.getStatus().tracking()) throw ApiException.conflict("TRACKING_STOPPED", "Không nhận GPS khi chuyến không hoạt động");
            Instant now = Instant.now();
            if (a.getLocationAt() != null && a.getLocationAt().plusSeconds(10).isAfter(now)) {
                throw new ApiException("PING_TOO_FREQUENT", HttpStatus.TOO_MANY_REQUESTS, "Gửi GPS cách nhau ít nhất 10 giây");
            }
            a.setLatitude(point.latitude()); a.setLongitude(point.longitude()); a.setLocationAt(now); a.setVersion(a.getVersion()+1);
            repo.save(a); repo.route(a); tracking(a); return repo.find(id, false);
        });
    }
    private static void fence(Assignment a, long expected) {
        if (a.getVersion() != expected) throw ApiException.conflict("STALE_DELIVERY", "Chuyến đã thay đổi, hãy tải lại trước khi thao tác");
    }
    private void tracking(Assignment a) {
        event(a, "TrackingUpdate", Map.of("assignmentId", a.getId(), "version", a.getVersion(), "status", a.getStatus().name(),
                "active", a.getStatus().tracking(), "location", a.getLatitude() == null ? Map.of() :
                    Map.of("latitude", a.getLatitude(), "longitude", a.getLongitude(), "recordedAt", a.getLocationAt().toString())));
    }
    public void scope(Assignment a, String actor, boolean admin) {
        if (admin) { clients.admin(actor); return; }
        if (actor.equals(a.getBuyerId())) return;
        if (actor.equals(a.getShipperId())) {
            tx().executeWithoutResult(ignored -> requireShipper(repo.find(a.getId(), true), actor)); return;
        }
        if (repo.handoffs(a.getId()).stream().anyMatch(h -> actor.equals(h.get("seller_id")))) return;
        throw forbidden();
    }
    public Assignment order(String order, String actor, boolean admin) {
        Assignment a = repo.findOrder(order, false).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", "Đơn chưa có chuyến giao"));
        scope(a, actor, admin); return a;
    }
    public Map<String,Object> detail(String order, String actor, boolean admin) {
        Assignment a = order(order, actor, admin);
        return Map.of("assignment", a, "order", json.readTree(a.getOrderSnapshot()), "pickups", repo.handoffs(a.getId()), "history", repo.history(a.getId()));
    }
    public List<Assignment> assigned(String actor, int page, int size) {
        tx().executeWithoutResult(ignored -> {
            if (!Boolean.TRUE.equals(repo.shipper(actor).get("active")) || !clients.activeShipper(actor)) throw forbidden();
        });
        return repo.assigned(actor, page, size);
    }
    public static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
