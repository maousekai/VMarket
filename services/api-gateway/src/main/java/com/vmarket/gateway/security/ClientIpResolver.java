package com.vmarket.gateway.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xác định IP client thật để làm khoá rate limit.
 *
 * <p>Mặc định dùng địa chỉ kết nối ({@code remoteAddr}). Chỉ khi kết nối đến từ một
 * proxy đáng tin (IP / CIDR / tên host trong {@code app.rate-limit.trusted-proxies})
 * thì mới đọc {@code X-Forwarded-For}: duyệt từ phải sang trái, bỏ qua các hop
 * cũng là proxy đáng tin, lấy địa chỉ đầu tiên không đáng tin. Phần bên trái của
 * header do client tự gửi nên không bao giờ được dùng khi còn hop đáng tin bên phải.
 *
 * <p>Chỉ chấp nhận IP literal trong XFF (không bao giờ tra DNS cho giá trị do client
 * gửi). Kết quả được chuẩn hoá ({@link InetAddress#getHostAddress()}) để một IPv6
 * không thể né giới hạn bằng cách viết theo nhiều dạng khác nhau.
 *
 * <p>Tên host (vd {@code frontend} trong docker-compose) được tra DNS lại định kỳ ở
 * luồng nền vì IP container đổi khi khởi động lại. Tra thất bại thì giữ kết quả cũ
 * tối đa {@value #STALE_AFTER_REFRESHES} chu kỳ rồi bỏ. Tin theo tên host nghĩa là
 * tin mọi container trả lời được tên đó — chỉ dùng trong mạng nội bộ đáng tin; môi
 * trường khác nên khai báo IP/CIDR cố định.
 */
public class ClientIpResolver {

	private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
	private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

	/** Tra DNS cho tên host cấu hình; tách ra để test thay được. */
	@FunctionalInterface
	public interface HostResolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	private final List<IpRange> staticRanges = new ArrayList<>();
	private final List<String> hostnames = new ArrayList<>();
	private final HostResolver hostResolver;
	private final LongSupplier clockMillis;
	private final long refreshMillis;

	/** Tên host không tra được quá số chu kỳ này thì thôi tin IP cũ của nó. */
	private static final int STALE_AFTER_REFRESHES = 3;

	private final Executor refreshExecutor;
	private final Map<String, List<IpRange>> resolvedHosts = new LinkedHashMap<>();
	private final Map<String, Long> lastResolvedAt = new HashMap<>();
	private volatile List<IpRange> hostRanges = List.of();
	private volatile long nextRefreshAt;
	private final AtomicBoolean refreshing = new AtomicBoolean(false);

	public ClientIpResolver(Collection<String> trustedProxies, int refreshSeconds) {
		this(trustedProxies, refreshSeconds, InetAddress::getAllByName, System::currentTimeMillis,
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "trusted-proxy-dns");
					t.setDaemon(true);
					return t;
				}));
	}

	ClientIpResolver(Collection<String> trustedProxies, int refreshSeconds, HostResolver hostResolver,
			LongSupplier clockMillis, Executor refreshExecutor) {
		this.hostResolver = hostResolver;
		this.clockMillis = clockMillis;
		this.refreshExecutor = refreshExecutor;
		this.refreshMillis = Math.max(1, refreshSeconds) * 1000L;
		for (String raw : trustedProxies) {
			String entry = raw == null ? "" : raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			IpRange range = IpRange.parse(entry);
			if (range != null) {
				staticRanges.add(range);
			} else if (entry.contains("/")) {
				throw new IllegalArgumentException("trusted-proxies: CIDR không hợp lệ: " + entry);
			} else {
				hostnames.add(entry);
			}
		}
		// Lần đầu tra đồng bộ lúc khởi động để request đầu tiên đã có danh sách tin cậy.
		if (!hostnames.isEmpty() && refreshing.compareAndSet(false, true)) {
			refreshHosts();
		}
	}

	/**
	 * Khoá bucket cho một IP client: IPv4 giữ nguyên; IPv6 gộp theo dải /64 (một
	 * client thường được cấp cả /64, nếu đếm theo /128 thì xoay địa chỉ là né được).
	 */
	public static String bucketKey(String clientIp) {
		InetAddress address = parseLiteral(clientIp);
		if (!(address instanceof Inet6Address)) {
			return clientIp;
		}
		byte[] bytes = address.getAddress();
		java.util.Arrays.fill(bytes, 8, 16, (byte) 0);
		try {
			return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
		} catch (UnknownHostException e) {
			return clientIp;
		}
	}

	/**
	 * @param remoteAddr  địa chỉ kết nối trực tiếp
	 * @param xffHeaders  mọi giá trị header {@code X-Forwarded-For}, theo thứ tự nhận
	 * @return IP client dùng làm khoá rate limit
	 */
	public String resolve(String remoteAddr, List<String> xffHeaders) {
		InetAddress remote = parseLiteral(remoteAddr);
		if (remote == null || !isTrusted(remote) || xffHeaders == null || xffHeaders.isEmpty()) {
			return remoteAddr;
		}
		List<String> hops = new ArrayList<>();
		for (String header : xffHeaders) {
			if (header == null) {
				continue;
			}
			for (String part : header.split(",")) {
				String hop = part.trim();
				if (!hop.isEmpty()) {
					hops.add(hop);
				}
			}
		}
		for (int i = hops.size() - 1; i >= 0; i--) {
			InetAddress hop = parseLiteral(hops.get(i));
			if (hop == null) {
				// Proxy đáng tin luôn ghi IP hợp lệ; gặp rác nghĩa là phần còn lại do
				// client tự đặt -> không tin, quay về địa chỉ kết nối.
				return remoteAddr;
			}
			if (!isTrusted(hop)) {
				return hop.getHostAddress();
			}
		}
		return remoteAddr;
	}

	boolean isTrusted(InetAddress address) {
		for (IpRange range : staticRanges) {
			if (range.contains(address)) {
				return true;
			}
		}
		if (!hostnames.isEmpty() && clockMillis.getAsLong() >= nextRefreshAt
				&& refreshing.compareAndSet(false, true)) {
			// Tra DNS ở luồng nền; request hiện tại dùng danh sách đang có, không phải chờ.
			try {
				refreshExecutor.execute(this::refreshHosts);
			} catch (RuntimeException e) {
				refreshing.set(false);
				log.warn("Không lên lịch tra DNS cho trusted proxy: {}", e.toString());
			}
		}
		for (IpRange range : hostRanges) {
			if (range.contains(address)) {
				return true;
			}
		}
		return false;
	}

	/** Gọi khi đã giữ cờ {@code refreshing}; luôn nhả cờ khi xong. */
	private void refreshHosts() {
		try {
			synchronized (resolvedHosts) {
				long staleMillis = refreshMillis * STALE_AFTER_REFRESHES;
				for (String host : hostnames) {
					try {
						List<IpRange> ranges = new ArrayList<>();
						for (InetAddress address : hostResolver.resolve(host)) {
							ranges.add(IpRange.single(address));
						}
						resolvedHosts.put(host, ranges);
						lastResolvedAt.put(host, clockMillis.getAsLong());
					} catch (UnknownHostException | RuntimeException e) {
						// Giữ kết quả lần trước một thời gian ngắn rồi bỏ, để IP cũ (có thể đã
						// cấp cho container khác) không được tin mãi.
						Long last = lastResolvedAt.get(host);
						if (last != null && clockMillis.getAsLong() - last > staleMillis) {
							resolvedHosts.remove(host);
							lastResolvedAt.remove(host);
							log.warn("Bỏ IP cũ của trusted proxy '{}' sau nhiều lần tra DNS thất bại", host);
						} else {
							log.warn("Không tra được DNS cho trusted proxy '{}': {}", host, e.getMessage());
						}
					}
				}
				List<IpRange> all = new ArrayList<>();
				resolvedHosts.values().forEach(all::addAll);
				hostRanges = List.copyOf(all);
			}
		} finally {
			nextRefreshAt = clockMillis.getAsLong() + refreshMillis;
			refreshing.set(false);
		}
	}

	/** Chỉ parse IP literal — không bao giờ tra DNS. Trả {@code null} nếu không hợp lệ. */
	static InetAddress parseLiteral(String value) {
		if (value == null) {
			return null;
		}
		String v = value.trim();
		if (v.startsWith("[") && v.endsWith("]")) {
			v = v.substring(1, v.length() - 1);
		}
		boolean literal = IPV4.matcher(v).matches() || (v.indexOf(':') >= 0 && IPV6.matcher(v).matches());
		if (!literal) {
			return null;
		}
		try {
			return InetAddress.getByName(v);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	/** Một địa chỉ hoặc dải CIDR. */
	static final class IpRange {
		private final byte[] network;
		private final int prefix;

		private IpRange(byte[] network, int prefix) {
			this.network = network;
			this.prefix = prefix;
		}

		static IpRange single(InetAddress address) {
			byte[] bytes = address.getAddress();
			return new IpRange(bytes, bytes.length * 8);
		}

		/** Parse {@code ip} hoặc {@code ip/prefix}; {@code null} nếu không phải IP. */
		static IpRange parse(String entry) {
			int slash = entry.indexOf('/');
			String ip = slash < 0 ? entry : entry.substring(0, slash);
			InetAddress address = parseLiteral(ip);
			if (address == null) {
				return null;
			}
			byte[] bytes = address.getAddress();
			int max = bytes.length * 8;
			int prefix = max;
			if (slash >= 0) {
				try {
					prefix = Integer.parseInt(entry.substring(slash + 1).trim());
				} catch (NumberFormatException e) {
					return null;
				}
				if (prefix < 0 || prefix > max) {
					return null;
				}
			}
			return new IpRange(bytes, prefix);
		}

		boolean contains(InetAddress address) {
			byte[] candidate = address.getAddress();
			if (candidate.length != network.length) {
				return false;
			}
			int fullBytes = prefix / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (candidate[i] != network[i]) {
					return false;
				}
			}
			int remainingBits = prefix % 8;
			if (remainingBits == 0) {
				return true;
			}
			int mask = (0xFF << (8 - remainingBits)) & 0xFF;
			return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
		}
	}
}
