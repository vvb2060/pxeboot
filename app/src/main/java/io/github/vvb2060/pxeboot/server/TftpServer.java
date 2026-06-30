package io.github.vvb2060.pxeboot.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class TftpServer {
    private static final int OP_RRQ = 1;
    private static final int OP_DATA = 3;
    private static final int OP_ACK = 4;
    private static final int OP_ERROR = 5;
    private static final int OP_OACK = 6;

    private static final int ERROR_NOT_FOUND = 1;
    private static final int ERROR_ACCESS = 2;
    private static final int ERROR_ILLEGAL_OP = 4;
    private static final int ERROR_UNKNOWN_TID = 5;

    private static final int DEFAULT_BLOCK_SIZE = 512;
    private static final int MAX_BLOCK_SIZE = 1468;
    private static final int DEFAULT_WINDOW_SIZE = 1;
    private static final int MAX_WINDOW_SIZE = 8;

    private static final int RX_BUFFER_SIZE = 2048;
    private static final int MAX_RETRIES = 3;
    private static final int SO_TIMEOUT_MS = 3000;

    private final InetAddress bindAddress;
    private final Path root;
    private final boolean verbose;

    TftpServer(AppConfig config) {
        this.bindAddress = config.serverIp;
        this.root = Paths.get(config.tftpRoot);
        this.verbose = config.verbose;
    }

    void start() {
        if (!Files.isDirectory(root)) {
            throw new RuntimeException(root + " is not a directory");
        }

        try (var selector = Selector.open();
             var channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(bindAddress, 69));
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new ListenerHandler());

            System.out.printf("TFTP server listening on %s:69 (root=%s)\n", bindAddress, root);

            receivePacket(selector);
        } catch (IOException e) {
            throw new RuntimeException("TFTP server failed: " + e.getMessage(), e);
        }
    }

    private void receivePacket(Selector selector) throws IOException {
        var buffer = ByteBuffer.allocateDirect(RX_BUFFER_SIZE);
        while (!Thread.currentThread().isInterrupted()) {
            int ready = selector.select(3000);

            long now = System.currentTimeMillis();
            for (var key : selector.keys()) {
                if (key.attachment() instanceof TransferSession session) {
                    session.checkTimeout(now);
                }
            }

            if (ready == 0) continue;
            var keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                var key = keys.next();
                keys.remove();
                try {
                    if (key.isValid() && key.isReadable()) {
                        var handler = (ChannelHandler) key.attachment();
                        handler.handleRead(key, buffer);
                    }
                } catch (IOException e) {
                    System.err.printf("TFTP session error: %s\n", e.getMessage());
                    key.cancel();
                    try {
                        key.channel().close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private interface ChannelHandler {
        void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException;
    }

    private class ListenerHandler implements ChannelHandler {
        @Override
        public void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
            var channel = (DatagramChannel) key.channel();
            buffer.clear();
            var clientAddress = channel.receive(buffer);
            if (clientAddress == null) return;

            buffer.flip();
            if (buffer.remaining() < 4) return;

            int opcode = Short.toUnsignedInt(buffer.getShort());
            if (opcode != OP_RRQ) {
                sendError(channel, clientAddress, ERROR_ILLEGAL_OP, "Only RRQ is supported");
                return;
            }

            try {
                var rrq = parseRrq(buffer);
                startTransfer(key, clientAddress, rrq);
            } catch (IllegalArgumentException ex) {
                sendError(channel, clientAddress, ERROR_ILLEGAL_OP, ex.getMessage());
            }
        }

        private void startTransfer(SelectionKey key, SocketAddress clientAddress, RrqRequest rrq) {
            var channel = (DatagramChannel) key.channel();
            var selector = key.selector();
            try {
                System.out.println("Requested file: " + rrq.filename());
                Path target = safeResolve(rrq.filename());
                if (!Files.exists(target) || !Files.isRegularFile(target)) {
                    sendError(channel, clientAddress, ERROR_NOT_FOUND,
                        "File not found: " + rrq.filename());
                    return;
                }

                var fileContent = Files.readAllBytes(target);
                var transferChannel = DatagramChannel.open();
                transferChannel.configureBlocking(false);
                var session = new TransferSession(transferChannel, clientAddress, rrq, fileContent);
                transferChannel.register(selector, SelectionKey.OP_READ, session);

                session.start();
            } catch (IllegalArgumentException | IOException ex) {
                sendError(channel, clientAddress, ERROR_ACCESS, ex.getMessage());
            }
        }
    }

    private class TransferSession implements ChannelHandler {
        private final DatagramChannel channel;
        private final SocketAddress clientAddress;
        private final RrqRequest rrq;
        private final byte[] fileContent;
        private final int fileSize;
        private final List<ByteBuffer> sentWindow;

        private int block = 1;
        private int fileOffset = 0;
        private boolean eof = false;
        private int firstBlockInWindow;
        private int lastBlockInWindow;

        private long lastActiveTime;
        private int retries = 0;
        private boolean waitingOackAck = false;

        TransferSession(DatagramChannel channel, SocketAddress clientAddress,
                        RrqRequest rrq, byte[] fileContent) {
            this.channel = channel;
            this.clientAddress = clientAddress;
            this.rrq = rrq;
            this.fileContent = fileContent;
            this.fileSize = fileContent.length;
            this.lastActiveTime = System.currentTimeMillis();
            this.sentWindow = new ArrayList<>(rrq.windowSize());
        }

        void start() throws IOException {
            if (rrq.hasOptions()) {
                waitingOackAck = true;
                sendOack();
            } else {
                sendNextWindow();
            }
        }

        @Override
        public void handleRead(SelectionKey key, ByteBuffer buffer) throws IOException {
            buffer.clear();
            var sender = channel.receive(buffer);
            if (sender == null) return;

            if (!sender.equals(clientAddress)) {
                sendError(channel, sender, ERROR_UNKNOWN_TID, "Unknown transfer ID");
                return;
            }

            buffer.flip();
            if (buffer.remaining() < 4) return;

            int opcode = Short.toUnsignedInt(buffer.getShort());
            if (opcode == OP_ERROR) {
                int errorCode = Short.toUnsignedInt(buffer.getShort());
                String errorMsg = readZString(buffer);
                System.err.printf("TFTP error from %s: code=%d message=%s\n",
                    clientAddress, errorCode, errorMsg);
                close();
                return;
            }

            if (opcode == OP_ACK) {
                int ackBlock = Short.toUnsignedInt(buffer.getShort());
                lastActiveTime = System.currentTimeMillis();
                retries = 0;

                if (waitingOackAck && ackBlock == 0) {
                    waitingOackAck = false;
                    sendNextWindow();
                    return;
                }

                if (inBlockRange(ackBlock, firstBlockInWindow, lastBlockInWindow)) {
                    if (verbose) {
                        System.out.printf("TFTP ACK block=%d <- %s\n", ackBlock, clientAddress);
                    }
                    if (ackBlock == lastBlockInWindow) {
                        if (eof) {
                            System.out.printf("TFTP transfer complete file=%s -> %s\n",
                                rrq.filename(), clientAddress);
                            close();
                        } else {
                            sendNextWindow();
                        }
                    } else {
                        int ackedCount = (ackBlock - firstBlockInWindow + 1) & 0xFFFF;
                        for (int i = ackedCount; i < sentWindow.size(); i++) {
                            var buf = sentWindow.get(i);
                            buf.rewind();
                            channel.send(buf, clientAddress);
                        }
                    }
                }
            }
        }

        private void sendNextWindow() throws IOException {
            sentWindow.clear();
            firstBlockInWindow = block;

            for (int i = 0; i < rrq.windowSize(); i++) {
                int remaining = fileSize - fileOffset;
                int payloadLen = Math.clamp(remaining, 0, rrq.blockSize());

                var packet = ByteBuffer.allocateDirect(4 + payloadLen);
                packet.putShort((short) OP_DATA);
                packet.putShort((short) block);

                if (payloadLen > 0) {
                    packet.put(fileContent, fileOffset, payloadLen);
                }

                packet.flip();
                channel.send(packet, clientAddress);
                sentWindow.add(packet);
                lastBlockInWindow = block;

                if (verbose) {
                    System.out.printf("TFTP DATA block=%d size=%d -> %s file=%s\n",
                        block, payloadLen, clientAddress, rrq.filename());
                }

                fileOffset += payloadLen;
                block = (block + 1) & 0xffff;

                if (payloadLen < rrq.blockSize()) {
                    eof = true;
                    break;
                }
            }
        }

        private void sendOack() throws IOException {
            var packet = ByteBuffer.allocateDirect(512);
            packet.putShort((short) OP_OACK);

            if (rrq.requestedTsize()) {
                writeZString(packet, "tsize");
                writeZString(packet, Integer.toString(fileSize));
            }
            if (rrq.requestedBlksize()) {
                writeZString(packet, "blksize");
                writeZString(packet, Integer.toString(rrq.blockSize()));
            }
            if (rrq.requestedWindowsize()) {
                writeZString(packet, "windowsize");
                writeZString(packet, Integer.toString(rrq.windowSize()));
            }

            packet.flip();
            channel.send(packet, clientAddress);
            sentWindow.clear();
            sentWindow.add(packet);

            System.out.printf("TFTP OACK -> %s (blksize=%d windowsize=%d tsize=%d)\n",
                clientAddress, rrq.blockSize(), rrq.windowSize(), fileSize);
        }

        void checkTimeout(long now) {
            if (now - lastActiveTime > SO_TIMEOUT_MS) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    System.err.printf("TFTP transfer timeout -> %s\n", clientAddress);
                    close();
                } else {
                    lastActiveTime = now;
                    try {
                        for (var buf : sentWindow) {
                            buf.rewind();
                            channel.send(buf, clientAddress);
                        }
                    } catch (IOException e) {
                        close();
                    }
                }
            }
        }

        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendError(DatagramChannel channel, SocketAddress address,
                                  int code, String message) {
        try {
            var msgBytes = message.getBytes(StandardCharsets.UTF_8);
            var buf = ByteBuffer.allocateDirect(4 + msgBytes.length + 1);
            buf.putShort((short) OP_ERROR);
            buf.putShort((short) code);
            buf.put(msgBytes);
            buf.put((byte) 0);
            buf.flip();
            channel.send(buf, address);
        } catch (IOException ignored) {
        }
    }

    private Path safeResolve(String requestedFile) {
        String normalized = requestedFile.replace('\\', '/');
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Access denied");
        }
        return resolved;
    }

    private static RrqRequest parseRrq(ByteBuffer buffer) {
        String filename = readZString(buffer);
        if (!buffer.hasRemaining() || filename.isEmpty()) {
            throw new IllegalArgumentException("Malformed RRQ packet");
        }

        String mode = readZString(buffer);

        var options = new LinkedHashMap<String, String>();
        while (buffer.hasRemaining()) {
            String key = readZString(buffer);
            if (key.isEmpty() || !buffer.hasRemaining()) break;
            String value = readZString(buffer);
            options.put(key.toLowerCase(Locale.ROOT), value);
        }

        if (!"octet".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Only octet mode is supported");
        }

        int blockSize = parsePositiveInt(options.get("blksize"), DEFAULT_BLOCK_SIZE, MAX_BLOCK_SIZE);
        int windowSize = parsePositiveInt(options.get("windowsize"), DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE);

        return new RrqRequest(
            filename,
            options.containsKey("tsize"),
            options.containsKey("blksize"),
            options.containsKey("windowsize"),
            blockSize,
            windowSize
        );
    }

    private static int parsePositiveInt(String raw, int fallback, int maxValue) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                return fallback;
            }
            return Math.min(value, maxValue);
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    private static boolean inBlockRange(int block, int start, int end) {
        if (start <= end) {
            return block >= start && block <= end;
        }
        return block >= start || block <= end;
    }

    private static String readZString(ByteBuffer buffer) {
        int start = buffer.position();
        while (true) {
            if (!buffer.hasRemaining() || buffer.get() == 0) break;
        }
        int len = buffer.position() - start - 1;
        if (len <= 0) return "";

        byte[] strBytes = new byte[len];
        buffer.position(start);
        buffer.get(strBytes);
        buffer.get();
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    private static void writeZString(ByteBuffer buffer, String str) {
        buffer.put(str.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
    }

    private record RrqRequest(
        String filename,
        boolean requestedTsize,
        boolean requestedBlksize,
        boolean requestedWindowsize,
        int blockSize,
        int windowSize
    ) {
        boolean hasOptions() {
            return requestedTsize || requestedBlksize || requestedWindowsize;
        }
    }
}
