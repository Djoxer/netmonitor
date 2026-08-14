package dev.djoxer.netmonitor.vpn;

import android.net.VpnService;
import android.util.Log;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class UdpForwarder {

    private static final String TAG = "UdpForwarder";

    private final VpnService vpnService;
    private final ConnectionTracker tracker;
    private final AtomicBoolean running;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private FileOutputStream tunOut;

    public UdpForwarder(VpnService vpnService, ConnectionTracker tracker, AtomicBoolean running) {
        this.vpnService = vpnService;
        this.tracker = tracker;
        this.running = running;
    }

    public void setTunOut(FileOutputStream tunOut) {
        this.tunOut = tunOut;
    }

    public int getLiveSessionCount() {
        return sessions.size();
    }

    public void handlePacket(byte[] data, int length) {
        try {
            int ipHeaderLen = (data[0] & 0x0F) * 4;
            if (length < ipHeaderLen + 8) return;

            byte[] srcIp = {data[12], data[13], data[14], data[15]};
            int srcPort = ((data[ipHeaderLen] & 0xFF) << 8) | (data[ipHeaderLen + 1] & 0xFF);
            byte[] dstIp = {data[16], data[17], data[18], data[19]};
            int dstPort = ((data[ipHeaderLen + 2] & 0xFF) << 8) | (data[ipHeaderLen + 3] & 0xFF);

            int payloadOffset = ipHeaderLen + 8;
            int payloadLen = length - payloadOffset;
            if (payloadLen <= 0) return;

            String remoteIp = InetAddress.getByAddress(dstIp).getHostAddress();
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            Session session = sessions.get(key);
            if (session == null) {
                DatagramSocket socket = new DatagramSocket();
                vpnService.protect(socket);
                socket.connect(InetAddress.getByAddress(dstIp), dstPort);

                session = new Session(socket, srcIp, srcPort, dstIp, dstPort, key);
                sessions.put(key, session);

                Thread t = new Thread(session, "UDP-" + key);
                t.setDaemon(true);
                t.start();
            }

            byte[] payload = new byte[payloadLen];
            System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
            session.socket.send(new DatagramPacket(payload, payload.length));
            tracker.udpForwarded.incrementAndGet();

        } catch (Exception e) {
            Log.w(TAG, "UDP handle error", e);
        }
    }

    public void shutdown() {
        for (Session s : sessions.values()) {
            try { s.socket.close(); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    private class Session implements Runnable {
        final DatagramSocket socket;
        final byte[] clientIp, remoteIp;
        final int clientPort, remotePort;
        final String key;

        Session(DatagramSocket socket, byte[] clientIp, int clientPort,
                byte[] remoteIp, int remotePort, String key) {
            this.socket = socket;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.key = key;
        }

        @Override
        public void run() {
            byte[] buf = new byte[65535];
            try {
                while (running.get() && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    byte[] response = PacketBuilder.buildUdp(
                            remoteIp, remotePort, clientIp, clientPort,
                            packet.getData(), packet.getOffset(), packet.getLength());
                    writeToTun(response);
                }
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
                sessions.remove(key);
            }
        }
    }

    private void writeToTun(byte[] packet) {
        if (tunOut == null) return;
        try {
            synchronized (tunOut) {
                tunOut.write(packet);
            }
        } catch (Exception e) {
            Log.w(TAG, "writeToTun failed", e);
        }
    }
}
