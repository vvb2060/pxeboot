package io.github.vvb2060.pxeboot.server;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Optional;

final class DhcpServer {
    private static final int DHCP_MIN_PACKET_SIZE = 300;

    private final AppConfig config;

    private DatagramSocket offerSocket;
    private DatagramSocket proxySocket;

    DhcpServer(AppConfig config) {
        this.config = config;
    }

    void serveOffer() {
        InetSocketAddress addr;
        if (File.pathSeparatorChar == ';') {
            addr = new InetSocketAddress(config.serverIp, 67);
        } else {
            addr = new InetSocketAddress(67);
        }
        try (var socket = new DatagramSocket(addr)) {
            offerSocket = socket;
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
            runLoop(socket, false);
        } catch (SocketException e) {
            throw new RuntimeException("Unable to bind DHCP socket on port 67: " + e.getMessage(), e);
        } finally {
            offerSocket = null;
        }
    }

    void serveProxy() {
        try (var socket = new DatagramSocket(new InetSocketAddress(config.serverIp, 4011))) {
            proxySocket = socket;
            runLoop(socket, true);
        } catch (SocketException e) {
            throw new RuntimeException("Unable to bind ProxyDHCP socket on port 4011: " + e.getMessage(), e);
        } finally {
            proxySocket = null;
        }
    }

    void close() {
        if (offerSocket != null) {
            offerSocket.close();
        }
        if (proxySocket != null) {
            proxySocket.close();
        }
    }

    private void runLoop(DatagramSocket socket, boolean proxy) {
        byte[] buffer = new byte[2048];

        while (!Thread.currentThread().isInterrupted()) {
            var datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                if (socket.isClosed()) return;
                System.err.println("Socket receive failed: " + e.getMessage());
                continue;
            }

            DhcpPacket request;
            try {
                request = DhcpPacket.parse(datagram.getData(), datagram.getLength());
            } catch (IllegalArgumentException e) {
                System.out.println("Ignored non-DHCP packet: " + e.getMessage());
                continue;
            }

            if (request.op != DhcpPacket.BOOT_REQUEST) {
                continue;
            }

            String vendorClass = request.vendorClass().orElse("");
            if (!vendorClass.startsWith("PXEClient")) {
                System.out.printf("Ignored request xid=0x%08x: vendorClass=%s\n",
                    request.xid, vendorClass);
                continue;
            }

            var msgTypeOpt = request.messageType();
            if (msgTypeOpt.isEmpty()) {
                continue;
            }
            int msgType = msgTypeOpt.get();
            if (!proxy && msgType == DhcpPacket.MSG_DISCOVER) {
                sendOffer(socket, request);
                continue;
            }

            if (proxy && msgType == DhcpPacket.MSG_REQUEST) {
                var profile = classifyClient(request);
                if (profile.isEmpty()) {
                    System.out.printf("Ignored request xid=0x%08x: vendorClass=%s\n",
                        request.xid, vendorClass);
                    continue;
                }
                sendProxyAck(socket, request, datagram.getSocketAddress(), profile.get());
            }
        }
    }

    private void sendOffer(DatagramSocket socket, DhcpPacket request) {
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

        byte[] payload = offer.encode(DHCP_MIN_PACKET_SIZE);
        var address = new InetSocketAddress("255.255.255.255", 68);
        send(socket, payload, address, request.xid, "OFFER");
    }

    private void sendProxyAck(
        DatagramSocket socket,
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

        byte[] payload = ack.encode(DHCP_MIN_PACKET_SIZE);
        String ackKind = "ACK-" + profile.label;
        send(socket, payload, address, request.xid, ackKind);
    }

    private Optional<ClientProfile> classifyClient(DhcpPacket request) {
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

    private void send(DatagramSocket socket, byte[] payload, SocketAddress address, int xid, String kind) {
        try {
            DatagramPacket response = new DatagramPacket(payload, payload.length, address);
            socket.send(response);
            System.out.printf("Sent %s xid=0x%08x -> %s (%d bytes)\n", kind, xid, address, payload.length);
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
