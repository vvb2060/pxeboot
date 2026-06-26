package io.github.vvb2060.pxeboot.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class DhcpPacket {
    static final int BOOT_REQUEST = 1;
    static final int BOOT_REPLY = 2;

    static final int MSG_DISCOVER = 1;
    static final int MSG_OFFER = 2;
    static final int MSG_REQUEST = 3;
    static final int MSG_ACK = 5;

    static final int OPTION_MESSAGE_TYPE = 53;
    static final int OPTION_SERVER_IDENTIFIER = 54;
    static final int OPTION_VENDOR_CLASS_IDENTIFIER = 60;
    static final int OPTION_TFTP_SERVER_NAME = 66;
    static final int OPTION_BOOT_FILE_NAME = 67;
    static final int OPTION_USER_CLASS = 77;
    static final int OPTION_CLIENT_ARCH = 93;
    static final int OPTION_CLIENT_NDI = 94;
    static final int OPTION_CLIENT_UUID = 97;
    static final int OPTION_END = 255;

    static final int MAGIC_COOKIE = 0x63825363;

    int op;
    int htype;
    int hlen;
    int hops;
    int xid;
    int secs;
    int flags;

    byte[] ciaddr = new byte[4];
    byte[] yiaddr = new byte[4];
    byte[] siaddr = new byte[4];
    byte[] giaddr = new byte[4];

    byte[] chaddr = new byte[16];
    byte[] sname = new byte[64];
    byte[] file = new byte[128];

    final Map<Integer, byte[]> options = new LinkedHashMap<>();

    static DhcpPacket parse(byte[] packetData, int length) {
        if (length < 240) {
            throw new IllegalArgumentException("Packet too short to be DHCP/BOOTP");
        }

        ByteBuffer buf = ByteBuffer.wrap(packetData, 0, length);
        DhcpPacket p = new DhcpPacket();

        p.op = Byte.toUnsignedInt(buf.get());
        p.htype = Byte.toUnsignedInt(buf.get());
        p.hlen = Byte.toUnsignedInt(buf.get());
        p.hops = Byte.toUnsignedInt(buf.get());
        p.xid = buf.getInt();
        p.secs = Short.toUnsignedInt(buf.getShort());
        p.flags = Short.toUnsignedInt(buf.getShort());

        buf.get(p.ciaddr);
        buf.get(p.yiaddr);
        buf.get(p.siaddr);
        buf.get(p.giaddr);

        buf.get(p.chaddr);
        buf.get(p.sname);
        buf.get(p.file);

        int cookie = buf.getInt();
        if (cookie != MAGIC_COOKIE) {
            throw new IllegalArgumentException("Missing DHCP magic cookie");
        }

        while (buf.hasRemaining()) {
            int code = Byte.toUnsignedInt(buf.get());
            if (code == 0) {
                continue;
            }
            if (code == OPTION_END) {
                break;
            }
            if (!buf.hasRemaining()) {
                break;
            }
            int len = Byte.toUnsignedInt(buf.get());
            if (len > buf.remaining()) {
                break;
            }
            byte[] value = new byte[len];
            buf.get(value);
            p.options.put(code, value);
        }

        return p;
    }

    byte[] encode(int minLength) {
        int optionsLength = 0;
        for (byte[] value : options.values()) {
            optionsLength += 2 + value.length;
        }
        optionsLength += 1; // End option

        int totalLength = Math.max(240 + optionsLength, minLength);
        ByteBuffer buf = ByteBuffer.allocate(totalLength);

        buf.put((byte) op);
        buf.put((byte) htype);
        buf.put((byte) hlen);
        buf.put((byte) hops);
        buf.putInt(xid);
        buf.putShort((short) secs);
        buf.putShort((short) flags);

        buf.put(ciaddr);
        buf.put(yiaddr);
        buf.put(siaddr);
        buf.put(giaddr);

        buf.put(chaddr);
        buf.put(sname);
        buf.put(file);

        buf.putInt(MAGIC_COOKIE);

        for (var entry : options.entrySet()) {
            byte[] value = entry.getValue();
            buf.put((byte) (entry.getKey() & 0xff));
            buf.put((byte) (value.length & 0xff));
            buf.put(value);
        }

        buf.put((byte) OPTION_END);

        return buf.array();
    }

    Optional<Integer> messageType() {
        byte[] value = options.get(OPTION_MESSAGE_TYPE);
        if (value == null || value.length == 0) {
            return Optional.empty();
        }
        return Optional.of(Byte.toUnsignedInt(value[0]));
    }

    Optional<String> vendorClass() {
        byte[] value = options.get(OPTION_VENDOR_CLASS_IDENTIFIER);
        if (value == null || value.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new String(value, StandardCharsets.UTF_8));
    }

    Optional<Integer> clientArchitecture() {
        byte[] value = options.get(OPTION_CLIENT_ARCH);
        if (value == null || value.length < 2) {
            return Optional.empty();
        }
        int arch = (Byte.toUnsignedInt(value[0]) << 8) | Byte.toUnsignedInt(value[1]);
        return Optional.of(arch);
    }

    Optional<String> userClass() {
        byte[] value = options.get(OPTION_USER_CLASS);
        if (value == null || value.length == 0) {
            return Optional.empty();
        }

        // RFC 3004 encodes user-class as one or more length-prefixed opaque fields.
        int classLen = Byte.toUnsignedInt(value[0]);
        if (value.length >= classLen + 1 && classLen > 0) {
            return Optional.of(new String(value, 1, classLen, StandardCharsets.UTF_8));
        }

        // Some clients send a plain string without inner length fields.
        return Optional.of(new String(value, StandardCharsets.UTF_8));
    }

    Optional<byte[]> option(int code) {
        byte[] value = options.get(code);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOf(value, value.length));
    }

    static byte[] asNulPaddedAscii(String text, int fieldLength) {
        byte[] out = new byte[fieldLength];
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int copyLen = Math.min(raw.length, fieldLength - 1);
        System.arraycopy(raw, 0, out, 0, copyLen);
        out[copyLen] = 0;
        return out;
    }

    static byte[] asNulTerminatedAscii(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(raw, raw.length + 1);
        out[raw.length] = 0;
        return out;
    }
}
