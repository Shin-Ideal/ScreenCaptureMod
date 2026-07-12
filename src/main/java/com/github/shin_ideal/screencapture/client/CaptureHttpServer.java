package com.github.shin_ideal.screencapture.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.github.shin_ideal.screencapture.Config;
import com.github.shin_ideal.screencapture.ScreenCaptureMod;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Self-hosted local HTTP server that serves the browser capture page and accepts JPEG frames.
 * Bound to 127.0.0.1 only.
 */
public class CaptureHttpServer {

    private final int port;
    private HttpServer httpServer;
    private byte[] indexHtml;

    public CaptureHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        indexHtml = loadIndexHtml();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.createContext("/", this::handleRoot);
        httpServer.createContext("/frame", this::handleFrame);
        httpServer.createContext("/config.json", this::handleConfig);
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ScreenCapture-HTTP");
            t.setDaemon(true);
            return t;
        }));
        httpServer.start();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, indexHtml.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(indexHtml);
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String json = "{\"fps\":" + Config.captureFps + ",\"maxWidth\":" + Config.maxFrameWidth + "}";
        sendText(exchange, 200, "application/json; charset=utf-8", json);
    }

    private void handleFrame(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }

        byte[] body = readAll(exchange.getRequestBody());
        CaptureSession.acceptJpegFrame(body);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        sendText(exchange, 204, "text/plain; charset=utf-8", "");
    }

    private static void sendText(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        if (code == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] loadIndexHtml() throws IOException {
        try (InputStream in = CaptureHttpServer.class.getResourceAsStream("/assets/screencapture/web/index.html")) {
            if (in == null) {
                throw new IOException("Missing resource: /assets/screencapture/web/index.html");
            }
            return readAll(in);
        } catch (IOException e) {
            ScreenCaptureMod.LOG.error("Failed to load capture page", e);
            throw e;
        }
    }
}
