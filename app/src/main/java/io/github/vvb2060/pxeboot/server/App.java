package io.github.vvb2060.pxeboot.server;

public class App {

    public static void main(String[] args) {
        var config = AppConfig.fromArgs(args);
        var server = new DhcpServer(config);

        var offerThread = new Thread(server::serveOffer, "dhcp-offer");
        var ackThread = new Thread(server::serveProxy, "proxydhcp-ack");
        var tftpThread = new Thread(() -> new TftpServer(config).start(), "tftp-server");
        var httpThread = new Thread(() -> new HttpFileServer(config).start(), "http-server");

        offerThread.start();
        ackThread.start();
        tftpThread.start();
        httpThread.start();

        System.out.printf(config +"\n");

        try {
            offerThread.join();
            ackThread.join();
            tftpThread.join();
            httpThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", e);
        }
    }
}
