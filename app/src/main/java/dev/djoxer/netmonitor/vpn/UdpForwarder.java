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
    private static final long DNS_TIMEOUT_MS = 10_000;
    private static final int MAX_SESSIONS = 128;
    private static final int CLEAN_INTERVAL_MS = 10_000;
    private static final int RX_BUFFER = 2048;

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
            IpPacket ip = IpPacket.parse(data, length);
            if (ip == null || ip.protocol != 17) return;
            if (length < ip.headerLength + 8) return;

            int srcPort = ((data[ip.headerLength] & 0xFF) << 8) | (data[ip.headerLength + 1] & 0xFF);
            int dstPort = ((data[ip.headerLength + 2] & 0xFF) << 8) | (data[ip.headerLength + 3] & 0xFF);

            int payloadOffset = ip.headerLength + 8;
            int payloadLen = length - payloadOffset;
            if (payloadLen <= 0) return;

            String remoteIp = ip.dstIpStr;
            String key = ip.version + "|" + srcPort + "|" + remoteIp + "|" + dstPort;

            Session session = sessions.get(key);
            if (session == null) {
                if (sessions.size() >= MAX_SESSIONS) {
                    Log.w(TAG, "Max UDP sessions reached");
                    return;
                }

                int uid = tracker.resolveUidForForward(
                        OsConstants.IPPROTO_UDP,
                        ip.srcIpStr, srcPort,
                        remoteIp, dstPort);
                String packageName = tracker.getPackageForUid(uid);
                String appName = tracker.getAppNameForUid(uid);
                String blockKey = blockKeyFor(uid, packageName);

                if (isBlockedOut(uid, blockKey)) {
                    LogWriter.getInstance().log(
                            blockKey,
                            appName != null ? appName : blockKey,
                            "BLOCKED", "OUT",
                            "UDP " + remoteIp + ":" + dstPort);
                    return;
                }

                DatagramSocket socket = new DatagramSocket();
                vpnService.protect(socket);
                socket.connect(InetAddress.getByAddress(ip.dstIp), dstPort);

                long idleTimeout = (dstPort == 53 || srcPort == 53)
                        ? DNS_TIMEOUT_MS : SESSION_TIMEOUT_MS;

                session = new Session(socket, ip.srcIp, srcPort, ip.dstIp, dstPort, key,
                        uid, packageName, appName, blockKey, ip.version, idleTimeout);
                sessions.put(key, session);

                LogWriter.getInstance().log(
                        blockKey,
                        appName != null ? appName : blockKey,
                        "CONNECT", "OUT",
                        "UDP " + remoteIp + ":" + dstPort);

                Thread t = new Thread(session, "UDP-" + key);
                t.setDaemon(true);
                t.start();
            } else {
                if (isBlockedOut(session.uid, session.blockKey)) {
                    LogWriter.getInstance().log(
                            session.blockKey,
                            session.appName != null ? session.appName : session.blockKey,
                            "BLOCKED", "OUT",
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

    private static boolean isBlockedOut(int uid, String blockKey) {
        if (uid > 0 && BlockManager.getInstance().shouldBlockOut(uid)) return true;
        return blockKey != null && BlockManager.getInstance().shouldBlockOutPackage(blockKey);
    }

    private static boolean isBlockedIn(int uid, String blockKey) {
        if (uid > 0 && BlockManager.getInstance().shouldBlockIn(uid)) return true;
        return blockKey != null && BlockManager.getInstance().shouldBlockInPackage(blockKey);
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
                    if (now - s.lastActivityMs > s.idleTimeoutMs) {
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
        final int ipVersion;
        final long idleTimeoutMs;

        volatile long lastActivityMs;
        boolean loggedFirstInbound;

        Session(DatagramSocket socket, byte[] clientIp, int clientPort,
                byte[] remoteIp, int remotePort, String key,
                int uid, String packageName, String appName, String blockKey,
                int ipVersion, long idleTimeoutMs) {
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
            this.ipVersion = ipVersion;
            this.idleTimeoutMs = idleTimeoutMs;
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
            byte[] buf = new byte[RX_BUFFER];
            try {
                while (running.get() && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    touch();

                    String remoteIpStr;
                    try {
                        remoteIpStr = InetAddress.getByAddress(remoteIp).getHostAddress();
                    } catch (Exception e) {
                        remoteIpStr = "?";
                    }

                    if (!loggedFirstInbound) {
                        loggedFirstInbound = true;
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "RECV", "IN",
                                "UDP " + remoteIpStr + ":" + remotePort);
                    }

                    if (isBlockedIn(uid, blockKey)) {
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "BLOCKED", "IN",
                                "UDP " + remoteIpStr + ":" + remotePort);
                        break;
                    }

                    byte[] response;
                    if (ipVersion == 6) {
                        response = PacketBuilder.buildUdp6(
                                remoteIp, remotePort, clientIp, clientPort,
                                packet.getData(), packet.getOffset(), packet.getLength());
                    } else {
                        response = PacketBuilder.buildUdp(
                                remoteIp, remotePort, clientIp, clientPort,
                                packet.getData(), packet.getOffset(), packet.getLength());
                    }
                    writeToTun(response);

                    tracker.addForwardedTraffic(
                            "UDP",
                            clientPort,
                            remoteIpStr,
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
