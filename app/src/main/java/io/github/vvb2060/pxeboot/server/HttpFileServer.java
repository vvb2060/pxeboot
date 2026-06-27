package io.github.vvb2060.pxeboot.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class HttpFileServer {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int BUFFER_SIZE = 8 * 1024;

    private final InetAddress bindAddress;
    private final int port;
    private final Path root;
    private final boolean verbose;

    HttpFileServer(AppConfig config) {
        this.bindAddress = config.serverIp;
        this.port = config.httpPort;
        this.root = Paths.get(config.httpRoot);
        this.verbose = config.verbose;
    }

    void start() {
        if (!Files.isDirectory(root)) {
            throw new RuntimeException(root + " is not a directory");
        }

        try (var selector = Selector.open();
             var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(bindAddress, port));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);

            System.out.printf("HTTP file server listening on %s:%d (root=%s)\n", bindAddress, port, root);

            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(selector, server);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException e) {
                        closeKey(key);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Server runtime error on port " + port, e);
        }
    }

    private void handleAccept(Selector selector, ServerSocketChannel server) throws IOException {
        var client = server.accept();
        if (client != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new ClientContext());
            System.out.println("Accepted connection from " + client.socket().getRemoteSocketAddress());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        var client = (SocketChannel) key.channel();
        var ctx = (ClientContext) key.attachment();

        var buf = ctx.readBuffer;
        int n = client.read(buf);
        if (n < 0) {
            closeKey(key);
            return;
        }
        if (n == 0) {
            return;
        }

        buf.flip();
        byte[] chunk = new byte[buf.remaining()];
        buf.get(chunk);
        ctx.headerStream.write(chunk);
        buf.clear();

        if (ctx.headerStream.size() > MAX_HEADER_BYTES) {
            prepareSimpleResponse(key, ctx, 431, "Request Header Fields Too Large", "Header too large");
            return;
        }

        byte[] data = ctx.headerStream.toByteArray();
        int end = headerEnd(data);
        if (end >= 0) {
            String headerText = new String(data, 0, end, StandardCharsets.UTF_8);
            var request = parseRequest(headerText);
            if (request == null) {
                prepareSimpleResponse(key, ctx, 400, "Bad Request", "Malformed request line");
                return;
            }

            String connHeader = request.headers.get("connection");
            ctx.keepAlive = connHeader != null && "keep-alive".equalsIgnoreCase(connHeader.trim());

            prepareFileResponse(key, ctx, request);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        var client = (SocketChannel) key.channel();
        var ctx = (ClientContext) key.attachment();

        if (ctx.responseHeaderBuffer != null && ctx.responseHeaderBuffer.hasRemaining()) {
            client.write(ctx.responseHeaderBuffer);
            if (ctx.responseHeaderBuffer.hasRemaining()) {
                return;
            }
        }

        if (ctx.responseBodyBuffer != null && ctx.responseBodyBuffer.hasRemaining()) {
            client.write(ctx.responseBodyBuffer);
            if (ctx.responseBodyBuffer.hasRemaining()) {
                return;
            }
        }

        if (ctx.fileChannel != null && ctx.fileRemaining > 0) {
            long sent = ctx.fileChannel.transferTo(ctx.filePosition, ctx.fileRemaining, client);
            if (sent > 0) {
                ctx.filePosition += sent;
                ctx.fileRemaining -= sent;
            }
            if (ctx.fileRemaining > 0) {
                return;
            }
        }

        if (verbose && ctx.logLine != null) {
            System.out.println(ctx.logLine);
        }

        if (ctx.keepAlive) {
            ctx.resetForNextRequest();
            key.interestOps(SelectionKey.OP_READ);
        } else {
            closeKey(key);
        }
    }

    private void prepareFileResponse(SelectionKey key, ClientContext ctx, HttpRequest request) throws IOException {
        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            prepareSimpleResponse(key, ctx, 405, "Method Not Allowed", "Only GET and HEAD are supported");
            return;
        }

        Path target;
        try {
            target = resolveSafePath(request.path);
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            prepareSimpleResponse(key, ctx, 403, "Forbidden", "Access denied");
            return;
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            prepareSimpleResponse(key, ctx, 404, "Not Found", "File not found");
            return;
        }

        long fileSize = Files.size(target);
        ByteRange range = null;
        String rangeHeader = request.headers.get("range");
        if (rangeHeader != null) {
            range = parseRange(rangeHeader, fileSize);
            if (range == null) {
                String headers = commonHeaders(416, "Requested Range Not Satisfiable", ctx.keepAlive)
                    + "Content-Range: bytes */" + fileSize + "\r\n"
                    + "Content-Length: 0\r\n\r\n";
                ctx.responseHeaderBuffer = StandardCharsets.UTF_8.encode(headers);
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
        }

        long start = range == null ? 0 : range.start;
        long end = range == null ? (fileSize - 1) : range.end;
        long contentLength = end - start + 1;

        String contentType = Files.probeContentType(target);
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        int status = range == null ? 200 : 206;
        String reason = range == null ? "OK" : "Partial Content";

        StringBuilder headers = new StringBuilder();
        headers.append(commonHeaders(status, reason, ctx.keepAlive));
        headers.append("Accept-Ranges: bytes\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(contentLength).append("\r\n");
        if (range != null) {
            headers.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileSize).append("\r\n");
        }
        headers.append("\r\n");

        ctx.responseHeaderBuffer = StandardCharsets.UTF_8.encode(headers.toString());

        if ("HEAD".equals(request.method)) {
            ctx.logLine = String.format("HTTP HEAD %s -> %d (%d bytes)", request.path, status, contentLength);
        } else {
            ctx.fileChannel = FileChannel.open(target, StandardOpenOption.READ);
            ctx.filePosition = start;
            ctx.fileRemaining = contentLength;
            ctx.logLine = String.format("HTTP GET  %s -> %d (%d bytes)", request.path, status, contentLength);
        }

        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void prepareSimpleResponse(SelectionKey key, ClientContext ctx, int status, String reason, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String headers = commonHeaders(status, reason, ctx.keepAlive)
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "Content-Length: " + payload.length + "\r\n\r\n";

        ctx.responseHeaderBuffer = StandardCharsets.UTF_8.encode(headers);
        ctx.responseBodyBuffer = ByteBuffer.wrap(payload);
        ctx.logLine = String.format("HTTP RESP %d -> %s", status, reason);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private String commonHeaders(int status, String reason, boolean keepAlive) {
        return "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
            + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n";
    }

    private void closeKey(SelectionKey key) {
        try {
            var ctx = (ClientContext) key.attachment();
            if (ctx != null) {
                ctx.close();
            }
            key.channel().close();
        } catch (IOException ignored) {
        }
        key.cancel();
    }

    private Path resolveSafePath(String requestPath) throws UnsupportedEncodingException {
        String pathOnly = requestPath;
        int q = pathOnly.indexOf('?');
        if (q >= 0) {
            pathOnly = pathOnly.substring(0, q);
        }

        String decoded = URLDecoder.decode(pathOnly, "UTF-8");
        if (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }
        if (decoded.isEmpty()) {
            throw new IllegalArgumentException();
        }

        Path resolved = root.resolve(decoded).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException();
        }
        return resolved;
    }

    private ByteRange parseRange(String header, long fileSize) {
        String raw = header.trim().toLowerCase(Locale.ROOT);
        if (!raw.startsWith("bytes=")) {
            return null;
        }

        String spec = raw.substring("bytes=".length()).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma);
        }

        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }

        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();

        try {
            long start;
            long end;

            if (startPart.isEmpty()) {
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    return null;
                }
                if (suffixLength >= fileSize) {
                    start = 0;
                } else {
                    start = fileSize - suffixLength;
                }
                end = fileSize - 1;
            } else {
                start = Long.parseLong(startPart);
                if (start < 0 || start >= fileSize) {
                    return null;
                }
                if (endPart.isEmpty()) {
                    end = fileSize - 1;
                } else {
                    end = Long.parseLong(endPart);
                    if (end < start) {
                        return null;
                    }
                    if (end >= fileSize) {
                        end = fileSize - 1;
                    }
                }
            }

            return new ByteRange(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HttpRequest parseRequest(String headerText) {
        String[] lines = headerText.split("\\r\\n");
        if (lines.length == 0) {
            return null;
        }

        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length < 2) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(key, value);
        }

        return new HttpRequest(requestLine[0].trim().toUpperCase(Locale.ROOT), requestLine[1].trim(), headers);
    }

    private int headerEnd(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private record HttpRequest(String method, String path, Map<String, String> headers) {
    }

    private record ByteRange(long start, long end) {
    }

    private static final class ClientContext implements AutoCloseable {
        final ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        final ByteArrayOutputStream headerStream = new ByteArrayOutputStream(BUFFER_SIZE);

        ByteBuffer responseHeaderBuffer;
        ByteBuffer responseBodyBuffer;
        FileChannel fileChannel;
        long filePosition;
        long fileRemaining;

        boolean keepAlive;
        String logLine;

        void resetForNextRequest() {
            headerStream.reset();
            readBuffer.clear();

            responseHeaderBuffer = null;
            responseBodyBuffer = null;
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException ignored) {
                }
                fileChannel = null;
            }
            filePosition = 0;
            fileRemaining = 0;
            keepAlive = false;
            logLine = null;
        }

        @Override
        public void close() {
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
