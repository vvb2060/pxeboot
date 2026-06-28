package io.github.vvb2060.pxeboot.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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

    private static final int RX_BUFFER = 2048;
    private static final int MAX_RETRIES = 3;
    private static final int SO_TIMEOUT_MS = 3000;

    private final InetAddress bindAddress;
    private final Path root;
    private final boolean verbose;
    private DatagramSocket listener;

    TftpServer(AppConfig config) {
        this.bindAddress = config.serverIp;
        this.root = Paths.get(config.tftpRoot);
        this.verbose = config.verbose;
    }

    void start() {
        if (!Files.isDirectory(root)) {
            throw new RuntimeException(root + " is not a directory");
        }

        try (var listener = new DatagramSocket(new InetSocketAddress(bindAddress, 69))) {
            this.listener = listener;
            System.out.printf("TFTP server listening on %s:69 (root=%s)\n", bindAddress, root);

            byte[] rx = new byte[RX_BUFFER];
            while (!Thread.currentThread().isInterrupted()) {
                var packet = new DatagramPacket(rx, rx.length);
                try {
                    listener.receive(packet);
                } catch (SocketException e) {
                    if (listener.isClosed()) return;
                    System.err.println("Socket receive failed: " + e.getMessage());
                    continue;
                }
                handleInitialPacket(packet);
            }
        } catch (SocketException e) {
            throw new RuntimeException("Unable to bind TFTP socket on port 69: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("TFTP listener failed: " + e.getMessage(), e);
        } finally {
            this.listener = null;
        }
    }

    void close() {
        if (listener != null) {
            listener.close();
        }
    }

    private void handleInitialPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int len = packet.getLength();
        if (len < 4) {
            return;
        }

        int opcode = readU16(data, 0);
        if (opcode != OP_RRQ) {
            sendError(packet.getSocketAddress(), ERROR_ILLEGAL_OP, "Only RRQ is supported");
            return;
        }

        RrqRequest rrq;
        try {
            rrq = parseRrq(data, len);
        } catch (IllegalArgumentException ex) {
            sendError(packet.getSocketAddress(), ERROR_ILLEGAL_OP, ex.getMessage());
            return;
        }

        Thread worker = new Thread(() -> handleReadRequest(packet.getSocketAddress(), rrq),
            "tftp-rrq-" + packet.getAddress().getHostAddress() + ":" + packet.getPort());
        worker.setDaemon(true);
        worker.start();
    }

    private void handleReadRequest(SocketAddress clientAddress, RrqRequest rrq) {
        System.out.println(Thread.currentThread().getName() + " requested file: " + rrq.filename());
        Path target;
        try {
            target = safeResolve(rrq.filename());
        } catch (IllegalArgumentException ex) {
            sendError(clientAddress, ERROR_ACCESS, ex.getMessage());
            return;
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            sendError(clientAddress, ERROR_NOT_FOUND, "File not found: " + rrq.filename());
            return;
        }

        long fileSize;
        try {
            fileSize = Files.size(target);
        } catch (IOException e) {
            sendError(clientAddress, ERROR_ACCESS, "Unable to access file");
            return;
        }

        int blockSize = rrq.blockSize();
        int windowSize = rrq.windowSize();

        try (var transfer = new DatagramSocket()) {
            transfer.setSoTimeout(SO_TIMEOUT_MS);

            if (rrq.hasOptions()) {
                if (!waitOackAck(transfer, clientAddress, rrq, fileSize)) {
                    return;
                }
            }

            byte[] content = Files.readAllBytes(target);
            sendFile(transfer, clientAddress, content, blockSize, windowSize, rrq.filename());
        } catch (SocketException e) {
            System.err.println("TFTP transfer socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("TFTP transfer error: " + e.getMessage());
        }
    }

    private void sendFile(
        DatagramSocket transfer,
        SocketAddress clientAddress,
        byte[] content,
        int blockSize,
        int windowSize,
        String fileName
    ) throws IOException {
        int offset = 0;
        int block = 1;
        boolean done = false;
        var sentWindow = new ArrayList<DatagramPacket>(windowSize);

        while (!done) {
            sentWindow.clear();
            int firstBlock = block;
            int lastBlock = block - 1;

            for (int i = 0; i < windowSize; i++) {
                int remaining = content.length - offset;
                int payloadLen = Math.clamp(remaining, 0, blockSize);

                byte[] payload = new byte[4 + payloadLen];
                writeU16(payload, 0, OP_DATA);
                writeU16(payload, 2, block & 0xffff);
                if (payloadLen > 0) {
                    System.arraycopy(content, offset, payload, 4, payloadLen);
                }

                var packet = new DatagramPacket(payload, payload.length, clientAddress);
                transfer.send(packet);
                sentWindow.add(packet);
                lastBlock = block;

                if (verbose) {
                    System.out.printf("TFTP DATA block=%d size=%d -> %s file=%s\n",
                        block, payloadLen, clientAddress, fileName);
                }

                offset += payloadLen;
                block = (block + 1) & 0xffff;

                if (payloadLen < blockSize) {
                    done = true;
                    break;
                }
            }

            if (!waitAckForWindow(transfer, clientAddress, firstBlock, lastBlock, sentWindow)) {
                return;
            }
        }

        System.out.printf("TFTP transfer complete file=%s -> %s\n", fileName, clientAddress);
    }

    private boolean waitAckForWindow(
        DatagramSocket transfer,
        SocketAddress clientAddress,
        int firstBlock,
        int lastBlock,
        List<DatagramPacket> sentWindow
    ) throws IOException {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                byte[] rx = new byte[64];
                var ack = new DatagramPacket(rx, rx.length);
                transfer.receive(ack);

                if (!ack.getSocketAddress().equals(clientAddress)) {
                    sendError(ack.getSocketAddress(), ERROR_UNKNOWN_TID, "Unknown transfer ID");
                    continue;
                }

                int opcode = readU16(ack.getData(), 0);
                if (opcode == OP_ERROR) {
                    int errorCode = readU16(ack.getData(), 2);
                    String errorMsg = readZString(ack.getData(), ack.getLength(), 4);
                    System.err.printf("TFTP transfer error from %s: code=%d message=%s\n",
                        clientAddress, errorCode, errorMsg);
                    return false;
                }
                if (opcode == OP_ACK) {
                    int ackBlock = readU16(ack.getData(), 2);
                    if (inBlockRange(ackBlock, firstBlock, lastBlock)) {
                        if (ackBlock == lastBlock) {
                            if (verbose) {
                                System.out.printf("TFTP ACK block=%d <- %s\n", ackBlock, clientAddress);
                            }
                            return true;
                        } else {
                            var ackedCount = (ackBlock - firstBlock + 1) & 0xFFFF;
                            var lost = sentWindow.subList(ackedCount, sentWindow.size());
                            for (var packet : lost) {
                                transfer.send(packet);
                            }
                        }
                    }
                }
            } catch (SocketTimeoutException timeout) {
                retries++;
                for (var packet : sentWindow) {
                    transfer.send(packet);
                }
            }
        }

        System.err.printf("TFTP transfer timeout waiting ACK for blocks %d-%d\n", firstBlock, lastBlock);
        return false;
    }

    private boolean waitOackAck(DatagramSocket socket, SocketAddress clientAddress, RrqRequest rrq, long fileSize) throws IOException {
        int retries = 0;
        sendOack(socket, clientAddress, rrq, fileSize);

        while (retries < MAX_RETRIES) {
            try {
                byte[] rx = new byte[64];
                DatagramPacket ack = new DatagramPacket(rx, rx.length);
                socket.receive(ack);

                if (!ack.getSocketAddress().equals(clientAddress)) {
                    sendError(ack.getSocketAddress(), ERROR_UNKNOWN_TID, "Unknown transfer ID");
                    continue;
                }

                int opcode = readU16(ack.getData(), 0);
                if (opcode == OP_ERROR) {
                    int errorCode = readU16(ack.getData(), 2);
                    String errorMsg = readZString(ack.getData(), ack.getLength(), 4);
                    System.err.printf("TFTP OACK error from %s: code=%d message=%s\n",
                        clientAddress, errorCode, errorMsg);
                    return false;
                }
                if (opcode == OP_ACK) {
                    if (readU16(ack.getData(), 2) == 0) {
                        return true;
                    }
                }
            } catch (SocketTimeoutException timeout) {
                retries++;
                sendOack(socket, clientAddress, rrq, fileSize);
            }
        }

        System.err.printf("TFTP timeout waiting ACK for OACK from %s\n", clientAddress);
        return false;
    }

    private void sendOack(DatagramSocket socket, SocketAddress clientAddress, RrqRequest rrq, long fileSize)
        throws IOException {
        List<byte[]> fields = new ArrayList<>();
        if (rrq.requestedTsize()) {
            fields.add("tsize".getBytes(StandardCharsets.UTF_8));
            fields.add(Long.toString(fileSize).getBytes(StandardCharsets.UTF_8));
        }
        if (rrq.requestedBlksize()) {
            fields.add("blksize".getBytes(StandardCharsets.UTF_8));
            fields.add(Integer.toString(rrq.blockSize()).getBytes(StandardCharsets.UTF_8));
        }
        if (rrq.requestedWindowsize()) {
            fields.add("windowsize".getBytes(StandardCharsets.UTF_8));
            fields.add(Integer.toString(rrq.windowSize()).getBytes(StandardCharsets.UTF_8));
        }

        int payloadLen = 2;
        for (byte[] field : fields) {
            payloadLen += field.length + 1;
        }

        byte[] payload = new byte[payloadLen];
        writeU16(payload, 0, OP_OACK);
        int pos = 2;
        for (byte[] field : fields) {
            System.arraycopy(field, 0, payload, pos, field.length);
            pos += field.length;
            payload[pos++] = 0;
        }

        socket.send(new DatagramPacket(payload, payload.length, clientAddress));
        System.out.printf("TFTP OACK -> %s (blksize=%d windowsize=%d tsize=%d)\n",
            clientAddress, rrq.blockSize(), rrq.windowSize(), fileSize);
    }

    private void sendError(SocketAddress address, int code, String message) {
        try (var socket = new DatagramSocket()) {
            byte[] msg = message.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[4 + msg.length + 1];
            writeU16(payload, 0, OP_ERROR);
            writeU16(payload, 2, code);
            System.arraycopy(msg, 0, payload, 4, msg.length);
            payload[payload.length - 1] = 0;
            socket.send(new DatagramPacket(payload, payload.length, address));
        } catch (IOException ignored) {
            // Ignore send failures for best-effort error reporting.
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

    private static RrqRequest parseRrq(byte[] data, int len) {
        int pos = 2;
        String filename = readZString(data, len, pos);
        pos += filename.length() + 1;

        if (pos >= len) {
            throw new IllegalArgumentException("Malformed RRQ packet");
        }

        String mode = readZString(data, len, pos);
        pos += mode.length() + 1;

        var options = new LinkedHashMap<String, String>();
        while (pos < len) {
            String key = readZString(data, len, pos);
            pos += key.length() + 1;
            if (key.isEmpty()) {
                break;
            }
            if (pos >= len) {
                break;
            }
            String value = readZString(data, len, pos);
            pos += value.length() + 1;
            options.put(key.toLowerCase(Locale.ROOT), value);
        }

        if (!"octet".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Only octet mode is supported");
        }

        int blockSize = parsePositiveInt(options.get("blksize"), DEFAULT_BLOCK_SIZE, MAX_BLOCK_SIZE);
        int windowSize = parsePositiveInt(options.get("windowsize"), DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE);

        return new RrqRequest(
            filename,
            mode,
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

    private static String readZString(byte[] data, int len, int pos) {
        if (pos >= len) {
            return "";
        }
        int end = pos;
        while (end < len && data[end] != 0) {
            end++;
        }
        return new String(data, pos, end - pos, StandardCharsets.UTF_8);
    }

    private static int readU16(byte[] data, int pos) {
        int hi = Byte.toUnsignedInt(data[pos]);
        int lo = Byte.toUnsignedInt(data[pos + 1]);
        return (hi << 8) | lo;
    }

    private static void writeU16(byte[] data, int pos, int value) {
        data[pos] = (byte) ((value >>> 8) & 0xff);
        data[pos + 1] = (byte) (value & 0xff);
    }

    private record RrqRequest(
        String filename,
        String mode,
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
