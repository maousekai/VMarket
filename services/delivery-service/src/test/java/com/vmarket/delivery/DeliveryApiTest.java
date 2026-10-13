package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.net.URI;
import java.net.http.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryOutbox;
import com.vmarket.delivery.tracking.*;
import com.vmarket.events.EventPublisher;
import tools.jackson.databind.*;

/** Real HTTP security, Flyway/H2 transactions and WebSocket transport; external services and Redis boundary mocked. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DeliveryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryRepository repo;
    @Autowired TrackingSocket socket;
    @Autowired com.vmarket.delivery.config.DeliveryProperties properties;
    @LocalServerPort int port;
    @MockitoBean DeliveryClients clients;
    @MockitoBean StringRedisTemplate redis;
    private static final String SECRET="test-only-secret-0123456789-0123456789-0123456789";
    private static final String ORDER="order-1", SHOP="shop-1", SHIPPER="shipper-1", BUYER="buyer-1", SELLER="seller-1";
    private final Map<String,String> ticketValues = new ConcurrentHashMap<>();

    @BeforeEach void seed() {
        properties.setWebSocketUrl("ws://localhost:"+port+"/api/delivery/ws");
        for (String table : List.of("delivery_outbox", "route_logs", "delivery_status_history", "delivery_handoffs", "delivery_assignments", "shippers")) jdbc.update("delete from " + table);
        jdbc.update("insert into shippers values (?, ?, 'Shipper', '0901234567', 'đà nẵng', 'hải châu', true, CURRENT_TIMESTAMP)", SHIPPER, "provision-1");
        when(clients.activeShipper(anyString())).thenReturn(true);
        when(clients.shop(anyString())).thenReturn(json.readTree("""
            {"id":"shop-1","ownerId":"seller-1","status":"ACTIVE","province":"Đà Nẵng","district":"Hải Châu","streetAddress":"1 đường lấy hàng"}
            """));
        when(clients.order(anyString(), anyBoolean())).thenReturn(order("COD", false));
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(inv -> { ticketValues.put(inv.getArgument(0), inv.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(values.getAndDelete(anyString())).thenAnswer(inv -> ticketValues.remove(inv.getArgument(0)));
    }
    private JsonNode order(String method, boolean twoShops) {
        return json.readTree("""
            {"id":"order-1","userId":"buyer-1","status":"PROCESSING","stockReserved":true,
             "paymentMethod":"METHOD","totalAmount":125000,"recipientName":"Buyer","phone":"0912345678",
             "province":"Đà Nẵng","district":"Hải Châu","ward":"Phường 1","streetAddress":"2 đường giao hàng",
             "items":[{"shopId":"shop-1"}EXTRA]}
            """.replace("METHOD", method).replace("EXTRA", twoShops ? ",{\"shopId\":\"shop-2\"}" : ""));
    }
    private String token(String user, String role) {
        return Jwts.builder().subject(user).issuer("auth-service").claim("roles", List.of(role))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }
    private ResultActions call(MockHttpServletRequestBuilder req, String user, String role, String body) throws Exception {
        req.header("Authorization", "Bearer " + token(user, role));
        String uri = req.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        var matcher = java.util.regex.Pattern.compile("/assignments/([^/]+)/(status|assign|retry)$").matcher(uri);
        if (matcher.find()) {
            var node = (tools.jackson.databind.node.ObjectNode)json.readTree(body == null ? "{}" : body);
            if (!node.has("expectedVersion")) node.put("expectedVersion", repo.find(matcher.group(1),false).getVersion());
            body = json.writeValueAsString(node);
        }
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }
    private String handoff() throws Exception {
        var result = call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asString();
    }
    private void state(String id, String next) throws Exception {
        call(put("/api/delivery/shipper/assignments/{id}/status", id), SHIPPER, "SHIPPER", "{\"status\":\""+next+"\"}").andExpect(status().isOk());
    }
    private void inTransit(String id) throws Exception { state(id, "PICKED_UP"); state(id, "IN_TRANSIT"); }

    @Test void authorizationAndTrustedHandoff() throws Exception {
        mvc.perform(get("/api/delivery/shipper/assignments")).andExpect(status().isUnauthorized());
        call(get("/api/delivery/admin/shippers"), BUYER, "BUYER", null).andExpect(status().isForbidden());
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), "other-seller", "SELLER", null).andExpect(status().isForbidden());
        verify(clients, never()).order(anyString(), eq(true));
        String id = handoff();
        assertThat(repo.find(id,false).getBuyerId()).isEqualTo(BUYER);
        call(get("/api/delivery/orders/{id}", ORDER), "other-buyer", "BUYER", null).andExpect(status().isForbidden());
        call(get("/api/delivery/orders/{id}", ORDER), BUYER, "BUYER", null).andExpect(status().isOk()).andExpect(jsonPath("$.assignment.codAmount").value(125000));
        call(put("/api/delivery/shipper/assignments/{id}/status", id), "other-shipper", "SHIPPER", "{\"status\":\"PICKED_UP\"}").andExpect(status().isForbidden());
        call(post("/api/delivery/seller/orders/{id}/handoff", ORDER), SELLER, "SELLER", null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from delivery_handoffs", Integer.class)).isEqualTo(1);
    }
    @Test void everyShopMustHandoffAndInactiveOrWrongAreaCannotBeAssigned() throws Exception {
        when(clients.order(anyString(),anyBoolean())).thenReturn(order("COD",true));
        call(post("/api/delivery/seller/orders/{id}/handoff",ORDER), SELLER,"SELLER",null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_HANDOFF"));
        String id=repo.findOrder(ORDER,false).orElseThrow().getId();
        call(post("/api/delivery/admin/assignments/{id}/assign",id),"admin","ADMIN","{\"shipperId\":\"shipper-1\"}").andExpect(status().isConflict());
        when(clients.shop(anyString())).thenReturn(json.readTree("{\"id\":\"shop-2\",\"ownerId\":\"seller-2\",\"status\":\"ACTIVE\"}"));
        when(clients.activeShipper(SHIPPER)).thenReturn(false);
        call(post("/api/delivery/seller/orders/{id}/handoff",ORDER),"seller-2","SELLER",null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_ASSIGNMENT"));
        call(post("/api/delivery/admin/assignments/{id}/assign",id),"admin","ADMIN","{\"shipperId\":\"shipper-1\"}").andExpect(status().isConflict());
        when(clients.activeShipper(SHIPPER)).thenReturn(true);
        jdbc.update("update shippers set district='khác' where id=?", SHIPPER);
        call(post("/api/delivery/admin/assignments/{id}/assign",id),"admin","ADMIN","{\"shipperId\":\"shipper-1\"}").andExpect(status().isConflict());
        jdbc.update("update shippers set district='hải châu' where id=?", SHIPPER);
        call(post("/api/delivery/admin/assignments/{id}/assign",id),"admin","ADMIN","{\"shipperId\":\"shipper-1\"}").andExpect(status().isOk());
    }
    @Test void codMustMatchAndDuplicateCompletionCannotCollectTwice() throws Exception {
        String id=handoff();
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER","{\"status\":\"DELIVERED\",\"codCollected\":125000}").andExpect(status().isConflict());
        inTransit(id);
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER","{\"status\":\"DELIVERED\",\"codCollected\":1}").andExpect(status().isBadRequest());
        String body="{\"status\":\"DELIVERED\",\"codCollected\":125000}";
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER",body).andExpect(status().isOk());
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER",body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='CodCollected'",Integer.class)).isEqualTo(1);
        var payload=json.readTree(jdbc.queryForObject("select payload from delivery_outbox where event_type='CodCollected'",String.class));
        assertThat(payload.path("buyerId").asString()).isEqualTo(BUYER);
        assertThat(payload.path("amount").asLong()).isEqualTo(125000);
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER","{\"latitude\":16.0,\"longitude\":108.0}").andExpect(status().isConflict());
    }
    @Test void prepaidCannotCollectCodAndThirdFailureReturnsOrder() throws Exception {
        when(clients.order(anyString(),anyBoolean())).thenReturn(order("PAYOS",false));
        String id=handoff(); inTransit(id);
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER","{\"status\":\"DELIVERED\",\"codCollected\":125000}").andExpect(status().isBadRequest());
        for(int i=1;i<=3;i++) {
            call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER","{\"status\":\"FAILED\"}").andExpect(status().isBadRequest());
            call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER","{\"status\":\"FAILED\",\"reason\":\"Không liên hệ được\"}").andExpect(status().isOk());
            if(i<3) call(post("/api/delivery/shipper/assignments/{id}/retry",id),SHIPPER,"SHIPPER",null).andExpect(status().isOk());
        }
        assertThat(repo.find(id,false).getStatus().name()).isEqualTo("RETURNING");
        call(post("/api/delivery/shipper/assignments/{id}/retry",id),SHIPPER,"SHIPPER",null).andExpect(status().isConflict());
        state(id,"RETURNED");
        assertThat(jdbc.queryForObject("select count(*) from delivery_status_history where status='FAILED'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='CodCollected'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select payload from delivery_outbox where event_type='DeliveryStatusChanged'",String.class)).anyMatch(body -> body.contains("RETURNED"));
    }
    @Test void gpsBoundsFrequencyAndLocksAndPagination() throws Exception {
        String id=handoff(); inTransit(id);
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER","{\"latitude\":91,\"longitude\":108}").andExpect(status().isBadRequest());
        String point="{\"latitude\":16.04,\"longitude\":108.21}";
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER",point).andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER",point).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("select count(*) from route_logs",Integer.class)).isEqualTo(1);
        call(get("/api/delivery/orders/{id}/route",ORDER),BUYER,"BUYER",null).andExpect(status().isOk()).andExpect(jsonPath("$[0].latitude").value(16.04));
        call(get("/api/delivery/shipper/assignments?size=101"),SHIPPER,"SHIPPER",null).andExpect(status().isBadRequest());
        call(put("/api/delivery/admin/shippers/{id}/lock",SHIPPER),"admin","ADMIN","{\"locked\":true}").andExpect(status().isOk());
        verify(clients).lock("admin",SHIPPER,true);
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER",point).andExpect(status().isForbidden());
    }
    @Test void delayedFailureReplayCannotCountAsAnotherDeliveryAttempt() throws Exception {
        String id=handoff(); inTransit(id);
        long version=repo.find(id,false).getVersion();
        String failure="{\"status\":\"FAILED\",\"reason\":\"No answer\",\"expectedVersion\":"+version+"}";
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER",failure).andExpect(status().isOk());
        call(post("/api/delivery/shipper/assignments/{id}/retry",id),SHIPPER,"SHIPPER",null).andExpect(status().isOk());
        call(put("/api/delivery/shipper/assignments/{id}/status",id),SHIPPER,"SHIPPER",failure).andExpect(status().isConflict());
        assertThat(repo.find(id,false).getStatus().name()).isEqualTo("IN_TRANSIT");
        assertThat(repo.find(id,false).getAttempt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from delivery_status_history where status='FAILED'",Integer.class)).isEqualTo(1);
    }
    @Test void deniedAdminCannotMutateAndUnreservedCannotHandoff() throws Exception {
        doThrow(new com.vmarket.delivery.exception.ApiException("FORBIDDEN",org.springframework.http.HttpStatus.FORBIDDEN,"Revoked")).when(clients).admin("admin");
        call(put("/api/delivery/admin/shippers/{id}/lock",SHIPPER),"admin","ADMIN","{\"locked\":true}").andExpect(status().isForbidden());
        verify(clients,never()).lock(anyString(),anyString(),anyBoolean());
        var snapshot=(tools.jackson.databind.node.ObjectNode) order("COD",false); snapshot.put("stockReserved",false);
        when(clients.order(anyString(),anyBoolean())).thenReturn(snapshot);
        call(post("/api/delivery/seller/orders/{id}/handoff",ORDER),SELLER,"SELLER",null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from delivery_assignments",Integer.class)).isZero();
    }
    @Test void outboxKeepsFailedEventsAndRetriesWithoutLostDomainState() throws Exception {
        String id=handoff();
        EventPublisher publisher=mock(EventPublisher.class); RedisTracking tracking=mock(RedisTracking.class);
        var outbox=new DeliveryOutbox(jdbc,json,publisher,tracking,Clock.systemUTC());
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyString(),any());
        outbox.events();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='DeliveryAssigned'",Integer.class)).isEqualTo(1);
        assertThat(repo.find(id,false).getShipperId()).isEqualTo(SHIPPER);
        reset(publisher);
        jdbc.update("update delivery_outbox set next_attempt_at=?",java.sql.Timestamp.from(Instant.EPOCH));
        outbox.events(); outbox.tracking();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox",Integer.class)).isZero();
        verify(publisher).publish(eq("DeliveryAssigned"),any()); verify(tracking).publish(anyString());
    }
    @Test void missingNotificationRouteDoesNotBlockOrderStatusEvents() throws Exception {
        String id=handoff(); inTransit(id);
        EventPublisher publisher=mock(EventPublisher.class);
        doThrow(new IllegalStateException("No notification binding")).when(publisher).publish(eq("DeliveryAssigned"),any());
        new DeliveryOutbox(jdbc,json,publisher,mock(RedisTracking.class),Clock.systemUTC()).events();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='DeliveryAssigned'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='DeliveryStatusChanged'",Integer.class)).isZero();
        verify(publisher,times(2)).publish(eq("DeliveryStatusChanged"),any());
    }
    @Test void fullBatchOfUnroutableAssignmentsCannotStarveLaterStatusOrCod() throws Exception {
        String id=handoff(); inTransit(id);
        jdbc.update("delete from delivery_outbox");
        var assignment=repo.find(id,false);
        for (int i=0; i<100; i++) repo.outbox(assignment,"DeliveryAssigned","{}");
        jdbc.update("update delivery_outbox set created_at=?",java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        repo.outbox(assignment,"DeliveryStatusChanged",json.writeValueAsString(new com.vmarket.events.DeliveryStatusChanged(ORDER,"DELIVERED",10)));
        repo.outbox(assignment,"CodCollected",json.writeValueAsString(new com.vmarket.events.CodCollected(ORDER,BUYER,100000L,id)));
        EventPublisher publisher=mock(EventPublisher.class);
        doThrow(new IllegalStateException("No notification binding")).when(publisher).publish(eq("DeliveryAssigned"),any());
        var outbox=new DeliveryOutbox(jdbc,json,publisher,mock(RedisTracking.class),Clock.systemUTC());
        for (int i=0; i<3; i++) outbox.events();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type in ('DeliveryStatusChanged','CodCollected')",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where event_type='DeliveryAssigned'",Integer.class)).isEqualTo(100);
        verify(publisher).publish(eq("DeliveryStatusChanged"),any());
        verify(publisher).publish(eq("CodCollected"),any());
        verify(publisher,times(100)).publish(eq("DeliveryAssigned"),any());
    }
    @Test void retryDeadlineSurvivesDispatcherRestartAndBackoffIsBounded() throws Exception {
        String id=handoff();
        jdbc.update("delete from delivery_outbox");
        repo.outbox(repo.find(id,false),"DeliveryAssigned","{}");
        Instant now=Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        EventPublisher publisher=mock(EventPublisher.class);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyString(),any());
        long[] delays={5,10,20,40,80,160,300,300,300};
        for (int attempt=0; attempt<delays.length; attempt++) {
            Clock due=Clock.fixed(now,ZoneOffset.UTC);
            new DeliveryOutbox(jdbc,json,publisher,mock(RedisTracking.class),due).events();
            long wait=delays[attempt];
            Instant retryAt=jdbc.queryForObject("select next_attempt_at from delivery_outbox",java.time.OffsetDateTime.class).toInstant();
            assertThat(retryAt).isEqualTo(now.plusSeconds(wait));
            assertThat(jdbc.queryForObject("select retry_attempts from delivery_outbox",Integer.class)).isEqualTo(attempt+1);
            // A new dispatcher must honor the persisted deadline, even after a restart.
            new DeliveryOutbox(jdbc,json,publisher,mock(RedisTracking.class),Clock.fixed(retryAt.minusMillis(1),ZoneOffset.UTC)).events();
            verify(publisher,times(attempt+1)).publish(eq("DeliveryAssigned"),any());
            now=retryAt;
        }
        reset(publisher);
        new DeliveryOutbox(jdbc,json,publisher,mock(RedisTracking.class),Clock.fixed(now,ZoneOffset.UTC)).events();
        verify(publisher).publish(eq("DeliveryAssigned"),any());
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox",Integer.class)).isZero();
    }
    @Test void actualWebSocketIsScopedSingleUseAndDeliversGpsUnderFiveSeconds() throws Exception {
        String id=handoff(); inTransit(id);
        call(post("/api/delivery/orders/{id}/tracking-ticket",ORDER),"other-buyer","BUYER",null).andExpect(status().isForbidden());
        var ticketResponse=json.readTree(call(post("/api/delivery/orders/{id}/tracking-ticket",ORDER),BUYER,"BUYER",null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String ticket=ticketResponse.path("ticket").asString();
        var messages=new LinkedBlockingQueue<String>();
        var listener=new WebSocket.Listener() {
            private final StringBuilder body=new StringBuilder();
            @Override public void onOpen(WebSocket ws) { ws.request(1); }
            @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
                body.append(data); if(last) { messages.add(body.toString()); body.setLength(0); } ws.request(1); return null;
            }
        };
        URI uri=URI.create(ticketResponse.path("webSocketUrl").asString());
        WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","http://localhost:5173").buildAsync(uri,listener).get(5,TimeUnit.SECONDS);
        assertThat(messages.poll(5,TimeUnit.SECONDS)).contains("IN_TRANSIT");
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri,listener).get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        long start=System.nanoTime();
        call(post("/api/delivery/shipper/assignments/{id}/location",id),SHIPPER,"SHIPPER","{\"latitude\":16.04,\"longitude\":108.21}").andExpect(status().isOk());
        // Mock Redis pub/sub boundary while using the real outbox and actual socket transport.
        RedisTracking tracking=mock(RedisTracking.class);
        doAnswer(inv -> { socket.accept(inv.getArgument(0)); return null; }).when(tracking).publish(anyString());
        new DeliveryOutbox(jdbc,json,mock(EventPublisher.class),tracking,Clock.systemUTC()).tracking();
        String update=messages.poll(5,TimeUnit.SECONDS);
        assertThat(update).contains("16.04");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(5000);
        ws.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
    }
}
