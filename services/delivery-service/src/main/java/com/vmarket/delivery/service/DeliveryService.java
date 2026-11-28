package com.vmarket.delivery.service;

import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.domain.*;
import com.vmarket.delivery.dto.DeliveryRequests;
import com.vmarket.delivery.exception.ApiException;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.tracking.TrackingRevoked;
import com.vmarket.delivery.tracking.TrackingUpdate;
import com.vmarket.events.*;

import db.migration.V8__sanitize_pickups_and_zones;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Nghiep vu giao hang (PBL6-20) theo quy tac da chot o review PR 68: lay / tra hang theo tung
 * shop, ly do that bai chuan, giao lai sau toi thieu 2 gio, seller xac nhan nhan lai hang, chi doi
 * shipper truoc khi lay hang, mot nguon GPS moi chuyen va du lieu tra ve loc theo vai tro.
 */
@Service
@RequiredArgsConstructor
public class DeliveryService {
    /** Nguon GPS khac duoc coi la "dang hoat dong" trong khoang nay ke tu diem cuoi. */
    static final Duration GPS_SOURCE_WINDOW = Duration.ofMinutes(2);

    private final DeliveryClients clients;
    private final DeliveryRepository repo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final ApplicationEventPublisher applicationEvents;
    private final Clock clock;
    private final com.vmarket.delivery.config.DeliveryProperties properties;

    private final DeliveryRules rules = DeliveryRules.get();

    public static String area(String value) {
        return AreaNames.normalize(value);
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static boolean isAreaMismatch(
            String shipperProvince,
            String shipperDistrict,
            String orderProvince,
            String orderDistrict) {
        if (!Objects.equals(shipperProvince, orderProvince)) {
            return true;
        }
        if (shipperDistrict == null
                || shipperDistrict.isBlank()
                || orderDistrict == null
                || orderDistrict.isBlank()) {
            return false;
        }
        return !shipperDistrict.equals(orderDistrict);
    }

    private static ApiException forbidden() {
        return new ApiException(
                "FORBIDDEN", HttpStatus.FORBIDDEN, "Không có quyền với chuyến giao này");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private void event(Assignment a, String type, Object payload) {
        repo.outbox(a, type, json.writeValueAsString(payload));
    }

    // ---------------------------------------------------------------- shipper profiles (admin)

    public Map<String, Object> createShipper(
            String actor, String key, DeliveryRequests.Shipper request) {
        String id = clients.createShipper(actor, key, request.account());
        String province = area(request.province());
        String district = blankToNull(area(request.district()));
        return tx().execute(
                        ignored -> {
                            var old =
                                    jdbc.queryForList(
                                            "select * from shippers where provision_key=?", key);
                            if (!old.isEmpty()) {
                                var s = old.get(0);
                                if (!s.get("id").equals(id)
                                        || !s.get("name").equals(request.name().trim())
                                        || !s.get("phone").equals(request.phone().trim())
                                        || !Objects.equals(s.get("province"), province)
                                        || !Objects.equals(s.get("district"), district)) {
                                    throw ApiException.conflict(
                                            "PROVISION_KEY_REUSED",
                                            "Khoá tạo shipper đã được dùng với hồ sơ khác");
                                }
                                return DeliveryRepository.lowercaseKeys(List.of(s)).get(0);
                            }
                            jdbc.update(
                                    "insert into shippers (id, provision_key, name, phone,"
                                        + " province, district, active, created_at) values (?, ?,"
                                        + " ?, ?, ?, ?, true, CURRENT_TIMESTAMP)",
                                    id,
                                    key,
                                    request.name().trim(),
                                    request.phone().trim(),
                                    province,
                                    district);
                            return repo.shipper(id);
                        });
    }

    public List<Map<String, Object>> shippers(
            String actor, String province, String district, int page, int size) {
        return shippers(actor, province, district, "", page, size);
    }

    public List<Map<String, Object>> shippers(
            String actor, String province, String district, String shift, int page, int size) {
        String shiftFilter = shift == null ? "" : shift;
        clients.admin(actor);
        // Ti le thanh cong = DELIVERED / (DELIVERED + RETURNED do loi shipper): don hoan vi khach
        // tu choi / sai dia chi khong tinh vao shipper (review PR 68, muc 4.11).
        List<Map<String, Object>> rows =
                DeliveryRepository.lowercaseKeys(
                        jdbc.queryForList(
                                """
select s.id, s.name, s.phone, s.province, s.district, s.active, s.on_shift, s.shift_changed_at,
    count(d.id) as total_orders,
    coalesce(sum(case when d.status in ('ASSIGNED','PICKED_UP','IN_TRANSIT','FAILED','RETURNING')
        then 1 else 0 end),0) as active_orders,
    coalesce(sum(case when d.status='DELIVERED' then 1 else 0 end),0) as delivered_orders,
    coalesce(sum(case when d.status='RETURNED' and d.failure_reason_code in ('SHIPPER_ISSUE','OTHER')
        then 1 else 0 end),0) as shipper_fault_returns,
    case when sum(case when d.status='DELIVERED' or (d.status='RETURNED'
              and d.failure_reason_code in ('SHIPPER_ISSUE','OTHER')) then 1 else 0 end) > 0
         then 100.0 * sum(case when d.status='DELIVERED' then 1 else 0 end)
            / sum(case when d.status='DELIVERED' or (d.status='RETURNED'
              and d.failure_reason_code in ('SHIPPER_ISSUE','OTHER')) then 1 else 0 end)
         else 0 end as success_rate_percent
from shippers s left join delivery_assignments d on d.shipper_id=s.id
where (?='' or s.province=?) and (?='' or s.district=?)
    and (?='' or (?='on' and s.on_shift=true) or (?='off' and s.on_shift=false))
group by s.id, s.name, s.phone, s.province, s.district, s.active, s.on_shift, s.shift_changed_at
order by s.id limit ? offset ?
""",
                                area(province),
                                area(province),
                                area(district),
                                area(district),
                                shiftFilter,
                                shiftFilter,
                                shiftFilter,
                                size,
                                (long) page * size));
        // Mot request toi auth-service cho ca trang (khong goi tung dong).
        Map<String, String> remote;
        try {
            remote = clients.shipperStatuses(rows.stream().map(r -> (String) r.get("id")).toList());
        } catch (RuntimeException ex) {
            remote = Map.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("district_name", rules.zoneName((String) row.get("district")));
            copy.put("account_status", accountStatus(row, remote.get((String) row.get("id"))));
            result.add(copy);
        }
        return result;
    }

    /** LOCKED: khoa o delivery hoac auth; PENDING_ACTIVATION: chua xac thuc email; UNKNOWN: auth loi. */
    static String accountStatus(Map<String, Object> row, String remote) {
        if (!Boolean.TRUE.equals(row.get("active"))) return "LOCKED";
        if (remote == null) return "UNKNOWN";
        return switch (remote) {
            case "ACTIVE" -> "ACTIVE";
            case "PENDING_ACTIVATION" -> "PENDING_ACTIVATION";
            case "SUSPENDED" -> "LOCKED";
            default -> "UNKNOWN";
        };
    }

    public void resendActivation(String actor, String shipper) {
        clients.admin(actor);
        repo.shipper(shipper);
        clients.resendActivation(actor, shipper);
    }

    public List<Map<String, Object>> assignments(
            String actor, String status, int page, int size) {
        clients.admin(actor);
        String filter = status == null ? "" : status;
        // Join ten shipper de bang dieu phoi khong phai hien id; tra version de admin phan
        // cong ma khong can goi them chi tiet tung don.
        List<Map<String, Object>> rows =
                DeliveryRepository.lowercaseKeys(
                        jdbc.queryForList(
                                "select d.id, d.order_id, d.shipper_id, s.name as shipper_name,"
                                    + " s.phone as shipper_phone, d.status, d.attempt, d.version,"
                                    + " d.province, d.district, d.ward, d.needs_attention,"
                                    + " d.created_at from delivery_assignments d left join"
                                    + " shippers s on s.id = d.shipper_id where (? = '' or"
                                    + " d.status = ?) order by d.needs_attention desc,"
                                    + " d.created_at desc, d.id limit ? offset ?",
                                filter,
                                filter,
                                size,
                                (long) page * size));
        return rows.stream()
                .map(
                        row -> {
                            Map<String, Object> copy = new LinkedHashMap<>(row);
                            copy.put("district_name", rules.zoneName((String) row.get("district")));
                            return (Map<String, Object>) copy;
                        })
                .toList();
    }

    /**
     * Khoa shipper (review PR 68, muc 4.6): chuyen chua lay hang quay ve cho phan cong va chay lai
     * tu dong phan cong; chuyen dang giu hang gan co "Can xu ly" cho admin. Mo khoa chi bat lai
     * tai khoan.
     */
    public void lock(String actor, String shipper, boolean locked) {
        clients.admin(actor);
        // Auth owns login/refresh revocation; local state additionally fences old access tokens.
        repo.shipper(shipper);
        clients.lock(actor, shipper, locked);
        List<String> released = new ArrayList<>();
        tx().executeWithoutResult(
                        ignored -> {
                            jdbc.update(
                                    "update shippers set active=? where id=?", !locked, shipper);
                            if (!locked) return;
                            // Khoa shipper thi tu ket thuc ca (review PR 68 lan 3, phan 2).
                            jdbc.update(
                                    "update shippers set on_shift=false, shift_changed_at=? where"
                                            + " id=? and on_shift=true",
                                    Timestamp.from(clock.instant()),
                                    shipper);
                            for (String id :
                                    jdbc.queryForList(
                                            "select id from delivery_assignments where"
                                                    + " shipper_id=?",
                                            String.class,
                                            shipper)) {
                                Assignment a = repo.find(id, true);
                                revoke(a, shipper);
                                if (a.getStatus() == DeliveryStatus.ASSIGNED) {
                                    releaseToDispatch(a, actor, "Shipper bị khoá", shipper);
                                    released.add(id);
                                } else if (a.getStatus().pickedUp()) {
                                    a.setNeedsAttention(true);
                                    repo.save(a);
                                }
                            }
                        });
        for (String id : released) autoAssign(repo.find(id, false), actor);
    }

    /** Chuyen chua lay hang quay ve "Cho phan cong" (khoa shipper / shipper ket thuc ca). */
    private void releaseToDispatch(Assignment a, String actor, String note, String shipper) {
        a.setShipperId(null);
        a.setStatus(DeliveryStatus.WAITING_ASSIGNMENT);
        a.setVersion(a.getVersion() + 1);
        a.setTrackingSequence(a.getTrackingSequence() + 1);
        repo.save(a);
        repo.history(a, actor, note, shipper, false);
        jdbc.update(
                "update delivery_assignments set assignment_failure_notified=false,"
                        + " next_assignment_at=CURRENT_TIMESTAMP where id=?",
                a.getId());
        tracking(a);
    }

    // ---------------------------------------------------------------- shift

    private static final String PICKED_UP_SQL = "('PICKED_UP','IN_TRANSIT','FAILED','RETURNING')";

    public Map<String, Object> shift(String actor) {
        requireLocalShipper(actor);
        return shiftView(actor);
    }

    private Map<String, Object> shiftView(String actor) {
        Map<String, Object> s = repo.shipper(actor);
        Map<String, Object> counts =
                DeliveryRepository.lowercaseKeys(
                                jdbc.queryForList(
                                        "select coalesce(sum(case when status='ASSIGNED' then 1"
                                                + " else 0 end),0) as unpicked, coalesce(sum(case"
                                                + " when status in "
                                                + PICKED_UP_SQL
                                                + " then 1 else 0 end),0) as picked from"
                                                + " delivery_assignments where shipper_id=?",
                                        actor))
                        .get(0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("onShift", Boolean.TRUE.equals(s.get("on_shift")));
        Object changed = s.get("shift_changed_at");
        view.put("shiftChangedAt", changed instanceof Timestamp ts ? ts.toInstant() : changed);
        view.put("unpickedTrips", ((Number) counts.get("unpicked")).intValue());
        view.put("activeTrips", ((Number) counts.get("picked")).intValue());
        return view;
    }

    /** Bat dau ca: duoc tu dong phan cong; chay lai phan cong cho cac chuyen dang cho trong khu. */
    public Map<String, Object> startShift(String actor) {
        requireActiveShipper(actor);
        jdbc.update(
                "update shippers set on_shift=true, shift_changed_at=? where id=? and"
                        + " on_shift=false",
                Timestamp.from(clock.instant()),
                actor);
        Map<String, Object> s = repo.shipper(actor);
        String district = s.get("district") == null ? "" : (String) s.get("district");
        for (String id :
                jdbc.queryForList(
                        "select id from delivery_assignments where status='WAITING_ASSIGNMENT'"
                                + " and province=? and district is not null and (?='' or"
                                + " district=?) order by created_at, id limit 50",
                        String.class,
                        s.get("province"),
                        district,
                        district)) {
            autoAssign(repo.find(id, false), "system:shift-start");
        }
        return shiftView(actor);
    }

    /**
     * Ket thuc ca: con chuyen da lay hang thi khong cho (SHIFT_HAS_ACTIVE_TRIPS); con chuyen chua
     * lay hang thi can xac nhan, cac chuyen do quay ve cho phan cong cho nguoi khac.
     */
    public Map<String, Object> endShift(String actor, boolean releaseUnpicked) {
        requireLocalShipper(actor);
        List<String> released = new ArrayList<>();
        tx().executeWithoutResult(
                        ignored -> {
                            jdbc.queryForList(
                                    "select id from shippers where id=? for update",
                                    String.class,
                                    actor);
                            Integer picked =
                                    jdbc.queryForObject(
                                            "select count(*) from delivery_assignments where"
                                                    + " shipper_id=? and status in "
                                                    + PICKED_UP_SQL,
                                            Integer.class,
                                            actor);
                            if (picked != null && picked > 0)
                                throw ApiException.conflict(
                                        "SHIFT_HAS_ACTIVE_TRIPS",
                                        "Còn "
                                                + picked
                                                + " chuyến đã lấy hàng; hãy giao xong hoặc trả"
                                                + " hàng trước khi kết thúc ca");
                            List<String> unpicked =
                                    jdbc.queryForList(
                                            "select id from delivery_assignments where"
                                                    + " shipper_id=? and status='ASSIGNED'",
                                            String.class,
                                            actor);
                            if (!unpicked.isEmpty() && !releaseUnpicked)
                                throw ApiException.conflict(
                                        "SHIFT_END_CONFIRM_REQUIRED",
                                        "Còn "
                                                + unpicked.size()
                                                + " chuyến chưa lấy hàng sẽ chuyển cho shipper"
                                                + " khác");
                            for (String id : unpicked) {
                                Assignment a = repo.find(id, true);
                                if (a.getStatus() != DeliveryStatus.ASSIGNED) continue;
                                revoke(a, actor);
                                releaseToDispatch(a, actor, "Shipper kết thúc ca", actor);
                                released.add(id);
                            }
                            jdbc.update(
                                    "update shippers set on_shift=false, shift_changed_at=?"
                                            + " where id=? and on_shift=true",
                                    Timestamp.from(clock.instant()),
                                    actor);
                        });
        for (String id : released) autoAssign(repo.find(id, false), "system:shift-end");
        return shiftView(actor);
    }

    // ---------------------------------------------------------------- handoff & assignment

    static final String STOCK_PENDING_MESSAGE = "Đơn đang giữ hàng, thử lại sau vài giây";

    /**
     * Giu ton kho chay bat dong bo sau khi seller xac nhan: cho ngan roi moi claim, thay vi tra 409
     * mo ho (review PR 68 lan 3, muc 1.3). Het luot cho van chua giu duoc thi bao ORDER_NOT_READY.
     */
    private JsonNode claimWhenStockReserved(String orderId, JsonNode snapshot) {
        int attempts = Math.max(1, properties.getStockWaitAttempts());
        for (int attempt = 1; ; attempt++) {
            if (snapshot.path("stockReserved").asBoolean()) {
                try {
                    JsonNode claimed = clients.order(orderId, true);
                    if (claimed.path("stockReserved").asBoolean()) return claimed;
                } catch (ApiException ex) {
                    if (!"STOCK_NOT_RESERVED".equals(ex.getCode())) throw ex;
                }
            }
            if (attempt >= attempts
                    || !Set.of("PENDING", "PROCESSING").contains(snapshot.path("status").asString()))
                throw ApiException.conflict("ORDER_NOT_READY", STOCK_PENDING_MESSAGE);
            try {
                Thread.sleep(properties.getStockWaitMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("ORDER_NOT_READY", STOCK_PENDING_MESSAGE);
            }
            snapshot = clients.order(orderId, false);
        }
    }

    public Assignment handoff(String actor, String bearer, String orderId) {
        JsonNode shop = clients.shop(bearer);
        if (!actor.equals(shop.path("ownerId").asString())
                || !"ACTIVE".equals(shop.path("status").asString())) throw forbidden();
        JsonNode snapshot = clients.order(orderId, false);
        String shopId = shop.path("id").asString();
        Set<String> shops = shops(snapshot);
        if (!shops.contains(shopId)) throw forbidden();
        var existing = repo.findOrder(orderId, false);
        if (existing.isPresent()
                && existing.get().getStatus() != DeliveryStatus.WAITING_HANDOFF
                && existing.get().getStatus() != DeliveryStatus.WAITING_ASSIGNMENT)
            return existing.get();
        // Claim is repeatable; the remote Order lock rejects cancelled/unpaid/unreserved orders.
        snapshot = claimWhenStockReserved(orderId, snapshot);
        if (!Set.of("PENDING", "PROCESSING").contains(snapshot.path("status").asString())) {
            throw ApiException.conflict("ORDER_NOT_READY", "Đơn chưa sẵn sàng giao");
        }
        if (snapshot.path("userId").asString().isBlank())
            throw ApiException.unavailable("INVALID_ORDER", "Đơn thiếu người mua");
        final JsonNode claimed = snapshot;
        try {
            tx().executeWithoutResult(
                            ignored -> {
                                if (repo.findOrder(orderId, true).isEmpty()) {
                                    Assignment a = new Assignment();
                                    a.setId(UUID.randomUUID().toString());
                                    a.setOrderId(orderId);
                                    a.setBuyerId(claimed.path("userId").asString());
                                    a.setStatus(DeliveryStatus.WAITING_HANDOFF);
                                    a.setOrderSnapshot(json.writeValueAsString(claimed));
                                    a.setPaymentMethod(
                                            claimed.path("paymentMethod").asString("COD"));
                                    if (!Set.of("COD", "VNPAY", "PAYOS")
                                            .contains(a.getPaymentMethod()))
                                        throw ApiException.conflict(
                                                "PAYMENT_METHOD_UNSUPPORTED",
                                                "Sai phương thức thanh toán");
                                    // Mien phi ship: COD = tong tien hang (review PR 68, 4.11).
                                    a.setCodAmount(
                                            "COD".equals(a.getPaymentMethod())
                                                    ? claimed.path("totalAmount")
                                                            .decimalValue()
                                                            .longValueExact()
                                                    : 0);
                                    a.setProvince(area(claimed.path("province").asString()));
                                    String ward = blankToNull(claimed.path("ward").asString(""));
                                    a.setWard(ward);
                                    a.setDistrict(zoneOf(claimed, ward));
                                    repo.insert(a);
                                }
                            });
        } catch (DuplicateKeyException concurrentInsert) {
            // A separate transaction won initialization. Start a fresh transaction after rollback.
        }
        // Chi luu thong tin can de lay hang: ten, dia chi, SDT - khong luu ngan hang/email/owner.
        String pickup =
                json.writeValueAsString(
                        V8__sanitize_pickups_and_zones.sanitizePickup(json, shop));
        Assignment handedOff =
                tx().execute(
                                ignored -> {
                                    Assignment a = repo.findOrder(orderId, true).orElseThrow();
                                    if (a.getStatus() != DeliveryStatus.WAITING_HANDOFF
                                            && a.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT)
                                        return a;
                                    repo.handoff(a.getId(), shopId, actor, pickup);
                                    if (repo.handoffs(a.getId()).size()
                                            == shops(json.readTree(a.getOrderSnapshot())).size()) {
                                        if (a.getStatus() == DeliveryStatus.WAITING_HANDOFF) {
                                            a.setStatus(DeliveryStatus.WAITING_ASSIGNMENT);
                                            a.setVersion(a.getVersion() + 1);
                                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                                            repo.history(a, actor, null);
                                            repo.save(a);
                                        }
                                    }
                                    return repo.find(a.getId(), false);
                                });
        // Handoff is committed before any optional Auth calls for automatic assignment.
        // An unavailable or missing candidate must not undo a seller's accepted handoff.
        autoAssign(handedOff, actor);
        return repo.find(handedOff.getId(), false);
    }

    /**
     * Khu giao hang cua don: quan cu neu don con co, nguoc lai tra phuong/xa trong bang 7 khu.
     * Phuong la -> null: chuyen o lai "Cho phan cong" cho admin (review PR 68, muc 3).
     */
    private String zoneOf(JsonNode order, String ward) {
        String district =
                order.hasNonNull("district")
                        ? blankToNull(area(order.path("district").asString()))
                        : null;
        if (district != null) return district;
        return rules.zoneOfWard(ward).orElse(null);
    }

    private Set<String> shops(JsonNode snapshot) {
        Set<String> result = new HashSet<>();
        for (var item : snapshot.path("items")) result.add(item.path("shopId").asString());
        if (result.isEmpty() || result.contains(""))
            throw ApiException.conflict("INVALID_ORDER", "Đơn không có shop hợp lệ");
        return result;
    }

    private void autoAssign(Assignment a, String actor) {
        autoAssign(a, actor, null);
    }

    public void retryAssignment(String id, String claim) {
        autoAssign(repo.find(id, false), "system:assignment-retry", claim);
    }

    private void autoAssign(Assignment a, String actor, String claim) {
        if (a.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT) return;
        for (String id : repo.candidates(a.getProvince(), a.getDistrict())) {
            try {
                if (!clients.activeShipper(id)) continue;
            } catch (ApiException unavailable) {
                continue;
            }
            boolean assigned =
                    Boolean.TRUE.equals(
                            tx().execute(
                                            ignored -> {
                                                Assignment current = repo.find(a.getId(), true);
                                                if (claim != null
                                                        && !repo.assignmentClaimValid(
                                                                a.getId(), claim, clock.instant()))
                                                    return true;
                                                if (current.getStatus()
                                                        != DeliveryStatus.WAITING_ASSIGNMENT)
                                                    return true;
                                                if (!Boolean.TRUE.equals(
                                                        repo.shipper(id).get("active")))
                                                    return false;
                                                assign(current, id, actor, null, false);
                                                return true;
                                            }));
            if (assigned) return;
        }
        notifyAssignmentFailed(a.getId());
    }

    /** Bao admin DUNG MOT LAN moi dot chuyen nam o "Cho phan cong" (review PR 68, muc 4.8). */
    private void notifyAssignmentFailed(String id) {
        tx().executeWithoutResult(
                        ignored -> {
                            Assignment current = repo.find(id, true);
                            if (current.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT) return;
                            int updated =
                                    jdbc.update(
                                            "update delivery_assignments set"
                                                + " assignment_failure_notified=true where id=?"
                                                + " and assignment_failure_notified=false",
                                            id);
                            if (updated == 1)
                                event(
                                        current,
                                        EventType.DELIVERY_ASSIGNMENT_FAILED,
                                        new DeliveryAssignmentFailed(
                                                current.getId(),
                                                current.getOrderId(),
                                                current.getDistrict() == null
                                                        ? "UNKNOWN_ZONE"
                                                        : "NO_SHIPPER_AVAILABLE"));
                        });
    }

    private void assign(
            Assignment a, String shipper, String actor, String reason, boolean areaOverride) {
        String previous = a.getShipperId();
        a.setShipperId(shipper);
        if (a.getStatus() == DeliveryStatus.WAITING_ASSIGNMENT)
            a.setStatus(DeliveryStatus.ASSIGNED);
        if (previous != null) {
            a.setLatitude(null);
            a.setLongitude(null);
            a.setLocationAt(null);
            revoke(a, previous);
        }
        a.setNeedsAttention(false);
        a.setGpsSource(null);
        a.setGpsSourceAt(null);
        a.setVersion(a.getVersion() + 1);
        a.setTrackingSequence(a.getTrackingSequence() + 1);
        repo.save(a);
        repo.history(a, actor, reason, previous, areaOverride);
        jdbc.update(
                "update delivery_assignments set assignment_failure_notified=false where id=?",
                a.getId());
        event(
                a,
                EventType.DELIVERY_ASSIGNED,
                new DeliveryAssigned(a.getId(), a.getOrderId(), a.getBuyerId(), shipper));
        tracking(a);
    }

    public Assignment assign(String actor, String id, DeliveryRequests.Assign request) {
        clients.admin(actor);
        String shipper = request.shipperId();
        if (!clients.activeShipper(shipper))
            throw ApiException.conflict("SHIPPER_INACTIVE", "Shipper chưa kích hoạt hoặc bị khoá");
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            if (shipper.equals(a.getShipperId())
                                    && (a.getVersion() == request.expectedVersion()
                                            || a.getVersion() == request.expectedVersion() + 1))
                                return a;
                            // Hang da roi shop thi khong doi shipper: dung "Chuyen hoan hang".
                            if (a.getStatus() != DeliveryStatus.WAITING_ASSIGNMENT
                                    && a.getStatus() != DeliveryStatus.ASSIGNED) {
                                throw ApiException.conflict(
                                        "REASSIGN_NOT_ALLOWED",
                                        "Chỉ đổi shipper khi chưa lấy hàng; hãy chuyển hoàn hàng");
                            }
                            var s = repo.shipper(shipper);
                            if (!Boolean.TRUE.equals(s.get("active")))
                                throw ApiException.conflict(
                                        "SHIPPER_INACTIVE", "Shipper đã bị khoá");
                            boolean mismatch =
                                    isAreaMismatch(
                                            (String) s.get("province"),
                                            (String) s.get("district"),
                                            a.getProvince(),
                                            a.getDistrict());
                            if (mismatch && !Boolean.TRUE.equals(request.overrideArea())) {
                                throw ApiException.conflict(
                                        "AREA_MISMATCH", "Shipper không phụ trách khu vực này");
                            }
                            boolean noReason =
                                    request.reason() == null || request.reason().isBlank();
                            if (mismatch && noReason) {
                                throw ApiException.badRequest(
                                        "ASSIGNMENT_REASON_REQUIRED",
                                        "Cần lý do khi gán shipper khác khu");
                            }
                            // Gan tay cho shipper ngoai ca duoc, nhung bat buoc ly do.
                            if (!Boolean.TRUE.equals(s.get("on_shift")) && noReason) {
                                throw ApiException.badRequest(
                                        "SHIFT_REASON_REQUIRED",
                                        "Cần lý do khi gán shipper đang ngoài ca");
                            }
                            fence(a, request.expectedVersion());
                            assign(a, shipper, actor, request.reason(), mismatch);
                            return repo.find(id, false);
                        });
    }

    private void requireShipper(Assignment a, String actor) {
        if (!actor.equals(a.getShipperId())) throw forbidden();
        requireLocalShipper(actor);
    }

    private void requireLocalShipper(String actor) {
        if (!Boolean.TRUE.equals(repo.shipper(actor).get("active"))) {
            throw new ApiException(
                    "SHIPPER_INACTIVE",
                    HttpStatus.FORBIDDEN,
                    "Shipper đã bị khoá hoặc chưa kích hoạt");
        }
    }

    private void requireActiveShipper(String actor) {
        requireLocalShipper(actor);
        if (!clients.activeShipper(actor)) {
            throw new ApiException(
                    "SHIPPER_INACTIVE",
                    HttpStatus.FORBIDDEN,
                    "Shipper đã bị khoá hoặc chưa kích hoạt");
        }
    }

    // ---------------------------------------------------------------- shipper actions

    /** Doi trang thai sau khi lay hang: bat dau giao, giao thanh cong, giao that bai. */
    public Assignment status(String actor, String id, DeliveryRequests.Status request) {
        requireShipper(repo.find(id, false), actor);
        requireActiveShipper(actor);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            requireShipper(a, actor);
                            DeliveryStatus next = request.status();
                            if (next == DeliveryStatus.PICKED_UP)
                                throw ApiException.conflict(
                                        "PICKUP_PER_SHOP",
                                        "Xác nhận lấy hàng ở từng shop để chuyển sang Đã lấy hàng");
                            if (next == DeliveryStatus.RETURNED
                                    || next == DeliveryStatus.RETURN_DROPPED_OFF)
                                throw ApiException.conflict(
                                        "RETURN_PER_SHOP",
                                        "Xác nhận đã trả hàng ở từng shop");
                            if (next == DeliveryStatus.DELIVERED) {
                                if (request.codCollected() == null
                                        || request.codCollected() != a.getCodAmount()) {
                                    throw ApiException.badRequest(
                                            "COD_AMOUNT_MISMATCH",
                                            "Phải xác nhận đúng số COD (0 cho đơn trả trước)");
                                }
                            } else if (request.codCollected() != null)
                                throw ApiException.badRequest(
                                        "COD_NOT_APPLICABLE", "Chỉ thu COD khi giao thành công");
                            DeliveryRules.FailureReason reason = null;
                            if (next == DeliveryStatus.FAILED) reason = failureReason(request);
                            if (a.getStatus() == next
                                    && (a.getVersion() == request.expectedVersion()
                                            || a.getVersion() == request.expectedVersion() + 1))
                                return a;
                            if (a.getStatus() == DeliveryStatus.RETURNING
                                    && next == DeliveryStatus.FAILED
                                    && a.getVersion() == request.expectedVersion() + 1) return a;
                            fence(a, request.expectedVersion());
                            boolean valid =
                                    switch (a.getStatus()) {
                                        case PICKED_UP -> next == DeliveryStatus.IN_TRANSIT;
                                        case IN_TRANSIT ->
                                                next == DeliveryStatus.DELIVERED
                                                        || next == DeliveryStatus.FAILED;
                                        default -> false;
                                    };
                            if (!valid)
                                throw ApiException.conflict(
                                        "INVALID_TRANSITION",
                                        "Không thể chuyển trạng thái giao hàng");
                            DeliveryStatus previous = a.getStatus();
                            if (next == DeliveryStatus.FAILED) {
                                fail(a, actor, reason, request.reason());
                            } else {
                                a.setStatus(next);
                                a.setVersion(a.getVersion() + 1);
                                a.setTrackingSequence(a.getTrackingSequence() + 1);
                                repo.save(a);
                                repo.history(a, actor, request.reason());
                                event(
                                        a,
                                        EventType.DELIVERY_STATUS_CHANGED,
                                        new DeliveryStatusChanged(
                                                a.getOrderId(), next.name(), a.getVersion()));
                                if (next == DeliveryStatus.DELIVERED
                                        && "COD".equals(a.getPaymentMethod())) {
                                    event(
                                            a,
                                            EventType.COD_COLLECTED,
                                            new CodCollected(
                                                    a.getOrderId(),
                                                    a.getBuyerId(),
                                                    a.getCodAmount(),
                                                    a.getId()));
                                }
                            }
                            revokeBuyerIfHidden(a, previous);
                            tracking(a);
                            return repo.find(id, false);
                        });
    }

    private DeliveryRules.FailureReason failureReason(DeliveryRequests.Status request) {
        var reason =
                rules.reason(request.reasonCode())
                        .orElseThrow(
                                () ->
                                        ApiException.badRequest(
                                                "FAILURE_REASON_REQUIRED",
                                                "Chọn lý do giao thất bại"));
        if (reason.noteRequired() && (request.reason() == null || request.reason().isBlank()))
            throw ApiException.badRequest(
                    "FAILURE_NOTE_REQUIRED", "Lý do \"" + reason.label() + "\" cần ghi chú");
        return reason;
    }

    /**
     * Giao that bai: ghi ly do chuan (publicReason) va ghi chu noi bo rieng. Khach tu choi / sai
     * dia chi, hoac that bai lan cuoi -> hoan hang ngay.
     */
    private void fail(Assignment a, String actor, DeliveryRules.FailureReason reason, String note) {
        Instant now = clock.instant();
        a.setStatus(DeliveryStatus.FAILED);
        a.setFailureReasonCode(reason.code());
        a.setLastFailedAt(now);
        boolean returning = reason.returnImmediately() || a.getAttempt() >= rules.maxAttempts();
        // Giu dong FAILED trong lich su truoc khi chuyen sang hoan hang.
        repo.history(a, actor, note, null, false, reason.code(), reason.label());
        if (returning) {
            a.setStatus(DeliveryStatus.RETURNING);
            repo.history(a, actor, null, null, false, reason.code(), reason.label());
        }
        a.setVersion(a.getVersion() + 1);
        a.setTrackingSequence(a.getTrackingSequence() + 1);
        repo.save(a);
        event(
                a,
                EventType.DELIVERY_STATUS_CHANGED,
                new DeliveryStatusChanged(
                        a.getOrderId(),
                        a.getStatus().name(),
                        a.getVersion(),
                        reason.label(),
                        returning ? null : earliestRetryAt(a)));
    }

    /** Gio som nhat duoc giao lai; null neu chuyen khong o FAILED hoac het luot. */
    public Instant earliestRetryAt(Assignment a) {
        if (a.getStatus() != DeliveryStatus.FAILED
                || a.getAttempt() >= rules.maxAttempts()
                || a.getLastFailedAt() == null) return null;
        return a.getLastFailedAt().plus(Duration.ofHours(rules.retryHours()));
    }

    public Assignment retry(String actor, String id, boolean admin, long expectedVersion) {
        if (admin) clients.admin(actor);
        Assignment before = repo.find(id, false);
        if (!admin) requireShipper(before, actor);
        String shipper = before.getShipperId();
        if (shipper == null) throw ApiException.conflict("NOT_ASSIGNED", "Chưa phân công");
        requireActiveShipper(shipper);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            if (!admin) requireShipper(a, actor);
                            else {
                                if (a.getShipperId() == null)
                                    throw ApiException.conflict("NOT_ASSIGNED", "Chưa phân công");
                                if (!shipper.equals(a.getShipperId()))
                                    throw ApiException.conflict(
                                            "STALE_DELIVERY", "Shipper đã thay đổi");
                                requireLocalShipper(shipper);
                            }
                            if (a.getStatus() == DeliveryStatus.IN_TRANSIT
                                    && a.getAttempt() > 1
                                    && a.getVersion() == expectedVersion + 1) return a;
                            fence(a, expectedVersion);
                            if (a.getStatus() != DeliveryStatus.FAILED
                                    || a.getAttempt() >= rules.maxAttempts())
                                throw ApiException.conflict(
                                        "RETRY_LIMIT",
                                        "Chỉ giao tối đa " + rules.maxAttempts() + " lần");
                            Instant earliest = earliestRetryAt(a);
                            if (earliest != null && clock.instant().isBefore(earliest))
                                throw ApiException.conflict(
                                        "RETRY_TOO_EARLY",
                                        "Chỉ được giao lại sau " + earliest);
                            a.setAttempt(a.getAttempt() + 1);
                            a.setStatus(DeliveryStatus.IN_TRANSIT);
                            a.setVersion(a.getVersion() + 1);
                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                            repo.save(a);
                            repo.history(a, actor, "Giao lại");
                            event(
                                    a,
                                    EventType.DELIVERY_STATUS_CHANGED,
                                    new DeliveryStatusChanged(
                                            a.getOrderId(), "IN_TRANSIT", a.getVersion()));
                            tracking(a);
                            return repo.find(id, false);
                        });
    }

    /** Xac nhan da lay hang o mot shop; lay du moi shop thi chuyen PICKED_UP. */
    public Assignment confirmPickup(String actor, String id, String shopId, long expectedVersion) {
        requireShipper(repo.find(id, false), actor);
        requireActiveShipper(actor);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            requireShipper(a, actor);
                            requireHandoff(a, shopId);
                            if (a.getStatus() != DeliveryStatus.ASSIGNED) {
                                if (a.getStatus().pickedUp()) return a;
                                throw ApiException.conflict(
                                        "INVALID_TRANSITION", "Chuyến chưa ở bước lấy hàng");
                            }
                            fence(a, expectedVersion);
                            if (!repo.markHandoff(a.getId(), shopId, "picked_up_at", clock.instant()))
                                return a;
                            a.setVersion(a.getVersion() + 1);
                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                            if (repo.allHandoffs(a.getId(), "picked_up_at")) {
                                a.setStatus(DeliveryStatus.PICKED_UP);
                                repo.save(a);
                                repo.history(a, actor, null);
                                event(
                                        a,
                                        EventType.DELIVERY_STATUS_CHANGED,
                                        new DeliveryStatusChanged(
                                                a.getOrderId(), "PICKED_UP", a.getVersion()));
                            } else repo.save(a);
                            tracking(a);
                            return repo.find(id, false);
                        });
    }

    /** Xac nhan da tra hang cho mot shop; tra du thi chuyen RETURN_DROPPED_OFF va dung GPS. */
    public Assignment confirmReturn(String actor, String id, String shopId, long expectedVersion) {
        requireShipper(repo.find(id, false), actor);
        requireActiveShipper(actor);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            requireShipper(a, actor);
                            requireHandoff(a, shopId);
                            if (a.getStatus() != DeliveryStatus.RETURNING) {
                                if (a.getStatus() == DeliveryStatus.RETURN_DROPPED_OFF
                                        || a.getStatus() == DeliveryStatus.RETURNED) return a;
                                throw ApiException.conflict(
                                        "INVALID_TRANSITION", "Chuyến không ở bước hoàn hàng");
                            }
                            fence(a, expectedVersion);
                            if (!repo.markHandoff(a.getId(), shopId, "returned_at", clock.instant()))
                                return a;
                            a.setVersion(a.getVersion() + 1);
                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                            if (repo.allHandoffs(a.getId(), "returned_at")) {
                                a.setStatus(DeliveryStatus.RETURN_DROPPED_OFF);
                                a.setDroppedOffAt(clock.instant());
                                repo.save(a);
                                repo.history(a, actor, null);
                                event(
                                        a,
                                        EventType.DELIVERY_STATUS_CHANGED,
                                        new DeliveryStatusChanged(
                                                a.getOrderId(),
                                                "RETURN_DROPPED_OFF",
                                                a.getVersion()));
                            } else repo.save(a);
                            tracking(a);
                            return repo.find(id, false);
                        });
    }

    private void requireHandoff(Assignment a, String shopId) {
        if (repo.handoffs(a.getId()).stream().noneMatch(h -> shopId.equals(h.get("shop_id"))))
            throw ApiException.notFound("PICKUP_NOT_FOUND", "Shop không thuộc chuyến này");
    }

    /** Seller xac nhan da nhan lai hang hoan; moi shop xac nhan thi chuyen RETURNED. */
    public Map<String, Object> sellerReturnReceived(String actor, String bearer, String orderId) {
        JsonNode shop = clients.shop(bearer);
        if (!actor.equals(shop.path("ownerId").asString())) throw forbidden();
        String shopId = shop.path("id").asString();
        Assignment result =
                tx().execute(
                                ignored -> {
                                    Assignment a = lookupOrderForUpdate(orderId);
                                    requireHandoff(a, shopId);
                                    if (a.getStatus() == DeliveryStatus.RETURNED) return a;
                                    if (a.getStatus() != DeliveryStatus.RETURN_DROPPED_OFF)
                                        throw ApiException.conflict(
                                                "RETURN_NOT_DROPPED_OFF",
                                                "Shipper chưa trả hàng về shop");
                                    repo.markHandoff(
                                            a.getId(), shopId, "return_received_at", clock.instant());
                                    if (repo.allHandoffs(a.getId(), "return_received_at"))
                                        closeReturn(a, actor, null);
                                    return repo.find(a.getId(), false);
                                });
        return sellerAssignment(result);
    }

    private Assignment lookupOrderForUpdate(String order) {
        return repo.findOrder(order, true)
                .orElseThrow(
                        () ->
                                ApiException.notFound(
                                        "DELIVERY_NOT_FOUND", "Đơn chưa có chuyến giao"));
    }

    private void closeReturn(Assignment a, String actor, String note) {
        a.setStatus(DeliveryStatus.RETURNED);
        a.setVersion(a.getVersion() + 1);
        a.setTrackingSequence(a.getTrackingSequence() + 1);
        repo.save(a);
        repo.history(a, actor, note);
        event(
                a,
                EventType.DELIVERY_STATUS_CHANGED,
                new DeliveryStatusChanged(a.getOrderId(), "RETURNED", a.getVersion()));
        tracking(a);
    }

    /** Seller khong xac nhan sau N ngay thi he thong tu chuyen RETURNED (co ghi lich su). */
    public int autoCloseReturns() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(rules.returnAutoCloseDays()));
        List<String> due =
                jdbc.queryForList(
                        "select id from delivery_assignments where status='RETURN_DROPPED_OFF'"
                                + " and dropped_off_at <= ? order by dropped_off_at limit 100",
                        String.class,
                        Timestamp.from(cutoff));
        int closed = 0;
        for (String id : due) {
            Boolean done =
                    tx().execute(
                                    ignored -> {
                                        Assignment a = repo.find(id, true);
                                        if (a.getStatus() != DeliveryStatus.RETURN_DROPPED_OFF)
                                            return false;
                                        closeReturn(
                                                a,
                                                "system:return-auto-close",
                                                "Tự động xác nhận sau "
                                                        + rules.returnAutoCloseDays()
                                                        + " ngày");
                                        return true;
                                    });
            if (Boolean.TRUE.equals(done)) closed++;
        }
        return closed;
    }

    /**
     * Nhan mot diem GPS. Mot nguon moi chuyen: mobile la nguon chinh va gianh quyen tu web; web
     * gui khi mobile dang hoat dong thi bi tu choi GPS_SOURCE_CONFLICT (review PR 68, muc 4.12).
     */
    public Assignment location(String actor, String id, DeliveryRequests.Location point) {
        if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude()))
            throw ApiException.badRequest("INVALID_LOCATION", "GPS không hợp lệ");
        requireShipper(repo.find(id, false), actor);
        requireActiveShipper(actor);
        return tx().execute(ignored -> acceptLocation(actor, id, point));
    }

    private Assignment acceptLocation(String actor, String id, DeliveryRequests.Location point) {
        Assignment a = repo.find(id, true);
        requireShipper(a, actor);
        if (!a.getStatus().tracking())
            throw ApiException.conflict(
                    "TRACKING_STOPPED", "Không nhận GPS khi chuyến không hoạt động");
        Instant now = clock.instant();
        String source = point.sourceOrDefault();
        if (a.getGpsSource() != null
                && !a.getGpsSource().equals(source)
                && "WEB".equals(source)
                && a.getGpsSourceAt() != null
                && a.getGpsSourceAt().plus(GPS_SOURCE_WINDOW).isAfter(now)) {
            throw ApiException.conflict(
                    "GPS_SOURCE_CONFLICT",
                    "Ứng dụng di động đang gửi vị trí cho chuyến này; trình duyệt không gửi thêm");
        }
        if (a.getLocationAt() != null && a.getLocationAt().plusSeconds(10).isAfter(now)) {
            throw new ApiException(
                    "PING_TOO_FREQUENT",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Gửi GPS cách nhau ít nhất 10 giây");
        }
        a.setLatitude(point.latitude());
        a.setLongitude(point.longitude());
        a.setLocationAt(now);
        a.setGpsSource(source);
        a.setGpsSourceAt(now);
        a.setLocationSequence(a.getLocationSequence() + 1);
        a.setTrackingSequence(a.getTrackingSequence() + 1);
        repo.save(a);
        repo.route(a);
        tracking(a);
        return repo.find(id, false);
    }

    /**
     * Mot diem GPS cho moi chuyen dang chay cua shipper trong MOT request. Ket qua tung chuyen:
     * OK hoac ma loi (TRACKING_STOPPED, PING_TOO_FREQUENT, GPS_SOURCE_CONFLICT, ...).
     */
    public Map<String, Object> batchLocations(String actor, DeliveryRequests.Location point) {
        if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude()))
            throw ApiException.badRequest("INVALID_LOCATION", "GPS không hợp lệ");
        requireActiveShipper(actor);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Assignment a : repo.assigned(actor, "active", 0, 200)) {
            if (!a.getStatus().tracking()) continue;
            String code;
            try {
                tx().execute(ignored -> acceptLocation(actor, a.getId(), point));
                code = "OK";
            } catch (ApiException ex) {
                code = ex.getCode();
            }
            results.add(Map.of("assignmentId", a.getId(), "orderId", a.getOrderId(), "result", code));
        }
        return Map.of("results", results);
    }

    private static void fence(Assignment a, long expected) {
        if (a.getVersion() != expected)
            throw ApiException.conflict(
                    "STALE_DELIVERY", "Chuyến đã thay đổi, hãy tải lại trước khi thao tác");
    }

    private void tracking(Assignment a) {
        event(a, "TrackingUpdate", TrackingUpdate.from(a));
    }

    private void revoke(Assignment a, String actor) {
        var revoked = new TrackingRevoked(a.getId(), actor);
        event(
                a,
                "TrackingAccessRevoked",
                Map.of("type", "TrackingAccessRevoked", "assignmentId", a.getId(), "actor", actor));
        applicationEvents.publishEvent(revoked);
    }

    /** Hang roi khoi doan duong toi nguoi mua: dong socket theo doi cua nguoi mua ngay. */
    private void revokeBuyerIfHidden(Assignment a, DeliveryStatus previous) {
        if (previous.buyerTracking() && !a.getStatus().buyerTracking())
            revoke(a, a.getBuyerId());
    }

    /**
     * Admin "Chuyen hoan hang" (bat buoc ly do noi bo): dung cho chuyen da lay hang ma gap su co,
     * thay cho doi shipper (review PR 68, muc 4.6).
     */
    public Assignment returnFailed(String actor, String id, DeliveryRequests.Return request) {
        clients.admin(actor);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            if (a.getStatus() == DeliveryStatus.RETURNING
                                    && a.getVersion() == request.expectedVersion() + 1) return a;
                            fence(a, request.expectedVersion());
                            if (!Set.of(
                                            DeliveryStatus.PICKED_UP,
                                            DeliveryStatus.IN_TRANSIT,
                                            DeliveryStatus.FAILED)
                                    .contains(a.getStatus()))
                                throw ApiException.conflict(
                                        "INVALID_TRANSITION",
                                        "Chỉ chuyển hoàn hàng khi hàng đã rời shop");
                            DeliveryStatus previous = a.getStatus();
                            a.setStatus(DeliveryStatus.RETURNING);
                            a.setNeedsAttention(false);
                            a.setVersion(a.getVersion() + 1);
                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                            repo.save(a);
                            repo.history(a, actor, request.reason());
                            event(
                                    a,
                                    EventType.DELIVERY_STATUS_CHANGED,
                                    new DeliveryStatusChanged(
                                            a.getOrderId(), "RETURNING", a.getVersion()));
                            revokeBuyerIfHidden(a, previous);
                            tracking(a);
                            return a;
                        });
    }

    /**
     * Chuyen dang hoan ma shipper bi khoa (co "Can xu ly") khong con ai di tiep: admin xac nhan hang
     * da ve shop, chuyen sang RETURN_DROPPED_OFF de seller xac nhan / tu dong dong (review PR 68
     * lan 3, muc 1.4).
     */
    public Assignment adminReturnDroppedOff(
            String actor, String id, DeliveryRequests.Return request) {
        clients.admin(actor);
        return tx().execute(
                        ignored -> {
                            Assignment a = repo.find(id, true);
                            if (a.getStatus() == DeliveryStatus.RETURN_DROPPED_OFF
                                    && a.getVersion() == request.expectedVersion() + 1) return a;
                            fence(a, request.expectedVersion());
                            if (a.getStatus() != DeliveryStatus.RETURNING)
                                throw ApiException.conflict(
                                        "INVALID_TRANSITION", "Chuyến không ở bước hoàn hàng");
                            Instant now = clock.instant();
                            for (Map<String, Object> h : repo.handoffs(a.getId())) {
                                if (h.get("returned_at") == null)
                                    repo.markHandoff(
                                            a.getId(), (String) h.get("shop_id"), "returned_at", now);
                            }
                            if (a.getShipperId() != null) revoke(a, a.getShipperId());
                            a.setStatus(DeliveryStatus.RETURN_DROPPED_OFF);
                            a.setDroppedOffAt(now);
                            a.setNeedsAttention(false);
                            a.setVersion(a.getVersion() + 1);
                            a.setTrackingSequence(a.getTrackingSequence() + 1);
                            repo.save(a);
                            repo.history(a, actor, request.reason());
                            event(
                                    a,
                                    EventType.DELIVERY_STATUS_CHANGED,
                                    new DeliveryStatusChanged(
                                            a.getOrderId(), "RETURN_DROPPED_OFF", a.getVersion()));
                            tracking(a);
                            return repo.find(id, false);
                        });
    }

    // ---------------------------------------------------------------- access & views

    public void scope(Assignment a, String actor, boolean admin) {
        if (admin) {
            clients.admin(actor);
            return;
        }
        if (actor.equals(a.getBuyerId())) return;
        if (actor.equals(a.getShipperId())) {
            requireActiveShipper(actor);
            return;
        }
        if (isSeller(a, actor)) return;
        throw forbidden();
    }

    private boolean isSeller(Assignment a, String actor) {
        return repo.handoffs(a.getId()).stream().anyMatch(h -> actor.equals(h.get("seller_id")));
    }

    private Assignment lookupOrder(String order) {
        return repo.findOrder(order, false)
                .orElseThrow(
                        () ->
                                ApiException.notFound(
                                        "DELIVERY_NOT_FOUND", "Đơn chưa có chuyến giao"));
    }

    public Assignment order(String order, String actor, boolean admin) {
        Assignment a = lookupOrder(order);
        scope(a, actor, admin);
        return a;
    }

    /**
     * Quyen xem vi tri (NFR-SEC-07): admin luon, shipper cua chuyen, nguoi mua chi khi PICKED_UP /
     * IN_TRANSIT; seller khong bao gio.
     */
    public void trackingScope(Assignment a, String actor, boolean admin) {
        if (!admin && !actor.equals(a.getBuyerId()) && !actor.equals(a.getShipperId()))
            throw forbidden();
        scope(a, actor, admin);
        if (!admin && !actor.equals(a.getShipperId()) && !a.getStatus().buyerTracking())
            throw ApiException.conflict(
                    "TRACKING_NOT_AVAILABLE",
                    "Chỉ theo dõi vị trí khi shipper đã lấy hàng và đang giao");
    }

    public Assignment trackingOrder(String order, String actor, boolean admin) {
        Assignment a = lookupOrder(order);
        trackingScope(a, actor, admin);
        return a;
    }

    /** Chi tiet chuyen giao, loc theo vai tro nguoi goi (review PR 68, muc 4.1). */
    public Map<String, Object> detail(String order, String actor, boolean admin) {
        Assignment a = order(order, actor, admin);
        if (admin) {
            return Map.of(
                    "assignment",
                    a,
                    "order",
                    json.readTree(a.getOrderSnapshot()),
                    "pickups",
                    pickups(a),
                    "history",
                    repo.history(a.getId()));
        }
        if (actor.equals(a.getShipperId())) return Map.of("assignment", shipperView(a));
        if (actor.equals(a.getBuyerId())) return buyerView(a);
        return sellerView(a, order, actor);
    }

    private Map<String, Object> sellerView(Assignment a, String order, String actor) {
        var own =
                pickups(a).stream()
                        .filter(h -> actor.equals(h.get("seller_id")))
                        .toList();
        Set<String> shopIds = new HashSet<>();
        own.forEach(h -> shopIds.add((String) h.get("shop_id")));
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : json.readTree(a.getOrderSnapshot()).path("items")) {
            if (shopIds.contains(item.path("shopId").asString())) items.add(item);
        }
        return Map.of(
                "assignment",
                sellerAssignment(a),
                "order",
                Map.of("id", order, "items", items),
                "pickups",
                own,
                "history",
                publicHistory(a));
    }

    /** Nguoi mua: trang thai, moc thoi gian, ten + SDT shipper, vi tri khi dang giao, diem giao. */
    private Map<String, Object> buyerView(Assignment a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", a.getId());
        view.put("orderId", a.getOrderId());
        view.put("status", a.getStatus().name());
        view.put("attempt", a.getAttempt());
        view.put("createdAt", a.getCreatedAt());
        view.put("updatedAt", a.getUpdatedAt());
        view.put("earliestRetryAt", earliestRetryAt(a));
        view.put("codAmount", a.getCodAmount());
        view.put("deliveryPoint", deliveryPoint(json.readTree(a.getOrderSnapshot())));
        // Ten va SDT shipper tu khi phan cong toi khi giao xong (review PR 68, muc 4.11).
        if (a.getShipperId() != null
                && Set.of(
                                DeliveryStatus.ASSIGNED,
                                DeliveryStatus.PICKED_UP,
                                DeliveryStatus.IN_TRANSIT,
                                DeliveryStatus.FAILED)
                        .contains(a.getStatus())) {
            var s = repo.shipper(a.getShipperId());
            view.put("shipper", Map.of("name", s.get("name"), "phone", s.get("phone")));
        } else view.put("shipper", null);
        if (a.getStatus().buyerTracking() && a.getLatitude() != null)
            view.put(
                    "location",
                    Map.of(
                            "latitude",
                            a.getLatitude(),
                            "longitude",
                            a.getLongitude(),
                            "recordedAt",
                            a.getLocationAt()));
        else view.put("location", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignment", view);
        result.put("history", publicHistory(a));
        return result;
    }

    /** Lich su chi gom trang thai, thoi gian va ly do cong khai. */
    private List<Map<String, Object>> publicHistory(Assignment a) {
        return repo.history(a.getId()).stream()
                .map(
                        h -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("status", h.get("status"));
                            row.put("attempt", h.get("attempt"));
                            row.put("occurred_at", h.get("occurred_at"));
                            row.put("public_reason", h.get("public_reason"));
                            return row;
                        })
                .toList();
    }

    private Map<String, Object> deliveryPoint(JsonNode order) {
        if (!order.path("latitude").isNumber() || !order.path("longitude").isNumber()) return null;
        return Map.of(
                "latitude", order.path("latitude").asDouble(),
                "longitude", order.path("longitude").asDouble());
    }

    private static String address(JsonNode node) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("streetAddress", "ward", "district", "province")) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) parts.add(value);
        }
        return String.join(", ", parts);
    }

    /** Diem lay / tra hang: chi ten shop, dia chi, SDT, toa do va moc theo tung shop. */
    private List<Map<String, Object>> pickups(Assignment a) {
        return repo.handoffs(a.getId()).stream()
                .map(
                        h -> {
                            JsonNode shop =
                                    json.readTree(
                                            Objects.toString(h.get("pickup_snapshot"), "{}"));
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("shop_id", h.get("shop_id"));
                            row.put("seller_id", h.get("seller_id"));
                            row.put("name", shop.path("name").asString(""));
                            row.put("phone", shop.path("phone").asString(""));
                            row.put("address", address(shop));
                            row.put("location", deliveryPoint(shop));
                            row.put("handed_off_at", h.get("created_at"));
                            row.put("picked_up_at", h.get("picked_up_at"));
                            row.put("returned_at", h.get("returned_at"));
                            row.put("return_received_at", h.get("return_received_at"));
                            return row;
                        })
                .toList();
    }

    /** Shipper: nguoi nhan, diem lay hang, cac lan that bai truoc, gio duoc giao lai. */
    public Map<String, Object> shipperView(Assignment a) {
        JsonNode order = json.readTree(a.getOrderSnapshot());
        boolean returning =
                Set.of(
                                DeliveryStatus.RETURNING,
                                DeliveryStatus.RETURN_DROPPED_OFF,
                                DeliveryStatus.RETURNED)
                        .contains(a.getStatus());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", a.getId());
        view.put("orderId", a.getOrderId());
        view.put("status", a.getStatus().name());
        view.put("attempt", a.getAttempt());
        view.put("maxAttempts", rules.maxAttempts());
        view.put("version", a.getVersion());
        view.put("paymentMethod", a.getPaymentMethod());
        // Hoan hang khong thu tien: an COD va dia chi nguoi mua.
        view.put("codAmount", returning ? 0 : a.getCodAmount());
        view.put("createdAt", a.getCreatedAt());
        view.put("updatedAt", a.getUpdatedAt());
        view.put("assignedAt", repo.lastTransition(a.getId(), DeliveryStatus.ASSIGNED.name()));
        view.put(
                "completedAt",
                a.getStatus().terminal() || a.getStatus() == DeliveryStatus.RETURN_DROPPED_OFF
                        ? repo.lastTransition(a.getId(), a.getStatus().name())
                        : null);
        view.put("earliestRetryAt", earliestRetryAt(a));
        view.put("ward", a.getWard());
        view.put("zone", rules.zoneName(a.getDistrict()));
        view.put("gpsSource", a.getGpsSource());
        view.put("locationSequence", a.getLocationSequence());
        if (returning) view.put("recipient", null);
        else {
            Map<String, Object> recipient = new LinkedHashMap<>();
            recipient.put("name", order.path("recipientName").asString(""));
            recipient.put("phone", order.path("phone").asString(""));
            recipient.put("address", address(order));
            recipient.put("note", order.path("note").asString(""));
            recipient.put("location", deliveryPoint(order));
            view.put("recipient", recipient);
        }
        int items = 0;
        for (JsonNode item : order.path("items")) items += item.path("quantity").asInt(0);
        view.put("itemCount", items);
        view.put(
                "pickups",
                pickups(a).stream()
                        .map(
                                p -> {
                                    Map<String, Object> copy = new LinkedHashMap<>(p);
                                    copy.remove("seller_id");
                                    return (Map<String, Object>) copy;
                                })
                        .toList());
        view.put(
                "failures",
                repo.history(a.getId()).stream()
                        .filter(h -> "FAILED".equals(h.get("status")))
                        .map(
                                h -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("attempt", h.get("attempt"));
                                    row.put("public_reason", h.get("public_reason"));
                                    row.put("occurred_at", h.get("occurred_at"));
                                    return row;
                                })
                        .toList());
        return view;
    }

    public Map<String, Object> sellerAssignment(Assignment a) {
        return Map.of(
                "id",
                a.getId(),
                "orderId",
                a.getOrderId(),
                "status",
                a.getStatus().name(),
                "attempt",
                a.getAttempt(),
                "version",
                a.getVersion());
    }

    public List<Assignment> assigned(String actor, int page, int size) {
        requireActiveShipper(actor);
        return repo.assigned(actor, page, size);
    }

    public List<Map<String, Object>> assigned(String actor, String scope, int page, int size) {
        requireActiveShipper(actor);
        return repo.assigned(actor, scope, page, size).stream().map(this::shipperView).toList();
    }

    public static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
