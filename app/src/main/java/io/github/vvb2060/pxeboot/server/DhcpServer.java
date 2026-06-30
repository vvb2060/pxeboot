package io.github.vvb2060.pxeboot.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Optional;

final class DhcpServer {
    private static final int DHCP_MIN_PACKET_SIZE = 300;

    private final AppConfig config;

    DhcpServer(AppConfig config) {
        this.config = config;
    }

    void start() {
        try (var sel = Selector.open();
             var offer = DatagramChannel.open();
             var proxy = DatagramChannel.open()) {
            InetSocketAddress offerAddr;
            if (File.pathSeparatorChar == ';') {
                offerAddr = new InetSocketAddress(config.serverIp, 67);
            } else {
                offerAddr = new InetSocketAddress(67);
            }
            offer.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            offer.setOption(StandardSocketOptions.SO_BROADCAST, true);
            offer.bind(offerAddr);
            offer.configureBlocking(false);
            offer.register(sel, SelectionKey.OP_READ, false);

            var proxyAddr = new InetSocketAddress(config.serverIp, 4011);
            proxy.bind(proxyAddr);
            proxy.configureBlocking(false);
            proxy.register(sel, SelectionKey.OP_READ, true);

            System.out.printf("DHCP server listening on %s and %s\n", offerAddr, proxyAddr);

            receivePacket(sel);
        } catch (IOException e) {
            throw new RuntimeException("DHCP server error: " + e.getMessage(), e);
        }
    }

    private void receivePacket(Selector sel) throws IOException {
        var buffer = ByteBuffer.allocateDirect(2048);
        while (!Thread.currentThread().isInterrupted()) {
            sel.select();
            var keys = sel.selectedKeys().iterator();
            while (keys.hasNext()) {
                var key = keys.next();
                keys.remove();
                if (!key.isValid() || !key.isReadable()) continue;
                var channel = (DatagramChannel) key.channel();
                boolean isProxy = (Boolean) key.attachment();
                buffer.clear();
                var sender = channel.receive(buffer);
                if (sender == null) continue;
                buffer.flip();
                handlePacket(channel, buffer, sender, isProxy);
            }
        }
    }

    private void handlePacket(DatagramChannel channel, ByteBuffer data,
                              SocketAddress sender, boolean proxy) {
        DhcpPacket request;
        try {
            request = DhcpPacket.parse(data);
        } catch (IllegalArgumentException e) {
            System.out.println("Ignored non-DHCP packet: " + e.getMessage());
            return;
        }

        if (request.op != DhcpPacket.BOOT_REQUEST) {
            return;
        }

        var profile = classifyClient(request);
        if (profile.isEmpty()) return;

        var msgTypeOpt = request.messageType();
        if (msgTypeOpt.isEmpty()) {
            return;
        }
        int msgType = msgTypeOpt.get();
        if (!proxy && msgType == DhcpPacket.MSG_DISCOVER) {
            sendOffer(channel, request);
            return;
        }

        if (proxy && msgType == DhcpPacket.MSG_REQUEST) {
            sendProxyAck(channel, request, sender, profile.get());
        }
    }

    private void sendOffer(DatagramChannel channel, DhcpPacket request) {
        DhcpPacket offer = new DhcpPacket();
        fillCommonReply(offer, request);

        offer.op = DhcpPacket.BOOT_REPLY;
        offer.siaddr = config.serverIp.getAddress();

        offer.options.put(DhcpPacket.OPTION_MESSAGE_TYPE, new byte[]{(byte) DhcpPacket.MSG_OFFER});
        offer.options.put(DhcpPacket.OPTION_SERVER_IDENTIFIER, config.serverIp.getAddress());
        request.option(DhcpPacket.OPTION_VENDOR_CLASS_IDENTIFIER)
            .ifPresent(value -> offer.options.put(DhcpPacket.OPTION_VENDOR_CLASS_IDENTIFIER, value));
        request.option(DhcpPacket.OPTION_CLIENT_UUID)
            .ifPresent(value -> offer.options.put(DhcpPacket.OPTION_CLIENT_UUID, value));

        var payload = offer.encode(DHCP_MIN_PACKET_SIZE);
        var address = new InetSocketAddress("255.255.255.255", 68);
        send(channel, payload, address, request.xid, "OFFER");
    }

    private void sendProxyAck(
        DatagramChannel channel,
        DhcpPacket request,
        SocketAddress address,
        ClientProfile profile
    ) {
        DhcpPacket ack = new DhcpPacket();
        fillCommonReply(ack, request);

        ack.op = DhcpPacket.BOOT_REPLY;
        ack.siaddr = config.serverIp.getAddress();
        ack.file = DhcpPacket.asNulPaddedAscii(profile.bootFile, 128);

        ack.options.put(DhcpPacket.OPTION_MESSAGE_TYPE, new byte[]{(byte) DhcpPacket.MSG_ACK});
        ack.options.put(DhcpPacket.OPTION_SERVER_IDENTIFIER, config.serverIp.getAddress());
        ack.options.put(DhcpPacket.OPTION_TFTP_SERVER_NAME, DhcpPacket.asNulTerminatedAscii(config.serverIp.getHostAddress()));
        ack.options.put(DhcpPacket.OPTION_BOOT_FILE_NAME, DhcpPacket.asNulTerminatedAscii(profile.bootFile));
        request.option(DhcpPacket.OPTION_VENDOR_CLASS_IDENTIFIER)
            .ifPresent(value -> ack.options.put(DhcpPacket.OPTION_VENDOR_CLASS_IDENTIFIER, value));
        request.option(DhcpPacket.OPTION_CLIENT_ARCH)
            .ifPresent(value -> ack.options.put(DhcpPacket.OPTION_CLIENT_ARCH, value));
        request.option(DhcpPacket.OPTION_CLIENT_NDI)
            .ifPresent(value -> ack.options.put(DhcpPacket.OPTION_CLIENT_NDI, value));
        request.option(DhcpPacket.OPTION_CLIENT_UUID)
            .ifPresent(value -> ack.options.put(DhcpPacket.OPTION_CLIENT_UUID, value));

        var payload = ack.encode(DHCP_MIN_PACKET_SIZE);
        String ackKind = "ACK-" + profile.label;
        send(channel, payload, address, request.xid, ackKind);
    }

    private Optional<ClientProfile> classifyClient(DhcpPacket request) {
        String vendorClass = request.vendorClass().orElse("");
        if (vendorClass.startsWith("PXEClient")) {
            int arch = request.clientArchitecture().orElse(-1);
            boolean ipxeRequest = request.userClass().map("iPXE"::equals).orElse(false);
            if (arch == 7) {
                return Optional.of(ClientProfile.UEFI_PXE_x64);
            }
            if (arch == 11) {
                return Optional.of(ClientProfile.UEFI_PXE_ARM);
            }
            if (arch == 0) {
                return Optional.of(ipxeRequest ? ClientProfile.BIOS_iPXE : ClientProfile.BIOS_PXE);
            }
        }
        System.out.printf("Ignored request xid=0x%08x: vendorClass=%s\n", request.xid, vendorClass);
        return Optional.empty();
    }

    private void fillCommonReply(DhcpPacket reply, DhcpPacket request) {
        reply.htype = request.htype;
        reply.hlen = request.hlen;
        reply.hops = request.hops;
        reply.xid = request.xid;
        reply.secs = request.secs;
        reply.flags = request.flags;

        reply.chaddr = Arrays.copyOf(request.chaddr, request.chaddr.length);
    }

    private void send(DatagramChannel channel, ByteBuffer payload, SocketAddress address, int xid, String kind) {
        try {
            channel.send(payload, address);
            System.out.printf("Sent %s xid=0x%08x -> %s (%d bytes)\n", kind, xid, address, payload.capacity());
        } catch (IOException e) {
            System.err.printf("Failed to send %s xid=0x%08x: %s\n", kind, xid, e.getMessage());
        }
    }

    private enum ClientProfile {
        UEFI_PXE_x64("UEFI-PXE-x64", "x86_64-sb/snponly-shim.efi"),
        UEFI_PXE_ARM("UEFI-PXE-ARM", "arm64-sb/snponly-shim.efi"),
        BIOS_PXE("BIOS-PXE", "x86_64/undionly.kpxe"),
        BIOS_iPXE("BIOS-iPXE", "autoexec.ipxe");

        final String label;
        final String bootFile;

        ClientProfile(String label, String bootFile) {
            this.label = label;
            this.bootFile = bootFile;
        }
    }
}
