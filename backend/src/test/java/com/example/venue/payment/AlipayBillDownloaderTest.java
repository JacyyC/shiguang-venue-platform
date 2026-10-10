package com.example.venue.payment.provider.alipay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipayBillDownloaderTest {
    private static final URI APPROVED_URI = URI.create("https://fixture-download.alipay.com/trade.zip");
    private static final int MAX_ARCHIVE_BYTES = 10 * 1024 * 1024;

    private HttpServer fixtureServer;

    @AfterEach
    void stopFixtureServer() {
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
    }

    @Test
    void productionConstructionUsesNeverRedirectPolicy() throws Exception {
        AlipayBillDownloader downloader = new AlipayBillDownloader();
        Field httpClient = AlipayBillDownloader.class.getDeclaredField("httpClient");
        httpClient.setAccessible(true);

        assertThat(((HttpClient) httpClient.get(downloader)).followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void downloadsFixtureOnlyAfterApprovedHttpsUriAndPublicAddressValidation() throws Exception {
        byte[] expected = "fixture-zip".getBytes(StandardCharsets.US_ASCII);
        fixtureServer = fixtureServer(expected);
        AtomicReference<URI> approvedRequest = new AtomicReference<>();
        AtomicBoolean resolverCalled = new AtomicBoolean();

        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> {
                resolverCalled.set(true);
                assertThat(host).isEqualTo("fixture-download.alipay.com");
                return List.of(publicAddress());
            },
            approvedUri -> {
                approvedRequest.set(approvedUri);
                return fixtureResponse(fixtureServer);
            });

        assertThat(downloader.download(APPROVED_URI)).isEqualTo(expected);
        assertThat(resolverCalled).isTrue();
        assertThat(approvedRequest.get()).isEqualTo(APPROVED_URI);
        assertThat(approvedRequest.get().getScheme()).isEqualTo("https");
        assertThat(approvedRequest.get().getHost()).endsWith(".alipay.com");
    }

    @Test
    void rejectsUnsafeUrisBeforeCallingResolverOrTransport() {
        AtomicBoolean resolverCalled = new AtomicBoolean();
        AtomicBoolean transportCalled = new AtomicBoolean();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> {
                resolverCalled.set(true);
                return List.of(publicAddress());
            },
            approvedUri -> {
                transportCalled.set(true);
                return response(200, 0, new byte[0]);
            });

        for (URI unsafe : List.of(
            URI.create("http://fixture-download.alipay.com/trade.zip"),
            URI.create("https://merchant@fixture-download.alipay.com/trade.zip"),
            URI.create("https://evil.alipay.example/trade.zip"),
            URI.create("https://localhost/trade.zip"),
            URI.create("https://127.0.0.1/trade.zip"))) {
            assertDownloadRejected(() -> downloader.download(unsafe));
        }

        assertThat(resolverCalled).isFalse();
        assertThat(transportCalled).isFalse();
    }

    @Test
    void rejectsLoopbackPrivateAndReservedResolvedAddressesBeforeTransport() throws Exception {
        List<byte[]> rejectedAddresses = List.of(
            new byte[]{127, 0, 0, 1},
            new byte[]{10, 1, 2, 3},
            new byte[]{(byte) 172, 16, 0, 1},
            new byte[]{(byte) 192, (byte) 168, 0, 1},
            new byte[]{(byte) 169, (byte) 254, 0, 1},
            new byte[]{(byte) 192, 0, 2, 1},
            new byte[]{(byte) 198, 51, 100, 1},
            new byte[]{(byte) 203, 0, 113, 1},
            new byte[16],
            ipv6(15, (byte) 1),
            ipv6(0, (byte) 0xfc),
            ipv6DocumentationAddress());

        for (byte[] address : rejectedAddresses) {
            AtomicBoolean transportCalled = new AtomicBoolean();
            AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
                host -> List.of(InetAddress.getByAddress(address)),
                approvedUri -> {
                    transportCalled.set(true);
                    return response(200, 0, new byte[0]);
                });

            assertDownloadRejected(() -> downloader.download(APPROVED_URI));
            assertThat(transportCalled).isFalse();
        }
    }

    @Test
    void rejectsIpv4MappedLoopbackBeforeTransport() throws Exception {
        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xff;
        mappedLoopback[11] = (byte) 0xff;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1;
        AtomicBoolean transportCalled = new AtomicBoolean();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(Inet6Address.getByAddress(null, mappedLoopback, -1)),
            approvedUri -> {
                transportCalled.set(true);
                return response(200, 0, new byte[0]);
            });

        assertDownloadRejected(() -> downloader.download(APPROVED_URI));
        assertThat(transportCalled).isFalse();
    }

    @Test
    void rejectsIpv4CompatibleLoopbackBeforeTransport() throws Exception {
        byte[] compatibleLoopback = new byte[16];
        compatibleLoopback[12] = 127;
        compatibleLoopback[15] = 1;
        AtomicBoolean transportCalled = new AtomicBoolean();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(Inet6Address.getByAddress(null, compatibleLoopback, -1)),
            approvedUri -> {
                transportCalled.set(true);
                return response(200, 0, new byte[0]);
            });

        assertDownloadRejected(() -> downloader.download(APPROVED_URI));
        assertThat(transportCalled).isFalse();
    }

    @Test
    void rejectsNonGlobalAndIanaSpecialIpv6AddressesBeforeTransport() throws Exception {
        List<byte[]> rejectedAddresses = List.of(
            ipv6Groups(0x0100, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x0064, 0xff9b, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x2001, 0x0002, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x2002, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x3fff, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x5f00, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0x2620, 0x004f, 0x8000, 0, 0, 0, 0, 1),
            ipv6Groups(0xfc00, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0xfe80, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0, 0, 0, 0, 0, 0, 0, 1),
            ipv6Groups(0xff00, 0, 0, 0, 0, 0, 0, 1));

        for (byte[] address : rejectedAddresses) {
            AtomicBoolean transportCalled = new AtomicBoolean();
            AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
                host -> List.of(Inet6Address.getByAddress(null, address, -1)),
                approvedUri -> {
                    transportCalled.set(true);
                    return response(200, 0, new byte[0]);
                });

            assertDownloadRejected(() -> downloader.download(APPROVED_URI));
            assertThat(transportCalled).isFalse();
        }
    }

    @Test
    void permitsGlobalUnicastIpv6AddressesAfterPolicyValidation() throws Exception {
        AtomicBoolean transportCalled = new AtomicBoolean();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(Inet6Address.getByAddress(null,
                ipv6Groups(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888), -1)),
            approvedUri -> {
                transportCalled.set(true);
                return response(200, 0, new byte[0]);
            });

        assertThat(downloader.download(APPROVED_URI)).isEmpty();
        assertThat(transportCalled).isTrue();
    }

    @Test
    void matchesPartialByteIpv6PrefixesWithoutReadingPastProvidedGroups() throws Exception {
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(publicAddress()), approvedUri -> response(200, 0, new byte[0]));
        Method hasPrefix = AlipayBillDownloader.class
            .getDeclaredMethod("hasIpv6Prefix", byte[].class, int.class, int[].class);
        hasPrefix.setAccessible(true);

        assertThat(hasPrefix.invoke(downloader, ipv6Groups(0x3fff, 0, 0, 0, 0, 0, 0, 1), 20,
            new int[]{0x3fff})).isEqualTo(true);
    }

    @Test
    void rejectsBlockedBodiesAtTheConfiguredReadDeadlineAndClosesThem() throws Exception {
        BlockingInputStream body = new BlockingInputStream();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(publicAddress()),
            approvedUri -> new AlipayBillDownloader.DownloadResponse(200, -1, body),
            Duration.ofMillis(100));

        assertDownloadRejected(() -> downloader.download(APPROVED_URI));

        assertThat(body.awaitReadStarted()).isTrue();
        assertThat(body.isClosed()).isTrue();
    }

    @Test
    void restoresCallerInterruptedStatusWhenTheReadWaitIsInterrupted() throws Exception {
        BlockingInputStream body = new BlockingInputStream();
        AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
            host -> List.of(publicAddress()),
            approvedUri -> new AlipayBillDownloader.DownloadResponse(200, -1, body),
            Duration.ofSeconds(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                downloader.download(APPROVED_URI);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.setDaemon(true);

        caller.start();
        assertThat(body.awaitReadStarted()).isTrue();
        caller.interrupt();
        caller.join(1_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(AlipaySandboxException.class);
        assertThat(((AlipaySandboxException) failure.get()).getCode()).isEqualTo("ALIPAY_BILL_DOWNLOAD_REJECTED");
        assertThat(interrupted).isTrue();
        assertThat(body.isClosed()).isTrue();
    }

    @Test
    void rejectsRedirectAndOversizedResponsesAfterPolicyValidation() throws Exception {
        for (AlipayBillDownloader.DownloadResponse response : List.of(
            response(302, 0, new byte[0]),
            response(200, MAX_ARCHIVE_BYTES + 1L, new byte[0]),
            response(200, -1, new byte[MAX_ARCHIVE_BYTES + 1]))) {
            AtomicBoolean transportCalled = new AtomicBoolean();
            AlipayBillDownloader downloader = AlipayBillDownloader.forTesting(
                host -> List.of(publicAddress()),
                approvedUri -> {
                    transportCalled.set(true);
                    return response;
                });

            assertDownloadRejected(() -> downloader.download(APPROVED_URI));
            assertThat(transportCalled).isTrue();
        }
    }

    private HttpServer fixtureServer(byte[] bytes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture.zip", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private AlipayBillDownloader.DownloadResponse fixtureResponse(HttpServer server) throws IOException {
        URI fixtureUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/fixture.zip");
        HttpURLConnection connection = (HttpURLConnection) fixtureUri.toURL().openConnection();
        connection.setConnectTimeout(1_000);
        connection.setReadTimeout(1_000);
        return new AlipayBillDownloader.DownloadResponse(
            connection.getResponseCode(), connection.getContentLengthLong(), connection.getInputStream());
    }

    private static AlipayBillDownloader.DownloadResponse response(int status, long contentLength, byte[] body) {
        return new AlipayBillDownloader.DownloadResponse(status, contentLength, new ByteArrayInputStream(body));
    }

    private static InetAddress publicAddress() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
    }

    private static byte[] ipv6(int lastIndex, byte lastValue) {
        byte[] address = new byte[16];
        address[lastIndex] = lastValue;
        return address;
    }

    private static byte[] ipv6DocumentationAddress() {
        byte[] address = new byte[16];
        address[0] = 0x20;
        address[1] = 0x01;
        address[2] = 0x0d;
        address[3] = (byte) 0xb8;
        address[15] = 1;
        return address;
    }

    private static byte[] ipv6Groups(int... groups) {
        if (groups.length != 8) {
            throw new IllegalArgumentException("An IPv6 address requires eight groups");
        }
        byte[] address = new byte[16];
        for (int index = 0; index < groups.length; index++) {
            address[index * 2] = (byte) (groups[index] >>> 8);
            address[index * 2 + 1] = (byte) groups[index];
        }
        return address;
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final Object monitor = new Object();
        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            readStarted.countDown();
            synchronized (monitor) {
                while (!closed) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException("blocked body reader interrupted", ex);
                    }
                }
            }
            return -1;
        }

        @Override
        public void close() {
            synchronized (monitor) {
                closed = true;
                monitor.notifyAll();
            }
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static void assertDownloadRejected(ThrowingDownload action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AlipaySandboxException.class)
            .extracting(error -> ((AlipaySandboxException) error).getCode())
            .isEqualTo("ALIPAY_BILL_DOWNLOAD_REJECTED");
    }

    @FunctionalInterface
    private interface ThrowingDownload {
        void run() throws Exception;
    }
}
