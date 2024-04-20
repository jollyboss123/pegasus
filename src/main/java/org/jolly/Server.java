package org.jolly;

import com.softwaremill.jox.Channel;
import com.softwaremill.jox.Select;
import org.jolly.command.Command;
import org.jolly.command.SetCommand;
import org.jolly.protocol.ArrayToken;
import org.jolly.protocol.Decoder;
import org.jolly.protocol.Token;
import org.jolly.protocol.TokenType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class Server {
    private static final Logger log = Logger.getLogger(Server.class.getName());
    private final Config cfg;
    private final Map<Peer, Boolean> peers;
    private final Channel<Peer> addPeerCh;
    private final Channel<?> quitCh;
    private final Channel<byte[]> msgCh;
    private AtomicBoolean running = new AtomicBoolean(true);
    private static final int DEFAULT_PORT = 5001;

    private Server(Config cfg) {
        this(cfg, new ConcurrentHashMap<>(), new Channel<>(), new Channel<>(), new Channel<>());
    }

    private Server(Config cfg, Map<Peer, Boolean> peers, Channel<Peer> addPeerCh, Channel<?> quitCh, Channel<byte[]> msgCh) {
        this.cfg = cfg;
        this.peers = peers;
        this.addPeerCh = addPeerCh;
        this.quitCh = quitCh;
        this.msgCh = msgCh;
    }

    public static Server create(Config cfg) {
        if (cfg.getPort() == null) {
            cfg = new Config(DEFAULT_PORT);
        }
        return new Server(cfg);
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(cfg.getPort())) {
            log.info(() -> "starting server ip: %s port: %d".formatted(serverSocket.getInetAddress().getHostAddress(), serverSocket.getLocalPort()));

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(this::loop);
            }

            log.info(() -> "server running at port %d".formatted(this.cfg.getPort()));

            acceptLoop(serverSocket);
        } catch (IOException e) {
            log.severe(() -> "failed to start server ip: %s".formatted(e.getMessage()));
            System.exit(1);
        }
    }

    private Thread loop() {
        return Thread.startVirtualThread(() -> {
            try {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    Select.select(
                            addPeerCh.receiveClause(peer -> {
                                log.info(() -> "peer connected: %s".formatted(peer));
                                peers.put(peer, true);
                                return null;
                            }),
                            quitCh.receiveClause(q -> {
                                running.getAndSet(false);
                                cleanup();
                                return null;
                            }),
                            msgCh.receiveClause(rawMsg -> {
                                handleRawMessage(rawMsg);
                                return null;
                            })
                    );
                }

            } catch (InterruptedException e) {
                log.warning(() -> "receiving thread interrupted");
                Thread.currentThread().interrupt();
            } finally {
                log.info(() -> "event loop terminated");
            }
        });
    }

    private void acceptLoop(ServerSocket ss) throws IOException {
        try (Socket clientSocket = ss.accept()) {
            log.info(() -> "accepting client connection: %s".formatted(clientSocket));
            handleConn(clientSocket);
        } catch (IOException e) {
            log.severe(() -> "failed to accept client socket");
            throw e;
        }
    }

    private void handleConn(Socket clientSocket) throws IOException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Peer p = Peer.create(clientSocket, this.msgCh);
            executor.submit(() -> {
                try {
                    log.info(() -> "peer send: %s".formatted(clientSocket.getRemoteSocketAddress().toString()));
                    addPeerCh.send(p);
                } catch (InterruptedException e) {
                    log.warning(() -> "add peer thread interrupted");
                    Thread.currentThread().interrupt();
                }
            });
            p.readLoop();
        } catch (IOException e) {
            throw e;
        }
    }

    private void handleRawMessage(byte[] rawMsg) {
        log.info(() -> new String(rawMsg));
        Proto.parseCommand(rawMsg);
    }

    private void cleanup() {
        // to be completed
    }
}
