package com.github.lory24.jubug;

import com.github.lory24.jubug.packets.handshaking.HandshakePacket;
import com.github.lory24.jubug.packets.login.LoginDisconnectPacket;
import com.github.lory24.jubug.packets.login.LoginStartPacket;
import com.github.lory24.jubug.packets.login.LoginSuccessPacket;
import com.github.lory24.jubug.packets.status.StatusPingPacket;
import com.github.lory24.jubug.packets.status.StatusPongPacket;
import com.github.lory24.jubug.packets.status.StatusResponsePacket;
import com.github.lory24.jubug.util.PacketsCheckingUtils;
import com.github.lory24.jubug.util.ServerProprieties;
import com.github.lory24.jubug.util.player.PlayerConnection;
import lombok.Getter;
import lombok.SneakyThrows;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

@SuppressWarnings({"FieldCanBeLocal", "MismatchedQueryAndUpdateOfCollection"})
public abstract class CraftServer {
    private final Logger logger;
    private ServerSocket serverSocket;
    @Getter private final HashMap<String, CraftPlayer> players;
    private final HashMap<CraftPlayer, Thread> playersThreads;
    @Getter private ServerProprieties serverProprieties;
    @Getter private final int PROTOCOL = 47;

    public CraftServer() {
        this.logger = new Logger(null, System.out);
        this.playersThreads = new HashMap<>();
        this.players = new HashMap<>();
    }

    @SneakyThrows
    protected void runServer() {
        getLogger().info("Loading default configuration");
        this.serverProprieties = new ServerProprieties();

        getLogger().info("Starting NET server...");
        this.serverSocket = new ServerSocket(serverProprieties.getPort());

        Thread connectionWaitingThread = new Thread(this::waitForConnections);
        connectionWaitingThread.start();
        getLogger().info("Server is waiting for connections...");
    }

    @SneakyThrows
    private void waitForConnections() {
        while (true) {
            Socket socket = serverSocket.accept();
            getLogger().info("New connection from " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
            Thread thread = new Thread(() -> doConnectionActions(socket));
            thread.start();
        }
    }

    @SneakyThrows
    private void doConnectionActions(Socket socket) {
        DataInputStream dataInputStream = new DataInputStream(socket.getInputStream()); // Server <- Client
        DataOutputStream dataOutputStream =
                new DataOutputStream(socket.getOutputStream()); // Server -> Client

        if (PacketsCheckingUtils.checkPacketLengthError(socket, dataInputStream) || PacketsCheckingUtils.checkPacketError(socket, dataInputStream, 0x00)) return;
        HandshakePacket handshakePacket = new HandshakePacket().readPacket(socket, dataInputStream);
        if (handshakePacket.getNextState() == 0x1) { // Status
            try {
                manageStatusRequest(socket, dataInputStream, dataOutputStream, handshakePacket.getProtocolVersion());
            } catch (Exception ignored) { }
        } else if (handshakePacket.getNextState() == 0x2) { // Login
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(220);
                    makePlayerJoin(socket, dataInputStream, dataOutputStream);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
            thread.start();
        } else {
            socket.close();
            getLogger().info("Connection from " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + " closed: Bad packet!");
        }
    }

    private void manageStatusRequest(Socket socket, DataInputStream dataInputStream, DataOutputStream dataOutputStream, int protocolVersion) throws Exception {

        // Read the first packet
        if (PacketsCheckingUtils.checkPacketLengthError(socket, dataInputStream) || PacketsCheckingUtils.checkPacketError(
                socket, dataInputStream, 0x00)) return;

        // Send the response packet
        StatusResponsePacket statusResponsePacket = new StatusResponsePacket(PROTOCOL, protocolVersion, getServerProprieties().getMaxPlayers(), players.size(), getServerProprieties().getMotd());
        statusResponsePacket.sendPacket(dataOutputStream);

        // READ THE PING
        if (PacketsCheckingUtils.checkPacketLengthError(socket, dataInputStream) || PacketsCheckingUtils.checkPacketError(socket, dataInputStream, 0x01)) return;
        StatusPingPacket statusPingPacket = new StatusPingPacket().readPacket(socket, dataInputStream);
        long payload = statusPingPacket.getPayload();

        // SEND THE PONG
        StatusPongPacket statusPongPacket = new StatusPongPacket(payload);
        statusPongPacket.sendPacket(dataOutputStream);

        socket.close();
    }

    @SneakyThrows
    private void makePlayerJoin(Socket socket, DataInputStream dataInputStream, DataOutputStream dataOutputStream) {

        if (PacketsCheckingUtils.checkPacketLengthError(socket, dataInputStream) || PacketsCheckingUtils.checkPacketError(socket, dataInputStream, 0x00)) return;
        LoginStartPacket loginStartPacket = new LoginStartPacket();
        loginStartPacket.readPacket(socket, dataInputStream);
        if (loginStartPacket.getName() == null) {
            LoginDisconnectPacket loginDisconnectPacket = new LoginDisconnectPacket();
            loginDisconnectPacket.sendPacket(dataOutputStream);
            getLogger().info("Client at " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + " disconnected: \"" + loginDisconnectPacket.getReason().getText() + "\"");
            socket.close();
            return;
        }

        LoginSuccessPacket loginSuccessPacket = new LoginSuccessPacket(loginStartPacket.getName());
        loginSuccessPacket.sendPacket(dataOutputStream);
        getLogger().info("UUID of player " + loginSuccessPacket.getNickname() + " is " + loginSuccessPacket.getUuid() + ". Logging in...");

        CraftPlayer craftPlayer = new CraftPlayer(new PlayerConnection(socket), loginSuccessPacket.getNickname(),
                loginSuccessPacket.getUuid());
        players.put(loginSuccessPacket.getNickname(), craftPlayer);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(450);
                players.get(loginSuccessPacket.getNickname()).startPlayerConnectionState(socket);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.start();
        playersThreads.put(craftPlayer, thread);
    }

    public void removePlayer(String player) {
        this.playersThreads.remove(this.players.get(player));
        this.players.remove(player);
    }

    public Logger getLogger() {
        return this.logger;
    }
}
