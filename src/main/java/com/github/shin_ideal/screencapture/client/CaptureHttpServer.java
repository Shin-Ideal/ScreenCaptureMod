package com.github.shin_ideal.screencapture.client;

import com.github.shin_ideal.screencapture.Config;
import com.github.shin_ideal.screencapture.ScreenCaptureMod;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * High-performance self-hosted local HTTP and WebSocket server using Netty.
 * Bound to 127.0.0.1 only.
 */
public class CaptureHttpServer {

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private byte[] indexHtml;

    public CaptureHttpServer(int port) {
        this.port = port;
    }

    public synchronized void start() throws IOException {
        indexHtml = loadIndexHtml();
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("Capture-Netty-Boss", true));
        workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("Capture-Netty-Worker", true));

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("httpCodec", new HttpServerCodec());
                        p.addLast("aggregator", new HttpObjectAggregator(64 * 1024 * 1024));
                        p.addLast("handler", new CaptureServerHandler(indexHtml, port));
                    }
                });

            ChannelFuture future = b.bind(new InetSocketAddress("127.0.0.1", port)).sync();
            serverChannel = future.channel();
            ScreenCaptureMod.LOG.info("Netty Capture Server bound to 127.0.0.1:" + port);
        } catch (Exception e) {
            stop();
            throw new IOException("Failed to start Netty Capture Server on port " + port, e);
        }
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    private static class CaptureServerHandler extends SimpleChannelInboundHandler<Object> {
        private final byte[] indexHtml;
        private final int port;
        private WebSocketServerHandshaker handshaker;

        public CaptureServerHandler(byte[] indexHtml, int port) {
            this.indexHtml = indexHtml;
            this.port = port;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest) {
                handleHttpRequest(ctx, (FullHttpRequest) msg);
            } else if (msg instanceof WebSocketFrame) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.getUri();

            // WebSocket Upgrade Request for /ws
            if (uri.startsWith("/ws")) {
                String host = req.headers().get("Host");
                if (host == null || host.isEmpty()) {
                    host = "127.0.0.1:" + port;
                }
                String location = "ws://" + host + "/ws";
                WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    location, null, true, 64 * 1024 * 1024);
                handshaker = wsFactory.newHandshaker(req);
                if (handshaker == null) {
                    ScreenCaptureMod.LOG.warn("Unsupported WebSocket version requested from " + ctx.channel().remoteAddress());
                    WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());
                } else {
                    ChannelFuture handshakeFuture = handshaker.handshake(ctx.channel(), req);
                    handshakeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            if (future.isSuccess()) {
                                ScreenCaptureMod.LOG.info("WebSocket handshake successful from " + ctx.channel().remoteAddress());
                                ctx.pipeline().addBefore("handler", "wsAggregator", new WebSocketFrameAggregator(64 * 1024 * 1024));
                            } else {
                                ScreenCaptureMod.LOG.error("WebSocket handshake failed", future.cause());
                            }
                        }
                    });
                }
                return;
            }

            if (HttpMethod.OPTIONS.equals(req.getMethod())) {
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
                res.headers().set("Access-Control-Allow-Origin", "*");
                res.headers().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                res.headers().set("Access-Control-Allow-Headers", "Content-Type");
                ctx.writeAndFlush(res);
                return;
            }

            if ("/config.json".equals(uri)) {
                String json = "{\"fps\":" + Config.captureFps + ",\"maxWidth\":" + Config.maxFrameWidth + "}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
                res.headers().set("Content-Type", "application/json; charset=utf-8");
                res.headers().set("Cache-Control", "no-store");
                res.headers().set("Access-Control-Allow-Origin", "*");
                HttpHeaders.setContentLength(res, bytes.length);
                ctx.writeAndFlush(res);
                return;
            }

            if ("/frame".equals(uri) && HttpMethod.POST.equals(req.getMethod())) {
                ByteBuf content = req.content();
                if (content.readableBytes() >= 5) {
                    content.markReaderIndex();
                    byte format = content.readByte();
                    int width = content.readUnsignedShort();
                    int height = content.readUnsignedShort();

                    if (format == 1) { // RAW_RGBA
                        int expectedBytes = width * height * 4;
                        if (content.readableBytes() >= expectedBytes) {
                            byte[] bytes = new byte[expectedBytes];
                            content.readBytes(bytes);
                            CaptureSession.acceptRawFrame(width, height, bytes, 0, expectedBytes);
                            sendNoContent(ctx);
                            return;
                        }
                    } else if (format == 2) { // JPEG with header
                        byte[] bytes = new byte[content.readableBytes()];
                        content.readBytes(bytes);
                        CaptureSession.acceptJpegFrame(bytes);
                        sendNoContent(ctx);
                        return;
                    }
                    content.resetReaderIndex();
                }

                // Legacy JPEG without 5-byte header
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                CaptureSession.acceptJpegFrame(bytes);
                sendNoContent(ctx);
                return;
            }

            // Default route "/" or index
            if ("/".equals(uri) || uri.startsWith("/index")) {
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(indexHtml));
                res.headers().set("Content-Type", "text/html; charset=utf-8");
                res.headers().set("Cache-Control", "no-store");
                HttpHeaders.setContentLength(res, indexHtml.length);
                ctx.writeAndFlush(res);
                return;
            }

            // 404
            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            ctx.writeAndFlush(res);
        }

        private static void sendNoContent(ChannelHandlerContext ctx) {
            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            res.headers().set("Access-Control-Allow-Origin", "*");
            ctx.writeAndFlush(res);
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                if (handshaker != null) {
                    handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                }
                return;
            }
            if (frame instanceof PingWebSocketFrame) {
                ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }
            if (frame instanceof TextWebSocketFrame) {
                // Client text message, ignore or log
                return;
            }
            if (frame instanceof BinaryWebSocketFrame) {
                ByteBuf content = frame.content();
                if (content.readableBytes() < 5) {
                    return;
                }
                byte format = content.readByte();
                int width = content.readUnsignedShort();
                int height = content.readUnsignedShort();

                if (format == 1) { // RAW_RGBA
                    int expectedBytes = width * height * 4;
                    if (content.readableBytes() >= expectedBytes) {
                        byte[] bytes = new byte[expectedBytes];
                        content.readBytes(bytes);
                        CaptureSession.acceptRawFrame(width, height, bytes, 0, expectedBytes);
                    } else {
                        ScreenCaptureMod.LOG.warn("Incomplete RAW frame: expected " + expectedBytes + " bytes, got " + content.readableBytes());
                    }
                } else if (format == 2) { // JPEG
                    byte[] bytes = new byte[content.readableBytes()];
                    content.readBytes(bytes);
                    CaptureSession.acceptJpegFrame(bytes);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ScreenCaptureMod.LOG.error("CaptureServerHandler error from " + ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
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

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
