package dev.djoxer.netmonitor.vpn;

import android.net.VpnService;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.data.LogWriter;

public class UdpForwarder {

    private static final String TAG = "UdpForwarder";
    private static final long SESSION_TIMEOUT_MS = 60_000;
    private static final int MAX_SESSIONS = 128;
    private static final int CLEAN_INTERVAL_MS = 10_000;

    private final VpnService vpnService;
    private final ConnectionTracker tracker;
    private final AtomicBoolean running;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private FileOutputStream tunOut;
    private Thread cleanerThread;

    public UdpForwarder(VpnService vpnService, ConnectionTracker tracker, AtomicBoolean running) {
        this.vpnService = vpnService;
        this.tracker = tracker;
        this.running = running;
    }

    public void setTunOut(FileOutputStream tunOut) {
        this.tunOut = tunOut;
    }

    public void start() {
        if (cleanerThread != null) return;
        cleanerThread = new Thread(this::cleanupLoop, "UDP-Cleaner");
        cleanerThread.setDaemon(true);
        cleanerThread.start();
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

            String localIpStr = ipv4String(srcIp);
            String remoteIp = ipv4String(dstIp);
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            Session session = sessions.get(key);
            if (session == null) {
                if (sessions.size() >= MAX_SESSIONS) {
                    Log.w(TAG, "Max UDP sessions reached, dropping new flow");
                    return;
                }

                int uid = tracker.resolveUidForForward(
                        OsConstants.IPPROTO_UDP,
                        localIpStr, srcPort,
                        remoteIp, dstPort);
                String packageName = tracker.getPackageForUid(uid);
                String appName = tracker.getAppNameForUid(uid);
                String blockKey = blockKeyFor(uid, packageName);

                if (isBlocked(uid, blockKey)) {
                    LogWriter.getInstance().log(
                            blockKey,
                            appName != null ? appName : blockKey,
                            "BLOCKED",
                            "OUT",
                            "UDP " + remoteIp + ":" + dstPort);
                    return;
                }

                DatagramSocket socket = new DatagramSocket();
                vpnService.protect(socket);
                socket.connect(InetAddress.getByAddress(dstIp), dstPort);

                session = new Session(socket, srcIp, srcPort, dstIp, dstPort, key,
                        uid, packageName, appName, blockKey);
                sessions.put(key, session);

                LogWriter.getInstance().log(
                        blockKey,
                        appName != null ? appName : blockKey,
                        "CONNECT",
                        "OUT",
                        "UDP " + remoteIp + ":" + dstPort);

                Thread t = new Thread(session, "UDP-" + key);
                t.setDaemon(true);
                t.start();
            } else {
                if (isBlocked(session.uid, session.blockKey)) {
                    LogWriter.getInstance().log(
                            session.blockKey,
                            session.appName != null ? session.appName : session.blockKey,
                            "BLOCKED",
                            "OUT",
                            "UDP session dropped " + remoteIp + ":" + dstPort);
                    session.close();
                    sessions.remove(key);
                    return;
                }
            }

            byte[] payload = new byte[payloadLen];
            System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
            session.socket.send(new DatagramPacket(payload, payload.length));
            session.touch();
            tracker.udpForwarded.incrementAndGet();

        } catch (Exception e) {
            Log.w(TAG, "UDP handle error", e);
        }
    }

    private static String blockKeyFor(int uid, String packageName) {
        if (packageName != null && !packageName.isEmpty()) return packageName;
        if (uid > 0) return "uid:" + uid;
        return "unknown";
    }

    private static boolean isBlocked(int uid, String blockKey) {
        if (uid > 0 && BlockManager.getInstance().shouldBlock(uid)) return true;
        return blockKey != null && BlockManager.getInstance().shouldBlockPackage(blockKey);
    }

    private static String ipv4String(byte[] ip) {
        return (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "."
                + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
    }

    private void cleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(CLEAN_INTERVAL_MS);
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Session> e = it.next();
                    Session s = e.getValue();
                    if (now - s.lastActivityMs > SESSION_TIMEOUT_MS) {
                        Log.i(TAG, "UDP timeout " + e.getKey());
                        s.close();
                        it.remove();
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "UDP cleanup error", e);
            }
        }
    }

    public void shutdown() {
        if (cleanerThread != null) {
            cleanerThread.interrupt();
            cleanerThread = null;
        }
        for (Session s : sessions.values()) {
            s.close();
        }
        sessions.clear();
    }

    private class Session implements Runnable {
        final DatagramSocket socket;
        final byte[] clientIp, remoteIp;
        final int clientPort, remotePort;
        final String key;
        final int uid;
        final String packageName;
        final String appName;
        final String blockKey;

        volatile long lastActivityMs;
        boolean loggedFirstInbound;

        Session(DatagramSocket socket, byte[] clientIp, int clientPort,
                byte[] remoteIp, int remotePort, String key,
                int uid, String packageName, String appName, String blockKey) {
            this.socket = socket;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.key = key;
            this.uid = uid;
            this.packageName = packageName;
            this.appName = appName;
            this.blockKey = blockKey;
            this.lastActivityMs = System.currentTimeMillis();
        }

        void touch() {
            lastActivityMs = System.currentTimeMillis();
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void run() {
            byte[] buf = new byte[65535];
            try {
                while (running.get() && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    touch();

                    if (!loggedFirstInbound) {
                        loggedFirstInbound = true;
                        String remoteIpStr = ipv4String(remoteIp);
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "RECV",
                                "IN",
                                "UDP " + remoteIpStr + ":" + remotePort);
                    }

                    if (isBlocked(uid, blockKey)) {
                        String remoteIpStr = ipv4String(remoteIp);
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "BLOCKED",
                                "IN",
                                "UDP " + remoteIpStr + ":" + remotePort);
                        break;
                    }

                    byte[] response = PacketBuilder.buildUdp(
                            remoteIp, remotePort, clientIp, clientPort,
                            packet.getData(), packet.getOffset(), packet.getLength());
                    writeToTun(response);

                    tracker.addForwardedTraffic(
                            "UDP",
                            clientPort,
                            ipv4String(remoteIp),
                            remotePort,
                            packet.getLength(),
                            true);
                }
            } catch (Exception ignored) {
            } finally {
                close();
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
