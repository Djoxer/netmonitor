package dev.djoxer.netmonitor.vpn;

import android.net.VpnService;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.data.LogWriter;

public class TcpForwarder {

    private static final String TAG = "TcpForwarder";
    private static final long SESSION_TIMEOUT_MS = 60_000;
    private static final int MAX_SESSIONS = 128;

    private final VpnService vpnService;
    private final ConnectionTracker tracker;
    private final AtomicBoolean running;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private FileOutputStream tunOut;
    private Thread cleanerThread;

    public TcpForwarder(VpnService vpnService, ConnectionTracker tracker, AtomicBoolean running) {
        this.vpnService = vpnService;
        this.tracker = tracker;
        this.running = running;
    }

    public void setTunOut(FileOutputStream tunOut) {
        this.tunOut = tunOut;
    }

    public void start() {
        cleanerThread = new Thread(this::cleanupLoop, "TCP-Cleaner");
        cleanerThread.setDaemon(true);
        cleanerThread.start();
    }

    public int getLiveSessionCount() {
        return sessions.size();
    }

    public void handlePacket(byte[] data, int length) {
        try {
            int ipHeaderLen = (data[0] & 0x0F) * 4;
            if (length < ipHeaderLen + 20) return;

            byte[] srcIp = {data[12], data[13], data[14], data[15]};
            byte[] dstIp = {data[16], data[17], data[18], data[19]};
            int srcPort = ((data[ipHeaderLen] & 0xFF) << 8) | (data[ipHeaderLen + 1] & 0xFF);
            int dstPort = ((data[ipHeaderLen + 2] & 0xFF) << 8) | (data[ipHeaderLen + 3] & 0xFF);
            int seq = ByteBuffer.wrap(data, ipHeaderLen + 4, 4).getInt();

            int dataOffset = ((data[ipHeaderLen + 12] & 0xF0) >> 4) * 4;
            int flags = data[ipHeaderLen + 13] & 0xFF;
            boolean syn = (flags & 0x02) != 0;
            boolean fin = (flags & 0x01) != 0;
            boolean rst = (flags & 0x04) != 0;

            int payloadOffset = ipHeaderLen + dataOffset;
            int payloadLen = Math.max(0, length - payloadOffset);

            String localIpStr = ipv4String(srcIp);
            String remoteIp = ipv4String(dstIp);
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            Session session = sessions.get(key);

            if (rst) {
                if (session != null) {
                    session.close();
                    sessions.remove(key);
                }
                return;
            }

            if (session == null) {
                if (!syn) return;
                tracker.tcpSynSeen.incrementAndGet();

                if (sessions.size() >= MAX_SESSIONS) {
                    Log.w(TAG, "Max TCP sessions reached");
                    return;
                }

                int uid = tracker.resolveUidForForward(
                        OsConstants.IPPROTO_TCP,
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
                            "TCP " + remoteIp + ":" + dstPort);
                    return;
                }

                try {
                    Socket socket = new Socket();
                    vpnService.protect(socket);
                    socket.connect(new InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(0);

                    session = new Session(socket, srcIp, srcPort, dstIp, dstPort, seq, key,
                            uid, packageName, appName, blockKey);
                    sessions.put(key, session);
                    tracker.tcpSessionsCreated.incrementAndGet();

                    LogWriter.getInstance().log(
                            blockKey,
                            appName != null ? appName : blockKey,
                            "CONNECT",
                            "OUT",
                            "TCP " + remoteIp + ":" + dstPort);

                    byte[] synAck = PacketBuilder.buildTcp(
                            dstIp, dstPort, srcIp, srcPort,
                            session.serverSeq, seq + 1,
                            (byte) 0x12, null, 0, 0, true);
                    writeToTun(synAck);
                    session.serverSeq++;
                    session.touch();

                    Thread t = new Thread(session, "TCP-" + key);
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    tracker.tcpConnectErrors.incrementAndGet();
                    Log.w(TAG, "TCP connect failed " + remoteIp + ":" + dstPort, e);
                }
                return;
            }

            // Existing session
            if (isBlocked(session.uid, session.blockKey)) {
                LogWriter.getInstance().log(
                        session.blockKey,
                        session.appName != null ? session.appName : session.blockKey,
                        "BLOCKED",
                        "OUT",
                        "TCP session dropped " + remoteIp + ":" + dstPort);
                session.close();
                sessions.remove(key);
                return;
            }

            session.touch();

            if (payloadLen > 0) {
                tracker.tcpClientPayloads.incrementAndGet();
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
                session.out.write(payload);
                session.out.flush();
                tracker.tcpForwarded.addAndGet(payloadLen);
                session.clientSeq = seq + payloadLen;
            } else {
                session.clientSeq = Math.max(session.clientSeq, seq);
            }

            byte[] ackPkt = PacketBuilder.buildTcp(
                    dstIp, dstPort, srcIp, srcPort,
                    session.serverSeq, session.clientSeq,
                    (byte) 0x10, null, 0, 0, false);
            writeToTun(ackPkt);

            if (fin) {
                session.close();
                sessions.remove(key);
            }
        } catch (Exception e) {
            Log.w(TAG, "TCP handle error", e);
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
                Thread.sleep(10_000);
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Session> e = it.next();
                    Session s = e.getValue();
                    if (now - s.lastActivityMs > SESSION_TIMEOUT_MS) {
                        Log.i(TAG, "Timeout session " + e.getKey());
                        s.close();
                        it.remove();
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "cleanup error", e);
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
        final Socket socket;
        final OutputStream out;
        final InputStream in;
        final byte[] clientIp, remoteIp;
        final int clientPort, remotePort;
        final String key;
        final int uid;
        final String packageName;
        final String appName;
        final String blockKey;

        int clientSeq;
        int serverSeq;
        volatile long lastActivityMs;
        boolean loggedFirstInbound;

        Session(Socket socket, byte[] clientIp, int clientPort,
                byte[] remoteIp, int remotePort, int initialClientSeq, String key,
                int uid, String packageName, String appName, String blockKey) throws Exception {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = socket.getInputStream();
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.key = key;
            this.uid = uid;
            this.packageName = packageName;
            this.appName = appName;
            this.blockKey = blockKey;
            this.clientSeq = initialClientSeq + 1;
            this.serverSeq = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
            this.lastActivityMs = System.currentTimeMillis();
        }

        void touch() {
            lastActivityMs = System.currentTimeMillis();
        }

        @Override
        public void run() {
            byte[] buf = new byte[16384];
            try {
                while (running.get() && !socket.isClosed()) {
                    int read = in.read(buf);
                    if (read < 0) break;

                    if (!loggedFirstInbound) {
                        loggedFirstInbound = true;
                        String remoteIpStr = ipv4String(remoteIp);
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "RECV",
                                "IN",
                                "TCP " + remoteIpStr + ":" + remotePort);
                    }

                    if (isBlocked(uid, blockKey)) {
                        String remoteIpStr = ipv4String(remoteIp);
                        LogWriter.getInstance().log(
                                blockKey,
                                appName != null ? appName : blockKey,
                                "BLOCKED",
                                "IN",
                                "TCP " + remoteIpStr + ":" + remotePort);
                        break;
                    }

                    touch();
                    byte[] packet = PacketBuilder.buildTcp(
                            remoteIp, remotePort, clientIp, clientPort,
                            serverSeq, clientSeq,
                            (byte) 0x18, buf, 0, read, false);
                    writeToTun(packet);
                    serverSeq += read;
                    tracker.tcpForwarded.addAndGet(read);

                    tracker.addForwardedTraffic(
                            "TCP",
                            clientPort,
                            ipv4String(remoteIp),
                            remotePort,
                            read,
                            true);
                }
            } catch (Exception ignored) {
            } finally {
                close();
                sessions.remove(key);
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
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
