package com.vmarket.payment.client;
import java.math.BigDecimal;
import java.time.Instant;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.vmarket.payment.exception.ApiException;
@Component
public class OrderClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;
    public OrderClient(HttpClient client, ObjectMapper mapper, @Value("${app.order-service.base-url:http://localhost:8086}") String baseUrl) {
        this.client = client; this.mapper = mapper; this.baseUrl = baseUrl;
    }
    public record OrderView(String id, String status, BigDecimal totalAmount, String paymentMethod, Instant paymentExpiresAt, boolean stockReserved) {}
    public OrderView getOwn(String token, String id) {
        if (id == null || !id.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw ApiException.badRequest("INVALID_ORDER_ID", "Mã đơn không hợp lệ");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders/" + id))
                .timeout(Duration.ofSeconds(5)).header("Authorization", token).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
            if (response.statusCode() != 200) throw ApiException.unavailable("ORDER_UNAVAILABLE", "Không thể xác minh đơn hàng");
            return mapper.readValue(response.body(), OrderView.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw ApiException.unavailable("ORDER_UNAVAILABLE", "Không thể xác minh đơn hàng");
        } catch (java.io.IOException ex) {
            throw ApiException.unavailable("ORDER_UNAVAILABLE", "Không thể xác minh đơn hàng");
        }
    }
}
