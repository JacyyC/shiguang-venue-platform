package com.example.venue.payment.provider.alipay;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class AlipayBillDownloader {
    private static final String DOWNLOAD_REJECTED = "ALIPAY_BILL_DOWNLOAD_REJECTED";
    private static final int MAX_ARCHIVE_BYTES = 10 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final AddressResolver addressResolver;
    private final DownloadTransport transport;
    private final Duration bodyReadTimeout;

    public AlipayBillDownloader() {
        this(newHttpClient(),
            host -> Arrays.asList(InetAddress.getAllByName(host)),
            null,
            BODY_READ_TIMEOUT);
    }

    private AlipayBillDownloader(HttpClient httpClient, AddressResolver addressResolver, DownloadTransport transport,
                                 Duration bodyReadTimeout) {
        this.httpClient = httpClient;
        this.addressResolver = addressResolver;
        this.transport = transport == null ? new JdkDownloadTransport(httpClient) : transport;
        this.bodyReadTimeout = bodyReadTimeout;
    }

    static AlipayBillDownloader forTesting(AddressResolver addressResolver, DownloadTransport transport) {
        return forTesting(addressResolver, transport, BODY_READ_TIMEOUT);
    }

    static AlipayBillDownloader forTesting(AddressResolver addressResolver, DownloadTransport transport,
                                           Duration bodyReadTimeout) {
        return new AlipayBillDownloader(newHttpClient(), addressResolver, transport, bodyReadTimeout);
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public byte[] download(URI uri) {
        validateUri(uri);
        validateResolvedAddresses(uri.getHost());
        try (DownloadResponse response = transport.fetch(uri)) {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300
                || response.contentLength() > MAX_ARCHIVE_BYTES || response.body() == null) {
                throw rejected();
            }
            return readWithDeadline(response.body());
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw rejected();
        } catch (IOException | RuntimeException ex) {
            throw rejected();
        }
    }

    private void validateUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || uri.getHost().isBlank() || uri.getUserInfo() != null || uri.getFragment() != null
            || (uri.getPort() != -1 && uri.getPort() != 443) || !isApprovedHost(uri.getHost())) {
            throw rejected();
        }
    }

    private boolean isApprovedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("alipay.com") || normalized.endsWith(".alipay.com")
            || normalized.equals("alipayobjects.com") || normalized.endsWith(".alipayobjects.com");
    }

    private void validateResolvedAddresses(String host) {
        try {
            List<InetAddress> addresses = addressResolver.resolve(host);
            if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublicAddress(address))) {
                throw rejected();
            }
        } catch (AlipaySandboxException ex) {
            throw ex;
        } catch (UnknownHostException | RuntimeException ex) {
            throw rejected();
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(bytes);
        }
        return address instanceof Inet6Address && isPublicIpv6(bytes);
    }

    private boolean isPublicIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 192 && second == 0)
            || (first == 192 && second == 88 && third == 99)
            || (first == 198 && (second == 18 || second == 19))
            || (first == 192 && second == 0 && third == 2)
            || (first == 198 && second == 51 && third == 100)
            || (first == 203 && second == 0 && third == 113)) {
            return false;
        }
        return true;
    }

    private boolean isPublicIpv6(byte[] bytes) {
        if (isIpv4Mapped(bytes) || isIpv4Compatible(bytes) || !isGlobalUnicastIpv6(bytes)) {
            return false;
        }
        return !isIanaSpecialIpv6(bytes);
    }

    private boolean isGlobalUnicastIpv6(byte[] bytes) {
        return (unsigned(bytes[0]) & 0xe0) == 0x20;
    }

    private boolean isIanaSpecialIpv6(byte[] bytes) {
        return hasIpv6Prefix(bytes, 23, 0x2001, 0x0000)
            || hasIpv6Prefix(bytes, 32, 0x2001, 0x0db8)
            || hasIpv6Prefix(bytes, 16, 0x2002)
            || hasIpv6Prefix(bytes, 20, 0x3fff)
            || hasIpv6Prefix(bytes, 48, 0x2620, 0x004f, 0x8000);
    }

    private boolean hasIpv6Prefix(byte[] address, int prefixBits, int... groups) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int index = 0; index < fullBytes; index++) {
            int expected = prefixByte(groups, index);
            if (unsigned(address[index]) != expected) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int expected = prefixByte(groups, fullBytes);
        int mask = 0xff << (8 - remainingBits);
        return (unsigned(address[fullBytes]) & mask) == (expected & mask);
    }

    private int prefixByte(int[] groups, int byteIndex) {
        int groupIndex = byteIndex / 2;
        if (groupIndex >= groups.length) {
            return 0;
        }
        return (groups[groupIndex] >>> ((byteIndex % 2 == 0) ? 8 : 0)) & 0xff;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private boolean isIpv4Compatible(byte[] bytes) {
        for (int index = 0; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            if (read > MAX_ARCHIVE_BYTES - total) {
                throw rejected();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private byte[] readWithDeadline(InputStream input) throws IOException, InterruptedException {
        FutureTask<byte[]> reader = new FutureTask<>(() -> readBounded(input));
        Thread readerThread = new Thread(reader, "alipay-bill-body-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            return reader.get(Math.max(1L, bodyReadTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            closeQuietly(input);
            reader.cancel(true);
            throw rejected();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof AlipaySandboxException sandboxException) {
                throw sandboxException;
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw rejected();
        } catch (InterruptedException ex) {
            closeQuietly(input);
            reader.cancel(true);
            throw ex;
        } finally {
            if (!reader.isDone()) {
                closeQuietly(input);
                reader.cancel(true);
            }
        }
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable rejection code is more useful than a body-close implementation detail.
        }
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private AlipaySandboxException rejected() {
        return AlipaySandboxException.config(DOWNLOAD_REJECTED);
    }

    @FunctionalInterface
    interface AddressResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    interface DownloadTransport {
        DownloadResponse fetch(URI approvedUri) throws IOException, InterruptedException;
    }

    record DownloadResponse(int statusCode, long contentLength, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }

    private static final class JdkDownloadTransport implements DownloadTransport {
        private final HttpClient client;

        private JdkDownloadTransport(HttpClient client) {
            this.client = client;
        }

        @Override
        public DownloadResponse fetch(URI approvedUri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(approvedUri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new DownloadResponse(response.statusCode(), contentLength, response.body());
        }
    }
}
