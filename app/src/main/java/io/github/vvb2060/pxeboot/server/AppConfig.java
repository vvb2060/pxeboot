package io.github.vvb2060.pxeboot.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class AppConfig {
    final InetAddress serverIp;
    final String fileRoot;
    final int httpPort;
    final boolean verbose;

    private AppConfig(InetAddress serverIp, String fileRoot, int httpPort, boolean verbose) {
        this.serverIp = serverIp;
        this.fileRoot = fileRoot;
        this.httpPort = httpPort;
        this.verbose = verbose;
    }

    private static InetAddress ipv4(String ip) {
        try {
            if (InetAddress.getByName(ip) instanceof Inet4Address ipv4) {
                return ipv4;
            }
            throw new IllegalArgumentException("Not an IPv4 address: " + ip);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid address: " + ip, e);
        }
    }

    static AppConfig fromArgs(String[] args) {
        String serverIp = "192.168.1.184";
        String fileRoot = "src/main/assets";
        int httpPort = 80;
        boolean verbose = true;

        for (var arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }

            var parts = arg.substring(2).split("=", 2);
            var key = parts[0];
            var value = parts[1];
            switch (key) {
                case "server-ip" -> serverIp = value;
                case "file-root" -> fileRoot = value;
                case "http-port" -> httpPort = Integer.parseInt(value);
                case "verbose" -> verbose = Boolean.parseBoolean(value);
                default -> {
                }
            }
        }

        return new AppConfig(ipv4(serverIp), fileRoot, httpPort, verbose);
    }

    @Override
    public String toString() {
        return "AppConfig[" +
            "serverIp=" + serverIp + ", " +
            "fileRoot=" + fileRoot + ", " +
            "httpPort=" + httpPort + ", " +
            "verbose=" + verbose + ']';
    }

}
