package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;

import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.domain.DeliveryStatus;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryOutbox;
import com.vmarket.delivery.tracking.*;
import com.vmarket.events.EventPublisher;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real HTTP security, Flyway/H2 transactions and WebSocket transport; external services and Redis
 * boundary mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DeliveryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository repo;
    @Autowired com.vmarket.delivery.service.AssignmentRetryWorker assignmentRetry;
    @Autowired TrackingSocket socket;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("deliveryAssignmentNotifications")
    org.springframework.amqp.core.Declarables notificationTopology;

    @Autowired com.vmarket.delivery.config.DeliveryProperties properties;
    @Autowired com.vmarket.delivery.service.DeliveryService deliveryService;
    @LocalServerPort int port;
    @MockitoBean DeliveryClients clients;
    @MockitoBean StringRedisTemplate redis;
    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
    private static final String ORDER = "order-1",
            SHOP = "shop-1",
            SHIPPER = "shipper-1",
            BUYER = "buyer-1",
            SELLER = "seller-1";
    private final Map<String, String> ticketValues = new ConcurrentHashMap<>();

    @BeforeEach
    void seed() {
        properties.setWebSocketUrl("ws://localhost:" + port + "/api/delivery/ws");
        for (String table :
                List.of(
                        "delivery_outbox",
                        "route_logs",
                        "delivery_status_history",
                        "delivery_handoffs",
                        "delivery_assignments",
                        "shippers")) jdbc.update("delete from " + table);
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active,"
                    + " created_at) values (?, ?, 'Shipper', '0901234567', 'da nang', 'hai chau',"
                    + " true, CURRENT_TIMESTAMP)",
                SHIPPER,
                "provision-1");
        when(clients.activeShipper(anyString())).thenReturn(true);
        when(clients.shop(anyString()))
                .thenReturn(
                        json.readTree(
                                """
{"id":"shop-1","ownerId":"seller-1","status":"ACTIVE","province":"Đà Nẵng","district":"Hải Châu","streetAddress":"1 đường lấy hàng"}
"""));
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("COD", false));
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(
                        inv -> {
                            ticketValues.put(inv.getArgument(0), inv.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), any(java.time.Duration.class));
        when(values.getAndDelete(anyString()))
                .thenAnswer(inv -> ticketValues.remove(inv.getArgument(0)));
    }

    private JsonNode order(String method, boolean twoShops) {
        return json.readTree(
                """
{"id":"order-1","userId":"buyer-1","status":"PROCESSING","stockReserved":true,
 "paymentMethod":"METHOD","totalAmount":125000,"recipientName":"Buyer","phone":"0912345678",
 "province":"Đà Nẵng","district":"Hải Châu","ward":"Phường 1","streetAddress":"2 đường giao hàng",
 "items":[{"shopId":"shop-1"}EXTRA]}
"""
                        .replace("METHOD", method)
                        .replace("EXTRA", twoShops ? ",{\"shopId\":\"shop-2\"}" : ""));
    }

    private String token(String user, String role) {
        return Jwts.builder()
                .subject(user)
                .issuer("auth-service")
                .claim("roles", List.of(role))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    private ResultActions call(
            MockHttpServletRequestBuilder req, String user, String role, String body)
            throws Exception {
        req.header("Authorization", "Bearer " + token(user, role));
        String uri =
                req.buildRequest(new org.springframework.mock.web.MockServletContext())
                        .getRequestURI();
        var matcher =
                java.util.regex.Pattern.compile(
                                "/assignments/([^/]+)/(status|assign|retry|return|pickups/[^/]+|returns/[^/]+)$")
                        .matcher(uri);
        if (matcher.find()) {
            var node =
                    (tools.jackson.databind.node.ObjectNode)
                            json.readTree(body == null ? "{}" : body);
            if (!node.has("expectedVersion"))
                node.put("expectedVersion", repo.find(matcher.group(1), false).getVersion());
            // Test cu chi ghi ly do tu do: dung ly do chuan "giao lai" (ly do tu do = ghi chu noi bo).
            if ("FAILED".equals(node.path("status").asString(""))
                    && node.has("reason")
                    && !node.has("reasonCode"))
                node.put("reasonCode", "CUSTOMER_UNREACHABLE");
            // Giao lai phai cach lan that bai >= 2 gio: test cu gia lap da qua 2 gio.
            if (matcher.group(2).equals("retry") && skipRetryWait)
                jdbc.update(
                        "update delivery_assignments set last_failed_at=? where id=?",
                        java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(3))),
                        matcher.group(1));
            body = json.writeValueAsString(node);
        }
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private String handoff() throws Exception {
        return handoff("ASSIGNED");
    }

    private String handoff(String expectedStatus) throws Exception {
        var result =
                call(
                                post("/api/delivery/seller/orders/{id}/handoff", ORDER),
                                SELLER,
                                "SELLER",
                                null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(expectedStatus))
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asString();
    }

    /** Bo qua dieu kien 2 gio khi giao lai (test RETRY_TOO_EARLY tat co nay). */
    private boolean skipRetryWait = true;

    private void state(String id, String next) throws Exception {
        if (next.equals("PICKED_UP")) {
            // Lay hang theo tung shop: xac nhan du moi shop thi chuyen PICKED_UP.
            for (var h : repo.handoffs(id))
                call(
                                post(
                                        "/api/delivery/shipper/assignments/{id}/pickups/{shop}",
                                        id,
                                        h.get("shop_id")),
                                SHIPPER,
                                "SHIPPER",
                                null)
                        .andExpect(status().isOk());
            return;
        }
        if (next.equals("RETURNED")) {
            // Tra hang tung shop -> RETURN_DROPPED_OFF, roi seller xac nhan -> RETURNED.
            for (var h : repo.handoffs(id))
                call(
                                post(
                                        "/api/delivery/shipper/assignments/{id}/returns/{shop}",
                                        id,
                                        h.get("shop_id")),
                                SHIPPER,
                                "SHIPPER",
                                null)
                        .andExpect(status().isOk());
            call(
                            post(
                                    "/api/delivery/seller/orders/{id}/return-received",
                                    repo.find(id, false).getOrderId()),
                            SELLER,
                            "SELLER",
                            null)
                    .andExpect(status().isOk());
            return;
        }
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"" + next + "\"}")
                .andExpect(status().isOk());
    }

    private void inTransit(String id) throws Exception {
        state(id, "PICKED_UP");
        state(id, "IN_TRANSIT");
    }

    @Test
    void authorizationAndTrustedHandoff() throws Exception {
        mvc.perform(get("/api/delivery/shipper/assignments")).andExpect(status().isUnauthorized());
        call(get("/api/delivery/admin/shippers"), BUYER, "BUYER", null)
                .andExpect(status().isForbidden());
        call(
                        post("/api/delivery/seller/orders/{id}/handoff", ORDER),
                        "other-seller",
                        "SELLER",
                        null)
                .andExpect(status().isForbidden());
        verify(clients, never()).order(anyString(), eq(true));
        String id = handoff();
        assertThat(repo.find(id, false).getBuyerId()).isEqualTo(BUYER);
        call(get("/api/delivery/orders/{id}", ORDER), "other-buyer", "BUYER", null)
                .andExpect(status().isForbidden());
        call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignment.codAmount").value(125000));
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        "other-shipper",
                        "SHIPPER",
                        "{\"status\":\"PICKED_UP\"}")
                .andExpect(status().isForbidden());
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from delivery_handoffs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void everyShopMustHandoffAndInactiveOrWrongAreaCannotBeAssigned() throws Exception {
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("COD", true));
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_HANDOFF"));
        String id = repo.findOrder(ORDER, false).orElseThrow().getId();
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\"}")
                .andExpect(status().isConflict());
        when(clients.shop(anyString()))
                .thenReturn(
                        json.readTree(
                                "{\"id\":\"shop-2\",\"ownerId\":\"seller-2\",\"status\":\"ACTIVE\"}"));
        when(clients.activeShipper(SHIPPER)).thenReturn(false);
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), "seller-2", "SELLER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_ASSIGNMENT"));
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\"}")
                .andExpect(status().isConflict());
        when(clients.activeShipper(SHIPPER)).thenReturn(true);
        jdbc.update("update shippers set district='khác' where id=?", SHIPPER);
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\"}")
                .andExpect(status().isConflict());
        jdbc.update("update shippers set district='hai chau' where id=?", SHIPPER);
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\"}")
                .andExpect(status().isOk());
    }

    @Test
    void codMustMatchAndDuplicateCompletionCannotCollectTwice() throws Exception {
        String id = handoff();
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"DELIVERED\",\"codCollected\":125000}")
                .andExpect(status().isConflict());
        inTransit(id);
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"DELIVERED\",\"codCollected\":1}")
                .andExpect(status().isBadRequest());
        String body = "{\"status\":\"DELIVERED\",\"codCollected\":125000}";
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", body)
                .andExpect(status().isOk());
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", body)
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='CodCollected'",
                                Integer.class))
                .isEqualTo(1);
        var payload =
                json.readTree(
                        jdbc.queryForObject(
                                "select payload from delivery_outbox where"
                                        + " event_type='CodCollected'",
                                String.class));
        assertThat(payload.path("buyerId").asString()).isEqualTo(BUYER);
        assertThat(payload.path("amount").asLong()).isEqualTo(125000);
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.0,\"longitude\":108.0}")
                .andExpect(status().isConflict());
    }

    @Test
    void prepaidCannotCollectCodAndThirdFailureReturnsOrder() throws Exception {
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("VNPAY", false));
        String id = handoff();
        inTransit(id);
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"DELIVERED\",\"codCollected\":125000}")
                .andExpect(status().isBadRequest());
        for (int i = 1; i <= 3; i++) {
            call(
                            put("/api/delivery/shipper/assignments/{id}/status", id),
                            SHIPPER,
                            "SHIPPER",
                            "{\"status\":\"FAILED\"}")
                    .andExpect(status().isBadRequest());
            call(
                            put("/api/delivery/shipper/assignments/{id}/status", id),
                            SHIPPER,
                            "SHIPPER",
                            "{\"status\":\"FAILED\",\"reason\":\"Không liên hệ được\"}")
                    .andExpect(status().isOk());
            if (i < 3)
                call(
                                post("/api/delivery/shipper/assignments/{id}/retry", id),
                                SHIPPER,
                                "SHIPPER",
                                null)
                        .andExpect(status().isOk());
        }
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("RETURNING");
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isConflict());
        state(id, "RETURNED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_status_history where"
                                        + " status='FAILED'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='CodCollected'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "select payload from delivery_outbox where"
                                        + " event_type='DeliveryStatusChanged'",
                                String.class))
                .anyMatch(body -> body.contains("RETURNED"));
    }

    @Test
    void gpsBoundsFrequencyAndLocksAndPagination() throws Exception {
        String id = handoff();
        inTransit(id);
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":91,\"longitude\":108}")
                .andExpect(status().isBadRequest());
        String point = "{\"latitude\":16.04,\"longitude\":108.21}";
        call(post("/api/delivery/shipper/assignments/{id}/location", id), SHIPPER, "SHIPPER", point)
                .andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/location", id), SHIPPER, "SHIPPER", point)
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("select count(*) from route_logs", Integer.class))
                .isEqualTo(1);
        call(get("/api/delivery/orders/{id}/route", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latitude").value(16.04));
        call(get("/api/delivery/shipper/assignments?size=101"), SHIPPER, "SHIPPER", null)
                .andExpect(status().isBadRequest());
        call(
                        put("/api/delivery/admin/shippers/{id}/lock", SHIPPER),
                        "admin",
                        "ADMIN",
                        "{\"locked\":true}")
                .andExpect(status().isOk());
        verify(clients).lock("admin", SHIPPER, true);
        call(post("/api/delivery/shipper/assignments/{id}/location", id), SHIPPER, "SHIPPER", point)
                .andExpect(status().isForbidden());
    }

    @Test
    void delayedFailureReplayCannotCountAsAnotherDeliveryAttempt() throws Exception {
        String id = handoff();
        inTransit(id);
        long version = repo.find(id, false).getVersion();
        String failure =
                "{\"status\":\"FAILED\",\"reason\":\"No answer\",\"expectedVersion\":"
                        + version
                        + "}";
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", failure)
                .andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isOk());
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", failure)
                .andExpect(status().isConflict());
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("IN_TRANSIT");
        assertThat(repo.find(id, false).getAttempt()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_status_history where"
                                        + " status='FAILED'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void deniedAdminCannotMutateAndUnreservedCannotHandoff() throws Exception {
        doThrow(
                        new com.vmarket.delivery.exception.ApiException(
                                "FORBIDDEN",
                                org.springframework.http.HttpStatus.FORBIDDEN,
                                "Revoked"))
                .when(clients)
                .admin("admin");
        call(
                        put("/api/delivery/admin/shippers/{id}/lock", SHIPPER),
                        "admin",
                        "ADMIN",
                        "{\"locked\":true}")
                .andExpect(status().isForbidden());
        verify(clients, never()).lock(anyString(), anyString(), anyBoolean());
        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.put("stockReserved", false);
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from delivery_assignments", Integer.class))
                .isZero();
    }

    @Test
    void outboxKeepsFailedEventsAndRetriesWithoutLostDomainState() throws Exception {
        String id = handoff();
        EventPublisher publisher = mock(EventPublisher.class);
        RedisTracking tracking = mock(RedisTracking.class);
        var outbox =
                new DeliveryOutbox(
                        jdbc, json, publisher, tracking, Clock.systemUTC(), transactions);
        doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .publish(anyString(), any());
        outbox.events();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(repo.find(id, false).getShipperId()).isEqualTo(SHIPPER);
        reset(publisher);
        jdbc.update(
                "update delivery_outbox set next_attempt_at=?",
                java.sql.Timestamp.from(Instant.EPOCH));
        outbox.events();
        outbox.tracking();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox", Integer.class))
                .isZero();
        verify(publisher).publish(eq("DeliveryAssigned"), any());
        verify(tracking).publish(anyString());
    }

    @Test
    void missingNotificationRouteDoesNotBlockOrderStatusEvents() throws Exception {
        String id = handoff();
        inTransit(id);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("No notification binding"))
                .when(publisher)
                .publish(eq("DeliveryAssigned"), any());
        new DeliveryOutbox(
                        jdbc,
                        json,
                        publisher,
                        mock(RedisTracking.class),
                        Clock.systemUTC(),
                        transactions)
                .events();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='DeliveryStatusChanged'",
                                Integer.class))
                .isZero();
        verify(publisher, times(2)).publish(eq("DeliveryStatusChanged"), any());
    }

    @Test
    void fullBatchOfUnroutableAssignmentsCannotStarveLaterStatusOrCod() throws Exception {
        String id = handoff();
        inTransit(id);
        jdbc.update("delete from delivery_outbox");
        var assignment = repo.find(id, false);
        for (int i = 0; i < 100; i++) repo.outbox(assignment, "DeliveryAssigned", "{}");
        jdbc.update(
                "update delivery_outbox set created_at=?",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        repo.outbox(
                assignment,
                "DeliveryStatusChanged",
                json.writeValueAsString(
                        new com.vmarket.events.DeliveryStatusChanged(ORDER, "DELIVERED", 10)));
        repo.outbox(
                assignment,
                "CodCollected",
                json.writeValueAsString(
                        new com.vmarket.events.CodCollected(ORDER, BUYER, 100000L, id)));
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("No notification binding"))
                .when(publisher)
                .publish(eq("DeliveryAssigned"), any());
        var outbox =
                new DeliveryOutbox(
                        jdbc,
                        json,
                        publisher,
                        mock(RedisTracking.class),
                        Clock.systemUTC(),
                        transactions);
        for (int i = 0; i < 3; i++) outbox.events();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where event_type in"
                                        + " ('DeliveryStatusChanged','CodCollected')",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(100);
        verify(publisher).publish(eq("DeliveryStatusChanged"), any());
        verify(publisher).publish(eq("CodCollected"), any());
        verify(publisher, times(100)).publish(eq("DeliveryAssigned"), any());
    }

    @Test
    void retryDeadlineSurvivesDispatcherRestartAndBackoffIsBounded() throws Exception {
        String id = handoff();
        jdbc.update("delete from delivery_outbox");
        repo.outbox(repo.find(id, false), "DeliveryAssigned", "{}");
        Instant now =
                Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .publish(anyString(), any());
        long[] delays = {5, 10, 20, 40, 80, 160, 300, 300, 300};
        for (int attempt = 0; attempt < delays.length; attempt++) {
            Clock due = Clock.fixed(now, ZoneOffset.UTC);
            new DeliveryOutbox(jdbc, json, publisher, mock(RedisTracking.class), due, transactions)
                    .events();
            long wait = delays[attempt];
            Instant retryAt =
                    jdbc.queryForObject(
                                    "select next_attempt_at from delivery_outbox",
                                    java.time.OffsetDateTime.class)
                            .toInstant();
            assertThat(retryAt).isEqualTo(now.plusSeconds(wait));
            assertThat(
                            jdbc.queryForObject(
                                    "select retry_attempts from delivery_outbox", Integer.class))
                    .isEqualTo(attempt + 1);
            // A new dispatcher must honor the persisted deadline, even after a restart.
            new DeliveryOutbox(
                            jdbc,
                            json,
                            publisher,
                            mock(RedisTracking.class),
                            Clock.fixed(retryAt.minusMillis(1), ZoneOffset.UTC),
                            transactions)
                    .events();
            verify(publisher, times(attempt + 1)).publish(eq("DeliveryAssigned"), any());
            now = retryAt;
        }
        reset(publisher);
        new DeliveryOutbox(
                        jdbc,
                        json,
                        publisher,
                        mock(RedisTracking.class),
                        Clock.fixed(now, ZoneOffset.UTC),
                        transactions)
                .events();
        verify(publisher).publish(eq("DeliveryAssigned"), any());
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox", Integer.class))
                .isZero();
    }

    @Test
    void adminAssignmentListShowsShipperNameVersionAndFiltersByStatus() throws Exception {
        String id = handoff();
        call(get("/api/delivery/admin/assignments"), "admin", "ADMIN", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].shipper_name").value("Shipper"))
                .andExpect(jsonPath("$[0].shipper_phone").value("0901234567"))
                .andExpect(jsonPath("$[0].version").isNumber());
        call(get("/api/delivery/admin/assignments?status=ASSIGNED"), "admin", "ADMIN", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        call(
                        get("/api/delivery/admin/assignments?status=WAITING_ASSIGNMENT"),
                        "admin",
                        "ADMIN",
                        null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/delivery/admin/assignments?status=NOPE"), "admin", "ADMIN", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminShipperListSeparatesPendingActivationActiveAndLocked() throws Exception {
        handoff();
        when(clients.shipperStatuses(any())).thenReturn(Map.of(SHIPPER, "ACTIVE"));
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account_status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].active_orders").value(1))
                .andExpect(jsonPath("$[0].district_name").value("Hải Châu"));
        // Mot request toi auth cho ca trang, khong goi tung dong.
        verify(clients, atLeastOnce()).shipperStatuses(List.of(SHIPPER));
        when(clients.shipperStatuses(any())).thenReturn(Map.of(SHIPPER, "PENDING_ACTIVATION"));
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].account_status").value("PENDING_ACTIVATION"));
        // Bi khoa phia auth (khong phai chua kich hoat) -> Da khoa.
        when(clients.shipperStatuses(any())).thenReturn(Map.of(SHIPPER, "SUSPENDED"));
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].account_status").value("LOCKED"));
        when(clients.shipperStatuses(any())).thenThrow(new IllegalStateException("auth down"));
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].account_status").value("UNKNOWN"));
        jdbc.update("update shippers set active=false where id=?", SHIPPER);
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].account_status").value("LOCKED"));
    }

    @Test
    void unicodePrefixesAndUnaccentedAreasMatchExistingShippers() throws Exception {
        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.put(
                "province",
                java.text.Normalizer.normalize("TP. Đà Nẵng", java.text.Normalizer.Form.NFD));
        snapshot.put("district", "  Quận   Hải Châu  ");
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);
        String id = handoff();
        assertThat(repo.find(id, false).getProvince()).isEqualTo("da nang");
        call(
                        get(
                                "/api/delivery/admin/shippers?province=Tỉnh Da Nang&district=Q. Hai"
                                        + " Chau"),
                        "admin",
                        "ADMIN",
                        null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SHIPPER));
    }

    @Test
    void adminCanOverrideAreaOnlyWithAnAuditedReason() throws Exception {
        jdbc.update("update shippers set district='son tra' where id=?", SHIPPER);
        var response =
                call(
                                post("/api/delivery/seller/orders/{id}/handoff", ORDER),
                                SELLER,
                                "SELLER",
                                null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("WAITING_ASSIGNMENT"))
                        .andReturn();
        String id =
                json.readTree(response.getResponse().getContentAsString()).path("id").asString();
        String path = "/api/delivery/admin/assignments/{id}/assign";
        call(
                        post(path, id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\",\"overrideArea\":true}")
                .andExpect(status().isBadRequest());
        call(
                        post(path, id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-1\",\"overrideArea\":true,\"reason\":\"Hỗ trợ khu"
                                + " vực lân cận\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
        var audit =
                jdbc.queryForMap("select * from delivery_status_history where area_override=true");
        assertThat(audit.get("actor_id")).isEqualTo("admin");
        assertThat(audit.get("shipper_id")).isEqualTo(SHIPPER);
        assertThat(audit.get("reason")).isEqualTo("Hỗ trợ khu vực lân cận");
    }

    @Test
    void gpsDoesNotInvalidateStatusOrRetryAndOldFailureStillCannotReplay() throws Exception {
        String id = handoff();
        inTransit(id);
        long version = repo.find(id, false).getVersion();
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16,\"longitude\":108}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.locationSequence").value(1));
        String failure =
                "{\"status\":\"FAILED\",\"reason\":\"No answer\",\"expectedVersion\":"
                        + version
                        + "}";
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", failure)
                .andExpect(status().isOk());
        String retry = "{\"expectedVersion\":" + (version + 1) + "}";
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", retry)
                .andExpect(status().isOk());
        jdbc.update(
                "update delivery_assignments set location_at=? where id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                id);
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":17,\"longitude\":108}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 2));
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", retry)
                .andExpect(status().isOk());
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", failure)
                .andExpect(status().isConflict());
        assertThat(repo.find(id, false).getAttempt()).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "select sequence from route_logs order by sequence", Long.class))
                .containsExactly(1L, 2L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"PICKED_UP", "IN_TRANSIT", "FAILED", "RETURNING"})
    void lockingShipperHoldingCargoFlagsTripAndForbidsReassignment(String deliveryStatus)
            throws Exception {
        String id = handoff();
        state(id, "PICKED_UP");
        if (!deliveryStatus.equals("PICKED_UP")) state(id, "IN_TRANSIT");
        if (Set.of("FAILED", "RETURNING").contains(deliveryStatus)) {
            call(
                            put("/api/delivery/shipper/assignments/{id}/status", id),
                            SHIPPER,
                            "SHIPPER",
                            "{\"status\":\"FAILED\",\"reason\":\"Không liên hệ được\"}")
                    .andExpect(status().isOk());
        }
        if (deliveryStatus.equals("RETURNING")) {
            call(
                            post("/api/delivery/admin/assignments/{id}/return", id),
                            "admin",
                            "ADMIN",
                            "{\"reason\":\"Người mua yêu cầu hoàn\"}")
                    .andExpect(status().isOk());
        }
        call(
                        put("/api/delivery/admin/shippers/{id}/lock", SHIPPER),
                        "admin",
                        "ADMIN",
                        "{\"locked\":true}")
                .andExpect(status().isOk());
        assertThat(repo.find(id, false).isNeedsAttention()).isTrue();
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo(deliveryStatus);
        call(get("/api/delivery/admin/assignments"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].needs_attention").value(true));
        jdbc.update(
                "insert into shippers"
                    + " (id,provision_key,name,phone,province,district,active,created_at) values"
                    + " ('shipper-2','provision-2','Backup','0900000000','da nang','hai"
                    + " chau',true,CURRENT_TIMESTAMP)");
        // Hang da roi shop: khong doi shipper, chi chuyen hoan hang.
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-2\",\"reason\":\"Tiếp nhận hàng\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REASSIGN_NOT_ALLOWED"));
        if (!deliveryStatus.equals("RETURNING")) {
            call(
                            post("/api/delivery/admin/assignments/{id}/return", id),
                            "admin",
                            "ADMIN",
                            "{\"reason\":\"Shipper bị khoá giữa chuyến\"}")
                    .andExpect(status().isOk());
            assertThat(repo.find(id, false).isNeedsAttention()).isFalse();
        }
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void lockingShipperReturnsNotPickedUpTripToDispatchAndReassignsIt() throws Exception {
        String id = handoff("ASSIGNED");
        jdbc.update(
                "insert into shippers"
                    + " (id,provision_key,name,phone,province,district,active,created_at) values"
                    + " ('shipper-2','provision-2','Backup','0900000000','da nang','hai"
                    + " chau',true,CURRENT_TIMESTAMP)");
        call(
                        put("/api/delivery/admin/shippers/{id}/lock", SHIPPER),
                        "admin",
                        "ADMIN",
                        "{\"locked\":true}")
                .andExpect(status().isOk());
        var trip = repo.find(id, false);
        assertThat(trip.getStatus()).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(trip.getShipperId()).isEqualTo("shipper-2");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_status_history where"
                                        + " status='WAITING_ASSIGNMENT' and reason='Shipper bị khoá'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void adminCanReturnFailedCargoEvenWhenCurrentShipperIsLocked() throws Exception {
        String id = handoff();
        inTransit(id);
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"FAILED\",\"reason\":\"Từ chối nhận\"}")
                .andExpect(status().isOk());
        call(
                        put("/api/delivery/admin/shippers/{id}/lock", SHIPPER),
                        "admin",
                        "ADMIN",
                        "{\"locked\":true}")
                .andExpect(status().isOk());
        call(post("/api/delivery/admin/assignments/{id}/return", id), "admin", "ADMIN", "{}")
                .andExpect(status().isBadRequest());
        String body =
                "{\"reason\":\"Thu hồi hàng về kho\",\"expectedVersion\":"
                        + repo.find(id, false).getVersion()
                        + "}";
        call(post("/api/delivery/admin/assignments/{id}/return", id), "admin", "ADMIN", body)
                .andExpect(status().isOk());
        call(post("/api/delivery/admin/assignments/{id}/return", id), "admin", "ADMIN", body)
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_status_history where"
                                        + " status='RETURNING'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void authUnavailableCannotUndoHandoffAndNoAuthCallRunsInSqlTransaction() throws Exception {
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select count(*) from delivery_handoffs",
                                                    Integer.class))
                                    .isEqualTo(1);
                            throw com.vmarket.delivery.exception.ApiException.unavailable(
                                    "DEPENDENCY_UNAVAILABLE", "offline");
                        })
                .when(clients)
                .activeShipper(anyString());
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_ASSIGNMENT"));
        assertThat(jdbc.queryForObject("select count(*) from delivery_handoffs", Integer.class))
                .isEqualTo(1);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return true;
                        })
                .when(clients)
                .activeShipper(anyString());
        String id = handoff();
        inTransit(id);
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16,\"longitude\":108}")
                .andExpect(status().isOk());
        call(get("/api/delivery/shipper/assignments"), SHIPPER, "SHIPPER", null)
                .andExpect(status().isOk());
        call(get("/api/delivery/orders/{id}", ORDER), SHIPPER, "SHIPPER", null)
                .andExpect(status().isOk());
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"FAILED\",\"reason\":\"No answer\"}")
                .andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isOk());
    }

    @Test
    void assignmentEventHasADurableNotificationRoute() {
        assertThat(
                        notificationTopology.getDeclarablesByType(
                                org.springframework.amqp.core.Queue.class))
                .anySatisfy(
                        queue -> {
                            assertThat(queue.getName())
                                    .isEqualTo("notification-service.delivery-assigned");
                            assertThat(queue.isDurable()).isTrue();
                        });
        assertThat(
                        notificationTopology.getDeclarablesByType(
                                org.springframework.amqp.core.Binding.class))
                .anySatisfy(
                        binding -> {
                            assertThat(binding.getDestination())
                                    .isEqualTo("notification-service.delivery-assigned");
                            assertThat(binding.getRoutingKey()).isEqualTo("DeliveryAssigned");
                        });
    }

    @Test
    void readingShipperInsideTransactionDoesNotBlockAnIndependentUpdate() throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var reading =
                    pool.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    transactions)
                                            .executeWithoutResult(
                                                    ignored -> {
                                                        repo.shipper(SHIPPER);
                                                        read.countDown();
                                                        try {
                                                            assertThat(
                                                                            release.await(
                                                                                    5,
                                                                                    TimeUnit
                                                                                            .SECONDS))
                                                                    .isTrue();
                                                        } catch (InterruptedException interrupted) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(interrupted);
                                                        }
                                                    }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> jdbc.update("update shippers set active=false where id=?", SHIPPER))
                    .get(3, TimeUnit.SECONDS);
            release.countDown();
            reading.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sellerOnlySeesOwnShopItemsAndNeverFullAssignmentSnapshot() throws Exception {
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("COD", true));
        var response =
                call(
                                post("/api/delivery/seller/orders/{id}/handoff", ORDER),
                                SELLER,
                                "SELLER",
                                null)
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain("orderSnapshot", "codAmount", "buyer-1", "streetAddress");
        when(clients.shop(anyString()))
                .thenReturn(
                        json.readTree(
                                "{\"id\":\"shop-2\",\"ownerId\":\"seller-2\",\"status\":\"ACTIVE\"}"));
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), "seller-2", "SELLER", null)
                .andExpect(status().isOk());
        var seller =
                call(get("/api/delivery/orders/{id}", ORDER), SELLER, "SELLER", null)
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(seller.getResponse().getContentAsString())
                .contains("shop-1")
                .doesNotContain(
                        "shop-2",
                        "buyer-1",
                        "recipientName",
                        "0912345678",
                        "orderSnapshot",
                        "codAmount",
                        "totalAmount");
        var buyer =
                call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.assignment.status").value("ASSIGNED"))
                        .andExpect(jsonPath("$.assignment.shipper.name").value("Shipper"))
                        .andExpect(jsonPath("$.order").doesNotExist())
                        .andExpect(jsonPath("$.pickups").doesNotExist())
                        .andReturn();
        assertThat(buyer.getResponse().getContentAsString())
                .doesNotContain(
                        "pickup_snapshot",
                        "seller_id",
                        "actor_id",
                        "shipper_id",
                        "previous_shipper_id",
                        "orderSnapshot");
        for (String actor : List.of(SELLER, "seller-2")) {
            call(get("/api/delivery/orders/{id}/route", ORDER), actor, "SELLER", null)
                    .andExpect(status().isForbidden());
            call(post("/api/delivery/orders/{id}/tracking-ticket", ORDER), actor, "SELLER", null)
                    .andExpect(status().isForbidden());
        }
        // Nguoi mua chi theo doi khi PICKED_UP / IN_TRANSIT; luc nay chuyen moi ASSIGNED.
        call(get("/api/delivery/orders/{id}/route", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_AVAILABLE"));
        for (String[] allowed :
                List.of(new String[] {SHIPPER, "SHIPPER"}, new String[] {"admin", "ADMIN"})) {
            call(get("/api/delivery/orders/{id}/route", ORDER), allowed[0], allowed[1], null)
                    .andExpect(status().isOk());
        }
    }

    @Test
    void backgroundAssignmentRetriesAfterNoCandidateAndAfterAuthRecovers() throws Exception {
        jdbc.update("update shippers set active=false");
        String id = handoff("WAITING_ASSIGNMENT");
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("WAITING_ASSIGNMENT");
        assignmentRetry.runBatch();
        assertThat(
                        jdbc.queryForObject(
                                "select assignment_attempts from delivery_assignments where id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        jdbc.update("update shippers set active=true");
        when(clients.activeShipper(anyString()))
                .thenThrow(
                        com.vmarket.delivery.exception.ApiException.unavailable(
                                "AUTH_DOWN", "temporary"));
        jdbc.update(
                "update delivery_assignments set next_assignment_at=?",
                java.sql.Timestamp.from(Instant.EPOCH));
        assignmentRetry.runBatch();
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("WAITING_ASSIGNMENT");
        assertThat(
                        jdbc.queryForObject(
                                "select assignment_attempts from delivery_assignments where id=?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        // Backoff prevents a request loop while Auth is down.
        assignmentRetry.runBatch();
        verify(clients, times(1)).activeShipper(SHIPPER);
        doReturn(true).when(clients).activeShipper(anyString());
        jdbc.update(
                "update delivery_assignments set next_assignment_at=?",
                java.sql.Timestamp.from(Instant.EPOCH));
        assignmentRetry.runBatch();
        assertThat(repo.find(id, false).getShipperId()).isEqualTo(SHIPPER);
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("ASSIGNED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                    + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twoAssignmentWorkersShareOneLeaseAndDoNotHoldTransactionDuringAuth() throws Exception {
        when(clients.activeShipper(anyString())).thenReturn(false);
        String id = handoff("WAITING_ASSIGNMENT");
        CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
        when(clients.activeShipper(anyString()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            checking.countDown();
                            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                            return true;
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(assignmentRetry::runBatch);
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(assignmentRetry::runBatch).get(3, TimeUnit.SECONDS);
            release.countDown();
            first.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(repo.find(id, false).getStatus().name()).isEqualTo("ASSIGNED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                    + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredWorkerCannotOverwriteAdminAssignment() throws Exception {
        when(clients.activeShipper(anyString())).thenReturn(false);
        String id = handoff("WAITING_ASSIGNMENT");
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active,"
                    + " created_at) values ('shipper-2','provision-2','Second','0900000000','da"
                    + " nang','hai chau',true,CURRENT_TIMESTAMP)");
        CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
        when(clients.activeShipper(SHIPPER))
                .thenAnswer(
                        inv -> {
                            checking.countDown();
                            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                            return true;
                        });
        when(clients.activeShipper("shipper-2")).thenReturn(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var stale = pool.submit(assignmentRetry::runBatch);
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "update delivery_assignments set assignment_claim_until=? where id=?",
                    java.sql.Timestamp.from(Instant.EPOCH),
                    id);
            call(
                            post("/api/delivery/admin/assignments/{id}/assign", id),
                            "admin",
                            "ADMIN",
                            "{\"shipperId\":\"shipper-2\"}")
                    .andExpect(status().isOk());
            release.countDown();
            stale.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(repo.find(id, false).getShipperId()).isEqualTo("shipper-2");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                    + " event_type='DeliveryAssigned'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sellerTicketIssuedBeforePolicyChangeCannotOpenTrackingSocket() throws Exception {
        String id = handoff();
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getId()).thenReturn("legacy-seller");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(
                        Map.of(
                                "access",
                                new TrackingTickets.Access(
                                        id, SELLER, false, Instant.now().plusSeconds(60))));
        socket.afterConnectionEstablished(session);
        verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void deadOutboxRowsRetainPayloadAndStopAutomaticRetries() throws Exception {
        String id = handoff();
        jdbc.update("delete from delivery_outbox");
        repo.outbox(repo.find(id, false), "DeliveryAssigned", "{\"mustKeep\":true}");
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("unroutable"))
                .when(publisher)
                .publish(anyString(), any());
        var outbox =
                new DeliveryOutbox(
                        jdbc,
                        json,
                        publisher,
                        mock(RedisTracking.class),
                        Clock.systemUTC(),
                        transactions);
        for (int i = 0; i < DeliveryOutbox.MAX_ATTEMPTS + 2; i++) {
            jdbc.update(
                    "update delivery_outbox set next_attempt_at=?",
                    java.sql.Timestamp.from(Instant.EPOCH));
            outbox.events();
        }
        assertThat(jdbc.queryForObject("select state from delivery_outbox", String.class))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select payload from delivery_outbox", String.class))
                .contains("mustKeep");
        verify(publisher, times(DeliveryOutbox.MAX_ATTEMPTS)).publish(anyString(), any());
    }

    @Test
    void concurrentOutboxWorkersSkipLockedRowsInsteadOfPublishingThemTwice() throws Exception {
        String id = handoff();
        jdbc.update("delete from delivery_outbox");
        repo.outbox(repo.find(id, false), "DeliveryStatusChanged", "{\"first\":true}");
        EventPublisher publisher = mock(EventPublisher.class);
        CountDownLatch publishing = new CountDownLatch(1), finish = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            publishing.countDown();
                            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(publisher)
                .publish(anyString(), any());
        var outbox =
                new DeliveryOutbox(
                        jdbc,
                        json,
                        publisher,
                        mock(RedisTracking.class),
                        Clock.systemUTC(),
                        transactions);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(outbox::events);
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(outbox::events).get(3, TimeUnit.SECONDS);
            verify(publisher, times(1)).publish(anyString(), any());
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox", Integer.class))
                .isZero();
    }

    @Test
    void actualWebSocketIsScopedSingleUseAndDeliversGpsUnderFiveSeconds() throws Exception {
        String id = handoff();
        inTransit(id);
        call(post("/api/delivery/orders/{id}/tracking-ticket", ORDER), "other-buyer", "BUYER", null)
                .andExpect(status().isForbidden());
        var ticketResponse =
                json.readTree(
                        call(
                                        post("/api/delivery/orders/{id}/tracking-ticket", ORDER),
                                        BUYER,
                                        "BUYER",
                                        null)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String ticket = ticketResponse.path("ticket").asString();
        var messages = new LinkedBlockingQueue<String>();
        var listener =
                new WebSocket.Listener() {
                    private final StringBuilder body = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(
                            WebSocket ws, CharSequence data, boolean last) {
                        body.append(data);
                        if (last) {
                            messages.add(body.toString());
                            body.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                };
        URI uri = URI.create(ticketResponse.path("webSocketUrl").asString());
        WebSocket ws =
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .header("Origin", "http://localhost:5173")
                        .buildAsync(uri, listener)
                        .get(5, TimeUnit.SECONDS);
        assertThat(messages.poll(5, TimeUnit.SECONDS)).contains("IN_TRANSIT");
        assertThatThrownBy(
                        () ->
                                HttpClient.newHttpClient()
                                        .newWebSocketBuilder()
                                        .buildAsync(uri, listener)
                                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        long start = System.nanoTime();
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.04,\"longitude\":108.21}")
                .andExpect(status().isOk());
        // Mock Redis pub/sub boundary while using the real outbox and actual socket transport.
        RedisTracking tracking = mock(RedisTracking.class);
        doAnswer(
                        inv -> {
                            socket.accept(inv.getArgument(0));
                            return null;
                        })
                .when(tracking)
                .publish(anyString());
        new DeliveryOutbox(
                        jdbc,
                        json,
                        mock(EventPublisher.class),
                        tracking,
                        Clock.systemUTC(),
                        transactions)
                .tracking();
        String update = messages.poll(5, TimeUnit.SECONDS);
        assertThat(update).contains("16.04");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5000);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    // =========================================================================
    // PBL6-20 - Đơn và shipper không có quận/huyện (Đà Nẵng từ 01/07/2025)
    // =========================================================================

    @Test
    void donKhongCoQuanHuyen_traPhuongRaKhuGiaoHangVaGanShipperCuaKhu() throws Exception {
        // Review PR 68 lan 2, muc 3: dia chi chi co tinh + phuong; Phuong Hoa Cuong thuoc khu Hai Chau.
        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.putNull("district");
        snapshot.put("ward", "Phường Hòa Cường");
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);

        String id = handoff("ASSIGNED");

        var assignment = repo.find(id, false);
        assertThat(assignment.getDistrict()).isEqualTo("hai chau");
        assertThat(assignment.getWard()).isEqualTo("Phường Hòa Cường");
        assertThat(assignment.getShipperId()).isEqualTo(SHIPPER);
    }

    @Test
    void phuongLa_chuyenOLaiChoPhanCongVaBaoAdminMotLan() throws Exception {
        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.putNull("district");
        snapshot.put("ward", "Phường Không Có");
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);

        String id = handoff("WAITING_ASSIGNMENT");
        assertThat(repo.find(id, false).getDistrict()).isNull();
        assertThat(repo.find(id, false).getShipperId()).isNull();
        // Lan thu lai sau khong bao them.
        assignmentRetry.runBatch();
        jdbc.update(
                "update delivery_assignments set next_assignment_at=?",
                java.sql.Timestamp.from(Instant.EPOCH));
        assignmentRetry.runBatch();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_outbox where"
                                        + " event_type='DeliveryAssignmentFailed'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void donKhongCoQuanHuyen_ganShipperDungKhuCuaPhuong() throws Exception {
        String shipper2 = "shipper-2";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-2', 'Shipper 2', '0907654321', 'da nang', 'son tra', true, CURRENT_TIMESTAMP)",
                shipper2);
        when(clients.activeShipper(shipper2)).thenReturn(true);

        // Phuong An Hai thuoc khu Son Tra -> shipper khu Son Tra, khong phai shipper Hai Chau.
        var snapshot2 = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot2.put("id", "order-2");
        snapshot2.putNull("district");
        snapshot2.put("ward", "Phường An Hải");
        when(clients.order(eq("order-2"), anyBoolean())).thenReturn(snapshot2);

        var result = call(
                post("/api/delivery/seller/orders/{id}/handoff", "order-2"),
                SELLER,
                "SELLER",
                null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andReturn();

        String id2 = json.readTree(result.getResponse().getContentAsString()).path("id").asString();
        var assignment2 = repo.find(id2, false);
        assertThat(assignment2.getDistrict()).isEqualTo("son tra");
        assertThat(assignment2.getShipperId()).isEqualTo(shipper2);
    }

    @Test
    void taoShipper_khongCoQuanHuyen_thanhCongVaLuuNull() throws Exception {
        when(clients.createShipper(anyString(), anyString(), any())).thenReturn("shipper-new");

        String body = """
                {"account":{"email":"shipper.new@example.com","username":"shippernew","password":"Password123"},
                 "name":"Trần Văn Shipper","phone":"0987654321","province":"Thành phố Đà Nẵng"}
                """;

        call(
                post("/api/delivery/admin/shippers")
                        .header("Idempotency-Key", "provision-new-1"),
                "admin",
                "ADMIN",
                body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shipper-new"))
                .andExpect(jsonPath("$.province").value("da nang"))
                .andExpect(jsonPath("$.district").doesNotExist());

        Map<String, Object> saved = repo.shipper("shipper-new");
        assertThat(saved.get("district")).isNull();
        assertThat(saved.get("province")).isEqualTo("da nang");

        // Gửi lại cùng idempotency key -> thành công idempotent
        call(
                post("/api/delivery/admin/shippers")
                        .header("Idempotency-Key", "provision-new-1"),
                "admin",
                "ADMIN",
                body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shipper-new"));
    }

    @Test
    void taoShipper_quanHuyenQua100KyTu_tra400() throws Exception {
        String body = """
                {"account":{"email":"shipper.long@example.com","username":"shipperlong","password":"Password123"},
                 "name":"Shipper","phone":"0987654321","province":"Đà Nẵng",
                 "district":"%s"}
                """.formatted("a".repeat(101));

        call(
                post("/api/delivery/admin/shippers")
                        .header("Idempotency-Key", "provision-long-1"),
                "admin",
                "ADMIN",
                body)
                .andExpect(status().isBadRequest());
    }

    @Test
    void locShipperTheoTinhThanh_khiKhongCoQuanHuyen_traVeTatCaShipperTrongTinh() throws Exception {
        String shipperNoDistrict = "shipper-no-district";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-nd', 'Shipper ND', '0901111111', 'da nang', null, true, CURRENT_TIMESTAMP)",
                shipperNoDistrict);

        String shipperHanoi = "shipper-hanoi";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-hn', 'Shipper HN', '0902222222', 'ha noi', null, true, CURRENT_TIMESTAMP)",
                shipperHanoi);

        // Lọc tỉnh Đà Nẵng, không truyền district -> trả cả shipper có district và shipper district = null
        call(
                get("/api/delivery/admin/shippers?province=Đà Nẵng"),
                "admin",
                "ADMIN",
                null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id").value(hasItems(SHIPPER, shipperNoDistrict)))
                .andExpect(jsonPath("$[?(@.id == '%s')].district".formatted(shipperNoDistrict)).value(hasItem(nullValue())));
    }

    @Test
    void adminGanShipper_cungTinhKhongCoQuanHuyen_khongCanLyDoVaKhongLoiAreaMismatch() throws Exception {
        String shipperNoDistrict = "shipper-free";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-free', 'Shipper Free', '0903333333', 'da nang', null, true, CURRENT_TIMESTAMP)",
                shipperNoDistrict);
        when(clients.activeShipper(shipperNoDistrict)).thenReturn(true);

        // Đơn có district = 'hai chau'
        String id = handoff("ASSIGNED");

        // Gán cho shipper phụ trách toàn tỉnh (district = null) -> không bị AREA_MISMATCH
        call(
                post("/api/delivery/admin/assignments/{id}/assign", id),
                "admin",
                "ADMIN",
                "{\"shipperId\":\"shipper-free\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipperId").value(shipperNoDistrict));
    }

    @Test
    void adminGanShipper_khacTinh_duKhongCoQuanHuyen_vanYeuCauOverrideAreaVaLyDo() throws Exception {
        String shipperHanoi = "shipper-hn-free";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-hn-free', 'Shipper HN', '0904444444', 'ha noi', null, true, CURRENT_TIMESTAMP)",
                shipperHanoi);
        when(clients.activeShipper(shipperHanoi)).thenReturn(true);

        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.putNull("district");
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);

        // Phuong la -> khong tu dong phan cong.
        String id = handoff("WAITING_ASSIGNMENT");

        // Gán shipper Hà Nội cho đơn Đà Nẵng -> lỗi AREA_MISMATCH vì khác tỉnh
        call(
                post("/api/delivery/admin/assignments/{id}/assign", id),
                "admin",
                "ADMIN",
                "{\"shipperId\":\"shipper-hn-free\"}")
                .andExpect(status().isConflict());

        // Gán kèm overrideArea và reason -> thành công
        call(
                post("/api/delivery/admin/assignments/{id}/assign", id),
                "admin",
                "ADMIN",
                "{\"shipperId\":\"shipper-hn-free\",\"overrideArea\":true,\"reason\":\"Điều động liên tỉnh\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipperId").value(shipperHanoi));
    }
    @Test
    void adminGanShipper_donKhongCoKhu_shipperCoKhuCungTinh_khongBiAreaMismatch() throws Exception {
        var snapshot = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot.putNull("district");
        when(clients.order(anyString(), anyBoolean())).thenReturn(snapshot);

        // Phuong la -> cho admin; gan shipper cung tinh khong can ly do.
        String id = handoff("WAITING_ASSIGNMENT");

        call(
                post("/api/delivery/admin/assignments/{id}/assign", id),
                "admin",
                "ADMIN",
                "{\"shipperId\":\"" + SHIPPER + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipperId").value(SHIPPER));
    }

    @Test
    void ganShipperKhacKhu_batBuocLyDo() throws Exception {
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values ('shipper-st', 'provision-st', 'Son Tra', '0906666666', 'da nang', 'son tra', true, CURRENT_TIMESTAMP)");
        when(clients.activeShipper("shipper-st")).thenReturn(true);
        String id = handoff("ASSIGNED");
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-st\",\"overrideArea\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_REASON_REQUIRED"));
        call(
                        post("/api/delivery/admin/assignments/{id}/assign", id),
                        "admin",
                        "ADMIN",
                        "{\"shipperId\":\"shipper-st\",\"overrideArea\":true,\"reason\":\"Hai Chau het nguoi\"}")
                .andExpect(status().isOk());
    }

    @Test
    void donCoQuanHuyen_uuTienShipperChuyenQuanTruocShipperToanTinh() throws Exception {
        String shipperToanTinh = "shipper-toan-tinh";
        jdbc.update(
                "insert into shippers (id, provision_key, name, phone, province, district, active, created_at)"
                        + " values (?, 'provision-tt', 'Shipper TT', '0905555555', 'da nang', null, true, CURRENT_TIMESTAMP)",
                shipperToanTinh);
        when(clients.activeShipper(shipperToanTinh)).thenReturn(true);

        // SHIPPER chuyên quận 'hai chau' đang có 1 đơn
        handoff("ASSIGNED");

        // Shipper toàn tỉnh có 0 đơn (rảnh hơn)
        // Khi đơn chỉ định quận 'hai chau', candidates phải ưu tiên shipper chuyên quận
        var candidates = repo.candidates("da nang", "hai chau");
        assertThat(candidates).startsWith(SHIPPER);

        var snapshot2 = (tools.jackson.databind.node.ObjectNode) order("COD", false);
        snapshot2.put("id", "order-hai-chau");
        snapshot2.put("district", "Hải Châu");
        when(clients.order(eq("order-hai-chau"), anyBoolean())).thenReturn(snapshot2);

        var result = call(
                post("/api/delivery/seller/orders/{id}/handoff", "order-hai-chau"),
                SELLER,
                "SELLER",
                null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andReturn();

        String id2 = json.readTree(result.getResponse().getContentAsString()).path("id").asString();
        var assignment2 = repo.find(id2, false);
        // Ưu tiên SHIPPER chuyên quận 'hai chau' dù shipper toàn tỉnh đang có 0 đơn
        assertThat(assignment2.getShipperId()).isEqualTo(SHIPPER);
    }

    // ======================= Review PR 68 lan 2: nghiep vu giao hang da chot =======================

    private String twoShopHandoff() throws Exception {
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("COD", true));
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk());
        when(clients.shop(anyString()))
                .thenReturn(
                        json.readTree(
                                """
{"id":"shop-2","ownerId":"seller-2","status":"ACTIVE","name":"Shop 2","phone":"0901111222",
 "province":"Đà Nẵng","ward":"Phường Thanh Khê","streetAddress":"9 Lê Duẩn",
 "bankAccountNumber":"123456789","bankName":"VCB","email":"shop2@example.com"}
"""));
        var result =
                call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), "seller-2", "SELLER", null)
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions pickup(String id, String shop)
            throws Exception {
        return call(
                post("/api/delivery/shipper/assignments/{id}/pickups/{shop}", id, shop),
                SHIPPER,
                "SHIPPER",
                null);
    }

    private org.springframework.test.web.servlet.ResultActions fail(String id, String body)
            throws Exception {
        return call(
                put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", body);
    }

    @Test
    void pickupSnapshotKeepsOnlyNamePhoneAndAddress() throws Exception {
        String id = twoShopHandoff();
        String stored =
                jdbc.queryForObject(
                        "select pickup_snapshot from delivery_handoffs where assignment_id=? and shop_id='shop-2'",
                        String.class,
                        id);
        assertThat(stored)
                .contains("Shop 2", "0901111222", "9 Lê Duẩn")
                .doesNotContain("bankAccountNumber", "123456789", "email", "ownerId", "seller-2");
    }

    @Test
    void pickupIsConfirmedPerShopAndOnlyAllShopsMovePickedUp() throws Exception {
        String id = twoShopHandoff();
        assertThat(repo.find(id, false).getStatus()).isEqualTo(DeliveryStatus.ASSIGNED);
        // Khong con PUT PICKED_UP thang.
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"PICKED_UP\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PICKUP_PER_SHOP"));
        call(get("/api/delivery/shipper/assignments?scope=active"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$[0].pickups.length()").value(2))
                .andExpect(jsonPath("$[0].pickups[1].name").value("Shop 2"))
                .andExpect(jsonPath("$[0].pickups[1].phone").value("0901111222"))
                .andExpect(jsonPath("$[0].pickups[1].seller_id").doesNotExist())
                .andExpect(jsonPath("$[0].recipient.name").value("Buyer"))
                .andExpect(jsonPath("$[0].recipient.phone").value("0912345678"));
        pickup(id, "shop-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.pickups[0].picked_up_at").exists())
                .andExpect(jsonPath("$.pickups[1].picked_up_at").value(nullValue()));
        pickup(id, "shop-2").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
        pickup(id, "shop-x").andExpect(status().isNotFound());
    }

    @Test
    void failureNeedsAStandardReasonAndOtherNeedsANote() throws Exception {
        String id = handoff();
        inTransit(id);
        String version = String.valueOf(repo.find(id, false).getVersion());
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"NOPE\",\"expectedVersion\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FAILURE_REASON_REQUIRED"));
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"OTHER\",\"expectedVersion\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FAILURE_NOTE_REQUIRED"));
        fail(
                        id,
                        "{\"status\":\"FAILED\",\"reasonCode\":\"CUSTOMER_UNREACHABLE\","
                                + "\"reason\":\"gọi 3 lần không nghe\",\"expectedVersion\":" + version + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.earliestRetryAt").exists())
                .andExpect(jsonPath("$.failures[0].public_reason").value("Không liên lạc được khách"));
        // Nguoi mua chi thay nhan cong khai, khong thay ghi chu noi bo.
        var buyer =
                call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                        .andExpect(jsonPath("$.history[-1].public_reason").value("Không liên lạc được khách"))
                        .andReturn();
        assertThat(buyer.getResponse().getContentAsString()).doesNotContain("gọi 3 lần không nghe");
        assertThat(
                        jdbc.queryForObject(
                                "select reason from delivery_status_history where status='FAILED'",
                                String.class))
                .isEqualTo("gọi 3 lần không nghe");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CUSTOMER_REFUSED", "WRONG_ADDRESS"})
    void refusalOrWrongAddressReturnsImmediately(String code) throws Exception {
        String id = handoff();
        inTransit(id);
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNING"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.codAmount").value(0))
                .andExpect(jsonPath("$.recipient").value(nullValue()));
    }

    @Test
    void retryIsBlockedForTwoHoursAfterAFailure() throws Exception {
        String id = handoff();
        inTransit(id);
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"CUSTOMER_RESCHEDULED\"}").andExpect(status().isOk());
        skipRetryWait = false;
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_TOO_EARLY"));
        skipRetryWait = true;
        call(post("/api/delivery/shipper/assignments/{id}/retry", id), SHIPPER, "SHIPPER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.attempt").value(2));
    }

    @Test
    void returnIsDroppedOffPerShopThenSellersConfirmReceipt() throws Exception {
        String id = twoShopHandoff();
        pickup(id, "shop-1").andExpect(status().isOk());
        pickup(id, "shop-2").andExpect(status().isOk());
        state(id, "IN_TRANSIT");
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"CUSTOMER_REFUSED\"}").andExpect(status().isOk());
        String returns = "/api/delivery/shipper/assignments/{id}/returns/{shop}";
        call(post(returns, id, "shop-1"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.status").value("RETURNING"));
        call(post(returns, id, "shop-2"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.status").value("RETURN_DROPPED_OFF"));
        // Het GPS khi da tra hang.
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.05,\"longitude\":108.2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_STOPPED"));
        // Seller shop-2 dang la shop hien tai cua mock; shop-1 xac nhan sau.
        call(post("/api/delivery/seller/orders/{id}/return-received", ORDER), "seller-2", "SELLER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURN_DROPPED_OFF"));
        when(clients.shop(anyString()))
                .thenReturn(json.readTree("{\"id\":\"shop-1\",\"ownerId\":\"seller-1\",\"status\":\"ACTIVE\"}"));
        call(post("/api/delivery/seller/orders/{id}/return-received", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
    }

    @Test
    void unconfirmedReturnClosesAutomaticallyAfterThreeDays() throws Exception {
        String id = handoff();
        inTransit(id);
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"WRONG_ADDRESS\"}").andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/returns/{shop}", id, "shop-1"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.status").value("RETURN_DROPPED_OFF"));
        assertThat(deliveryService.autoCloseReturns()).isZero();
        jdbc.update(
                "update delivery_assignments set dropped_off_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(4))),
                id);
        assertThat(deliveryService.autoCloseReturns()).isEqualTo(1);
        assertThat(repo.find(id, false).getStatus()).isEqualTo(DeliveryStatus.RETURNED);
        assertThat(
                        jdbc.queryForObject(
                                "select actor_id from delivery_status_history where status='RETURNED'",
                                String.class))
                .isEqualTo("system:return-auto-close");
    }

    @Test
    void buyerSeesLocationOnlyOnTheWayAndShipperContactUntilDelivered() throws Exception {
        String id = handoff();
        call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                .andExpect(jsonPath("$.assignment.shipper.phone").value("0901234567"))
                .andExpect(jsonPath("$.assignment.location").value(nullValue()));
        call(post("/api/delivery/orders/{id}/tracking-ticket", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isConflict());
        inTransit(id);
        call(
                        post("/api/delivery/shipper/assignments/{id}/location", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.05,\"longitude\":108.2}")
                .andExpect(status().isOk());
        call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                .andExpect(jsonPath("$.assignment.location.latitude").value(16.05));
        call(post("/api/delivery/orders/{id}/tracking-ticket", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isOk());
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"CUSTOMER_REFUSED\"}").andExpect(status().isOk());
        call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null)
                .andExpect(jsonPath("$.assignment.status").value("RETURNING"))
                .andExpect(jsonPath("$.assignment.location").value(nullValue()))
                .andExpect(jsonPath("$.assignment.shipper").value(nullValue()));
        call(get("/api/delivery/orders/{id}/route", ORDER), BUYER, "BUYER", null)
                .andExpect(status().isConflict());
        call(get("/api/delivery/orders/{id}/route", ORDER), "admin", "ADMIN", null)
                .andExpect(status().isOk());
    }

    @Test
    void onlyOneGpsSourcePerTripAndMobileWins() throws Exception {
        String id = handoff();
        inTransit(id);
        String path = "/api/delivery/shipper/assignments/{id}/location";
        call(post(path, id), SHIPPER, "SHIPPER", "{\"latitude\":16.05,\"longitude\":108.2,\"source\":\"MOBILE\"}")
                .andExpect(status().isOk());
        jdbc.update(
                "update delivery_assignments set location_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(15)),
                id);
        call(post(path, id), SHIPPER, "SHIPPER", "{\"latitude\":16.06,\"longitude\":108.2,\"source\":\"WEB\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GPS_SOURCE_CONFLICT"));
        // Mobile im lang qua 2 phut thi web duoc gui.
        jdbc.update(
                "update delivery_assignments set gps_source_at=?, location_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(180)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(180)),
                id);
        call(post(path, id), SHIPPER, "SHIPPER", "{\"latitude\":16.06,\"longitude\":108.2,\"source\":\"WEB\"}")
                .andExpect(status().isOk());
        jdbc.update(
                "update delivery_assignments set location_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(15)),
                id);
        // Mobile gianh lai quyen tu web.
        call(post(path, id), SHIPPER, "SHIPPER", "{\"latitude\":16.07,\"longitude\":108.2,\"source\":\"MOBILE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gpsSource").value("MOBILE"));
    }

    @Test
    void batchLocationReportsEachTrip() throws Exception {
        String id = handoff();
        inTransit(id);
        call(
                        post("/api/delivery/shipper/locations"),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.05,\"longitude\":108.2,\"source\":\"MOBILE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].assignmentId").value(id))
                .andExpect(jsonPath("$.results[0].result").value("OK"));
        call(
                        post("/api/delivery/shipper/locations"),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.05,\"longitude\":108.2,\"source\":\"MOBILE\"}")
                .andExpect(jsonPath("$.results[0].result").value("PING_TOO_FREQUENT"));
        jdbc.update("update shippers set active=false where id=?", SHIPPER);
        call(
                        post("/api/delivery/shipper/locations"),
                        SHIPPER,
                        "SHIPPER",
                        "{\"latitude\":16.05,\"longitude\":108.2}")
                .andExpect(status().isForbidden());
    }

    @Test
    void activeScopeReturnsEveryRunningTripAndFinishedIsPaged() throws Exception {
        String id = handoff();
        inTransit(id);
        call(get("/api/delivery/shipper/assignments?scope=active"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].zone").value("Hải Châu"));
        call(get("/api/delivery/shipper/assignments?scope=finished"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.length()").value(0));
        call(
                        put("/api/delivery/shipper/assignments/{id}/status", id),
                        SHIPPER,
                        "SHIPPER",
                        "{\"status\":\"DELIVERED\",\"codCollected\":125000}")
                .andExpect(status().isOk());
        call(get("/api/delivery/shipper/assignments?scope=finished"), SHIPPER, "SHIPPER", null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].completedAt").exists());
        call(get("/api/delivery/shipper/assignments?scope=bogus"), SHIPPER, "SHIPPER", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void successRateCountsOnlyShipperFaultReturns() throws Exception {
        String id = handoff();
        inTransit(id);
        fail(id, "{\"status\":\"FAILED\",\"reasonCode\":\"CUSTOMER_REFUSED\"}").andExpect(status().isOk());
        state(id, "RETURNED");
        when(clients.shipperStatuses(any())).thenReturn(Map.of(SHIPPER, "ACTIVE"));
        // Hoan vi khach tu choi: khong tinh vao shipper -> 0 lan "do loi", ti le giu 0 (chua co mau).
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].shipper_fault_returns").value(0))
                .andExpect(jsonPath("$[0].success_rate_percent").value(0));
        jdbc.update("update delivery_assignments set failure_reason_code='SHIPPER_ISSUE' where id=?", id);
        call(get("/api/delivery/admin/shippers"), "admin", "ADMIN", null)
                .andExpect(jsonPath("$[0].shipper_fault_returns").value(1));
    }

    @Test
    void adminResendsActivationEmail() throws Exception {
        call(post("/api/delivery/admin/shippers/{id}/activation-email", SHIPPER), "admin", "ADMIN", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
        verify(clients).resendActivation("admin", SHIPPER);
    }

}
