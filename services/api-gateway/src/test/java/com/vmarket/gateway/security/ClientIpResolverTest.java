package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

	private static ClientIpResolver resolver(String... trusted) {
		return new ClientIpResolver(List.of(trusted), 30);
	}

	@Test
	void noTrustedProxies_alwaysUsesRemoteAddr() {
		assertThat(resolver().resolve("10.0.0.1", List.of("198.51.100.1"))).isEqualTo("10.0.0.1");
	}

	@Test
	void trustedRemote_withoutXff_usesRemoteAddr() {
		assertThat(resolver("10.0.0.1").resolve("10.0.0.1", List.of())).isEqualTo("10.0.0.1");
	}

	@Test
	void cidrMatchesWholeRange() {
		ClientIpResolver r = resolver("172.16.0.0/12");
		assertThat(r.resolve("172.31.255.254", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");
		assertThat(r.resolve("172.32.0.1", List.of("198.51.100.1"))).isEqualTo("172.32.0.1");
	}

	@Test
	void skipsChainOfTrustedProxies_rightToLeft() {
		ClientIpResolver r = resolver("10.0.0.0/8");
		// client giả "1.1.1.1", IP thật 198.51.100.7, rồi qua thêm một proxy nội bộ 10.0.0.9
		assertThat(r.resolve("10.0.0.1", List.of("1.1.1.1, 198.51.100.7", "10.0.0.9"))).isEqualTo("198.51.100.7");
	}

	@Test
	void garbageHop_fallsBackToRemoteAddr() {
		assertThat(resolver("10.0.0.1").resolve("10.0.0.1", List.of("not-an-ip"))).isEqualTo("10.0.0.1");
	}

	@Test
	void ipv6IsNormalised() {
		ClientIpResolver r = resolver("10.0.0.1");
		String a = r.resolve("10.0.0.1", List.of("2001:db8:0:0:0:0:0:1"));
		String b = r.resolve("10.0.0.1", List.of("2001:DB8::1"));
		assertThat(a).isEqualTo(b);
	}

	@Test
	void xffValuesNeverTriggerDnsLookup() {
		assertThat(ClientIpResolver.parseLiteral("example.com")).isNull();
		assertThat(ClientIpResolver.parseLiteral("999.1.1.1")).isNull();
		assertThat(ClientIpResolver.parseLiteral("[::1]")).isNotNull();
	}

	@Test
	void invalidCidr_isRejectedAtStartup() {
		assertThrows(IllegalArgumentException.class, () -> resolver("10.0.0.0/33"));
		assertThrows(IllegalArgumentException.class, () -> resolver("frontend/24"));
	}

	@Test
	void hostnameIsResolvedAndRefreshed_keepingLastGoodResultOnFailure() throws Exception {
		AtomicLong clock = new AtomicLong(0);
		AtomicInteger lookups = new AtomicInteger();
		AtomicReference<String> current = new AtomicReference<>("172.18.0.5");
		ClientIpResolver.HostResolver dns = host -> {
			lookups.incrementAndGet();
			String ip = current.get();
			if (ip == null) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(ip) };
		};
		// Executor chạy ngay để test xác định; thực tế refresh chạy ở luồng nền
		ClientIpResolver r = new ClientIpResolver(List.of("frontend"), 30, dns, clock::get, Runnable::run);

		assertThat(r.resolve("172.18.0.5", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");

		// Container frontend khởi động lại với IP mới; trước chu kỳ refresh vẫn dùng IP cũ
		current.set("172.18.0.9");
		assertThat(r.resolve("172.18.0.9", List.of("198.51.100.1"))).isEqualTo("172.18.0.9");

		clock.set(30_000);
		assertThat(r.resolve("172.18.0.9", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");
		assertThat(r.resolve("172.18.0.5", List.of("198.51.100.1"))).isEqualTo("172.18.0.5");

		// DNS lỗi -> giữ kết quả lần trước trong vài chu kỳ
		current.set(null);
		clock.set(60_000);
		assertThat(r.resolve("172.18.0.9", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");
		assertThat(lookups.get()).isEqualTo(3);

		// Lỗi kéo dài quá 3 chu kỳ kể từ lần tra thành công -> thôi tin IP cũ
		clock.set(130_000);
		r.resolve("172.18.0.9", List.of("198.51.100.1"));
		assertThat(r.resolve("172.18.0.9", List.of("198.51.100.1"))).isEqualTo("172.18.0.9");
	}

	@Test
	void slowDnsDoesNotBlockRequestThread() {
		AtomicLong clock = new AtomicLong(0);
		List<Runnable> queued = new java.util.ArrayList<>();
		ClientIpResolver r = new ClientIpResolver(List.of("frontend"), 30,
				host -> new InetAddress[] { InetAddress.getByName("172.18.0.5") }, clock::get, queued::add);

		// Lần tra đầu cũng chạy nền: constructor chỉ xếp hàng, không tự tra DNS
		assertThat(queued).hasSize(1);
		queued.remove(0).run();

		clock.set(30_000);
		// Refresh được đẩy sang executor, request vẫn trả kết quả ngay với danh sách hiện có
		assertThat(r.resolve("172.18.0.5", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");
		assertThat(queued).hasSize(1);
		// Đang có một refresh chờ chạy thì không xếp thêm
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(queued).hasSize(1);
	}

	@Test
	void unresolvedHostname_isRetriedWithBackoff_notAfterFullRefreshPeriod() throws Exception {
		AtomicLong clock = new AtomicLong(0);
		AtomicInteger lookups = new AtomicInteger();
		AtomicReference<String> current = new AtomicReference<>(null);
		ClientIpResolver.HostResolver dns = host -> {
			lookups.incrementAndGet();
			String ip = current.get();
			if (ip == null) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(ip) };
		};
		// frontend khởi động sau gateway: lần tra đầu thất bại
		ClientIpResolver r = new ClientIpResolver(List.of("frontend"), 30, dns, clock::get, Runnable::run);
		assertThat(lookups.get()).isEqualTo(1);

		// Thử lại sau 1s, rồi 2s
		clock.set(999);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(1);
		clock.set(1_000);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(2);
		clock.set(2_999);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(2);

		// frontend đã lên: lần thử sau 2s tra được, XFF được tin ngay
		current.set("172.18.0.5");
		clock.set(3_000);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(3);
		assertThat(r.resolve("172.18.0.5", List.of("198.51.100.1"))).isEqualTo("198.51.100.1");

		// Đủ tên host rồi thì quay về chu kỳ refresh đầy đủ
		clock.set(32_999);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(3);
		clock.set(33_000);
		r.resolve("172.18.0.5", List.of("198.51.100.1"));
		assertThat(lookups.get()).isEqualTo(4);
	}

	@Test
	void ipv4MappedIpv6_sharesBucketWithPlainIpv4() {
		assertThat(ClientIpResolver.bucketKey("::ffff:198.51.100.1")).isEqualTo("198.51.100.1");
		// Không tin XFF thì vẫn trả địa chỉ kết nối ở dạng chuẩn
		assertThat(resolver().resolve("::ffff:198.51.100.1", List.of())).isEqualTo("198.51.100.1");
	}

	@Test
	void ipv6BucketKeyGroupsBySlash64() {
		assertThat(ClientIpResolver.bucketKey("2001:db8:1:2:aaaa::1"))
				.isEqualTo(ClientIpResolver.bucketKey("2001:db8:1:2:bbbb::9"))
				.endsWith("/64");
		assertThat(ClientIpResolver.bucketKey("2001:db8:1:3::1"))
				.isNotEqualTo(ClientIpResolver.bucketKey("2001:db8:1:2::1"));
		assertThat(ClientIpResolver.bucketKey("198.51.100.1")).isEqualTo("198.51.100.1");
	}
}
