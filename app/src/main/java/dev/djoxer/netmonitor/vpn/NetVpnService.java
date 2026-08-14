package dev.djoxer.netmonitor.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.djoxer.netmonitor.MainActivity;

public class NetVpnService extends VpnService {

    private static final String TAG = "NetVpnService";

    public static final String ACTION_START = "dev.djoxer.netmonitor.START_VPN";
    public static final String ACTION_STOP  = "dev.djoxer.netmonitor.STOP_VPN";
    public static final String EXTRA_BLOCK_MODE = "block_mode";

    private static final String CHANNEL_ID = "netmonitor_vpn";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean blockMode = false;

    public static final AtomicLong udpForwarded = new AtomicLong(0);
    public static final AtomicLong tcpForwarded = new AtomicLong(0);
    public static final AtomicLong tcpSeen = new AtomicLong(0);
    public static final AtomicLong tcpSynSeen = new AtomicLong(0);
    public static final AtomicLong tcpSessionsCreated = new AtomicLong(0);
    public static final AtomicLong tcpConnectErrors = new AtomicLong(0);
    public static final AtomicLong tcpClientPayloads = new AtomicLong(0);
    public static final AtomicLong packetsSeen = new AtomicLong(0);

    private static volatile int liveUdpSessions = 0;
    private static volatile int liveTcpSessions = 0;

    public static int getLiveUdpSessions() { return liveUdpSessions; }
    public static int getLiveTcpSessions() { return liveTcpSessions; }

    private ParcelFileDescriptor vpnInterface = null;
    private Thread captureThread = null;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private FileOutputStream tunOut = null;

    private ConnectivityManager connectivityManager;
    private PackageManager packageManager;

    private final Map<Integer, String> uidToPackage = new HashMap<>();
    private final Map<Integer, String> uidToAppName = new HashMap<>();

    private final Map<String, UdpSession> udpSessions = new ConcurrentHashMap<>();
    private final Map<String, TcpSession> tcpSessions = new ConcurrentHashMap<>();

    private static final Map<String, ConnectionInfo> connections = new LinkedHashMap<>(400, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ConnectionInfo> eldest) {
            return size() > 400;
        }
    };

    public static List<ConnectionInfo> getConnections() {
        synchronized (connections) {
            return new ArrayList<>(connections.values());
        }
    }

    public static void clearConnections() {
        synchronized (connections) {
            connections.clear();
        }
        udpForwarded.set(0);
        tcpForwarded.set(0);
        tcpSeen.set(0);
        tcpSynSeen.set(0);
        tcpSessionsCreated.set(0);
        tcpConnectErrors.set(0);
        tcpClientPayloads.set(0);
        packetsSeen.set(0);
    }

    public static boolean isBlockMode() {
        return blockMode;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        connectivityManager = getSystemService(ConnectivityManager.class);
        packageManager = getPackageManager();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            blockMode = intent.getBooleanExtra(EXTRA_BLOCK_MODE, false);
        }
        startForeground(NOTIFICATION_ID, buildNotification(
                blockMode ? "Block mode" : "Forward mode (UDP+TCP)"));
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (running.get()) return;
        try {
            Builder builder = new Builder();
            builder.setSession("NetMonitor");
            builder.setMtu(1500);
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            try {
                builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
                builder.addRoute("::", 0);
            } catch (Exception e) {
                Log.w(TAG, "IPv6 not available", e);
            }
            builder.addDisallowedApplication(getPackageName());
            builder.setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                stopSelf();
                return;
            }

            running.set(true);
            updateNotification(blockMode ? "Block mode active" : "Forward mode (UDP+TCP)");

            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            stopVpn();
            stopSelf();
        }
    }

    private void captureLoop() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(vpnInterface.getFileDescriptor());
            tunOut = new FileOutputStream(vpnInterface.getFileDescriptor());
            byte[] buffer = new byte[32767];

            while (running.get()) {
                int length = in.read(buffer);
                if (length <= 0) continue;

                packetsSeen.incrementAndGet();
                parsePacket(buffer, length);
                if (blockMode) continue;

                int version = (buffer[0] >> 4) & 0x0F;
                if (version != 4) continue;

                int protocol = buffer[9] & 0xFF;
                if (protocol == 17) {
                    handleUdpForward(buffer, length);
                } else if (protocol == 6) {
                    tcpSeen.incrementAndGet();
                    handleTcpForward(buffer, length);
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "captureLoop error", e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (tunOut != null) tunOut.close(); } catch (Exception ignored) {}
            tunOut = null;
        }
    }

    // ===================== UDP =====================

    private void handleUdpForward(byte[] data, int length) {
        try {
            int ipHeaderLen = (data[0] & 0x0F) * 4;
            if (length < ipHeaderLen + 8) return;

            byte[] srcIpBytes = {data[12], data[13], data[14], data[15]};
            int srcPort = ((data[ipHeaderLen] & 0xFF) << 8) | (data[ipHeaderLen + 1] & 0xFF);
            byte[] dstIpBytes = {data[16], data[17], data[18], data[19]};
            int dstPort = ((data[ipHeaderLen + 2] & 0xFF) << 8) | (data[ipHeaderLen + 3] & 0xFF);

            int payloadOffset = ipHeaderLen + 8;
            int payloadLen = length - payloadOffset;
            if (payloadLen <= 0) return;

            String remoteIp = InetAddress.getByAddress(dstIpBytes).getHostAddress();
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            UdpSession session = udpSessions.get(key);
            if (session == null) {
                DatagramSocket socket = new DatagramSocket();
                protect(socket);
                socket.connect(InetAddress.getByAddress(dstIpBytes), dstPort);
                session = new UdpSession(socket, srcIpBytes, srcPort, dstIpBytes, dstPort);
                udpSessions.put(key, session);
                liveUdpSessions = udpSessions.size();
                Thread t = new Thread(session, "UDP-" + key);
                t.setDaemon(true);
                t.start();
            }

            byte[] payload = new byte[payloadLen];
            System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
            session.socket.send(new DatagramPacket(payload, payload.length));
            udpForwarded.incrementAndGet();
        } catch (Exception e) {
            Log.w(TAG, "UDP forward error", e);
        }
    }

    private class UdpSession implements Runnable {
        final DatagramSocket socket;
        final byte[] clientIp, remoteIp;
        final int clientPort, remotePort;

        UdpSession(DatagramSocket socket, byte[] clientIp, int clientPort,
                   byte[] remoteIp, int remotePort) {
            this.socket = socket;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
        }

        @Override
        public void run() {
            byte[] buf = new byte[65535];
            try {
                while (running.get() && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    writeToTun(buildUdpPacket(remoteIp, remotePort, clientIp, clientPort,
                            packet.getData(), packet.getOffset(), packet.getLength()));
                }
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
                udpSessions.values().remove(this);
                liveUdpSessions = udpSessions.size();
            }
        }
    }

    private byte[] buildUdpPacket(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
                                  byte[] payload, int offset, int len) {
        int totalLen = 20 + 8 + len;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.put((byte) 0x45); buf.put((byte) 0);
        buf.putShort((short) totalLen); buf.putShort((short) 0); buf.putShort((short) 0x4000);
        buf.put((byte) 64); buf.put((byte) 17); buf.putShort((short) 0);
        buf.put(srcIp); buf.put(dstIp);
        buf.putShort((short) srcPort); buf.putShort((short) dstPort);
        buf.putShort((short) (8 + len)); buf.putShort((short) 0);
        buf.put(payload, offset, len);
        return buf.array();
    }

    // ===================== TCP =====================

    private void handleTcpForward(byte[] data, int length) {
        try {
            int ipHeaderLen = (data[0] & 0x0F) * 4;
            if (length < ipHeaderLen + 20) return;

            byte[] srcIpBytes = {data[12], data[13], data[14], data[15]};
            byte[] dstIpBytes = {data[16], data[17], data[18], data[19]};
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

            String remoteIp = InetAddress.getByAddress(dstIpBytes).getHostAddress();
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            TcpSession session = tcpSessions.get(key);

            if (rst) {
                if (session != null) {
                    session.close();
                    tcpSessions.remove(key);
                    liveTcpSessions = tcpSessions.size();
                }
                return;
            }

            if (session == null) {
                if (!syn) return;
                tcpSynSeen.incrementAndGet();

                try {
                    Socket socket = new Socket();
                    protect(socket);
                    socket.connect(new InetSocketAddress(InetAddress.getByAddress(dstIpBytes), dstPort), 10000);
                    socket.setTcpNoDelay(true);

                    session = new TcpSession(socket, srcIpBytes, srcPort, dstIpBytes, dstPort, seq);
                    tcpSessions.put(key, session);
                    liveTcpSessions = tcpSessions.size();
                    tcpSessionsCreated.incrementAndGet();

                    // SYN-ACK **mit MSS-Option**
                    byte[] synAck = buildTcpPacket(
                            dstIpBytes, dstPort,
                            srcIpBytes, srcPort,
                            session.serverSeq, seq + 1,
                            (byte) 0x12, // SYN+ACK
                            null, 0, 0,
                            true); // include MSS
                    writeToTun(synAck);
                    session.serverSeq++;

                    Thread t = new Thread(session, "TCP-" + key);
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    tcpConnectErrors.incrementAndGet();
                    Log.w(TAG, "TCP connect failed " + remoteIp + ":" + dstPort, e);
                }
                return;
            }

            // Client data → real server
            if (payloadLen > 0) {
                tcpClientPayloads.incrementAndGet();
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
                session.out.write(payload);
                session.out.flush();
                tcpForwarded.addAndGet(payloadLen);
                session.clientSeq = seq + payloadLen;
            } else {
                // pure ACK – keep clientSeq in sync if possible
                session.clientSeq = Math.max(session.clientSeq, seq);
            }

            // ACK back to client
            byte[] ackPkt = buildTcpPacket(
                    dstIpBytes, dstPort,
                    srcIpBytes, srcPort,
                    session.serverSeq, session.clientSeq,
                    (byte) 0x10,
                    null, 0, 0,
                    false);
            writeToTun(ackPkt);

            if (fin) {
                session.close();
                tcpSessions.remove(key);
                liveTcpSessions = tcpSessions.size();
            }
        } catch (Exception e) {
            Log.w(TAG, "TCP forward error", e);
        }
    }

    private class TcpSession implements Runnable {
        final Socket socket;
        final OutputStream out;
        final InputStream in;
        final byte[] clientIp, remoteIp;
        final int clientPort, remotePort;
        int clientSeq;
        int serverSeq;

        TcpSession(Socket socket, byte[] clientIp, int clientPort,
                   byte[] remoteIp, int remotePort, int initialClientSeq) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = socket.getInputStream();
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.clientSeq = initialClientSeq + 1;
            this.serverSeq = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        }

        @Override
        public void run() {
            byte[] buf = new byte[16384];
            try {
                while (running.get() && !socket.isClosed()) {
                    int read = in.read(buf);
                    if (read < 0) break;

                    byte[] packet = buildTcpPacket(
                            remoteIp, remotePort,
                            clientIp, clientPort,
                            serverSeq, clientSeq,
                            (byte) 0x18, // PSH+ACK
                            buf, 0, read,
                            false);
                    writeToTun(packet);
                    serverSeq += read;
                    tcpForwarded.addAndGet(read);
                }
            } catch (Exception ignored) {
            } finally {
                close();
                tcpSessions.values().remove(this);
                liveTcpSessions = tcpSessions.size();
            }
        }

        void close() {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Build a TCP packet with correct IP + TCP checksums.
     * @param withMss add MSS option (for SYN-ACK)
     */
    private byte[] buildTcpPacket(byte[] srcIp, int srcPort,
                                  byte[] dstIp, int dstPort,
                                  int seq, int ack,
                                  byte flags,
                                  byte[] payload, int offset, int len,
                                  boolean withMss) {
        if (payload == null) len = 0;

        int tcpHeaderLen = withMss ? 24 : 20;
        int totalLen = 20 + tcpHeaderLen + len;
        byte[] packet = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(packet);

        // ----- IP Header -----
        buf.put((byte) 0x45);                    // Version + IHL
        buf.put((byte) 0);                       // DSCP
        buf.putShort((short) totalLen);          // Total length
        buf.putShort((short) 0);                 // Identification
        buf.putShort((short) 0x4000);            // Don't Fragment
        buf.put((byte) 64);                      // TTL
        buf.put((byte) 6);                       // Protocol TCP
        buf.putShort((short) 0);                 // Checksum placeholder
        buf.put(srcIp);
        buf.put(dstIp);

        // IP checksum
        int ipChecksum = ipChecksum(packet, 0, 20);
        packet[10] = (byte) (ipChecksum >> 8);
        packet[11] = (byte) (ipChecksum);

        // ----- TCP Header -----
        int tcpStart = 20;
        buf.position(tcpStart);
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putInt(seq);
        buf.putInt(ack);
        buf.put((byte) ((tcpHeaderLen / 4) << 4)); // data offset
        buf.put(flags);
        buf.putShort((short) 65535);             // window
        buf.putShort((short) 0);                 // checksum placeholder
        buf.putShort((short) 0);                 // urgent pointer

        if (withMss) {
            // MSS option: kind=2, len=4, mss=1460
            buf.put((byte) 2);
            buf.put((byte) 4);
            buf.putShort((short) 1460);
        }

        if (len > 0) {
            buf.put(payload, offset, len);
        }

        // TCP checksum (includes pseudo-header)
        int tcpLen = tcpHeaderLen + len;
        int tcpChecksum = tcpChecksum(srcIp, dstIp, packet, tcpStart, tcpLen);
        packet[tcpStart + 16] = (byte) (tcpChecksum >> 8);
        packet[tcpStart + 17] = (byte) (tcpChecksum);

        return packet;
    }

    /** Standard IP header checksum */
    private int ipChecksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        }
        if ((length & 1) != 0) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    /** TCP checksum with IPv4 pseudo-header */
    private int tcpChecksum(byte[] srcIp, byte[] dstIp,
                            byte[] tcpPacket, int tcpOffset, int tcpLength) {
        int sum = 0;

        // Pseudo-header
        sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
        sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
        sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
        sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
        sum += 6;                    // protocol TCP
        sum += tcpLength;

        // TCP header + payload
        for (int i = tcpOffset; i < tcpOffset + tcpLength - 1; i += 2) {
            sum += ((tcpPacket[i] & 0xFF) << 8) | (tcpPacket[i + 1] & 0xFF);
        }
        if ((tcpLength & 1) != 0) {
            sum += (tcpPacket[tcpOffset + tcpLength - 1] & 0xFF) << 8;
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    private void writeToTun(byte[] packet) {
        if (tunOut == null) return;
        try {
            synchronized (tunOut) {
                tunOut.write(packet);
            }
        } catch (IOException e) {
            Log.w(TAG, "writeToTun failed", e);
        }
    }

    // ===================== Tracking =====================

    private void parsePacket(byte[] data, int length) {
        if (length < 20) return;
        int version = (data[0] >> 4) & 0x0F;
        if (version != 4) return;
        int headerLength = (data[0] & 0x0F) * 4;
        if (length < headerLength + 4) return;

        int protocol = data[9] & 0xFF;
        String protoName;
        int osProto;
        if (protocol == 6) {
            protoName = "TCP";
            osProto = OsConstants.IPPROTO_TCP;
        } else if (protocol == 17) {
            protoName = "UDP";
            osProto = OsConstants.IPPROTO_UDP;
        } else return;

        String srcIp = (data[12] & 0xFF) + "." + (data[13] & 0xFF) + "." +
                (data[14] & 0xFF) + "." + (data[15] & 0xFF);
        int srcPort = ((data[headerLength] & 0xFF) << 8) | (data[headerLength + 1] & 0xFF);
        String destIp = (data[16] & 0xFF) + "." + (data[17] & 0xFF) + "." +
                (data[18] & 0xFF) + "." + (data[19] & 0xFF);
        int destPort = ((data[headerLength + 2] & 0xFF) << 8) | (data[headerLength + 3] & 0xFF);
        if (destIp.startsWith("10.0.0.") || destPort == 0) return;

        String key = protoName + "|" + destIp + "|" + destPort;
        synchronized (connections) {
            ConnectionInfo info = connections.get(key);
            if (info == null) {
                info = new ConnectionInfo(protoName, destIp, destPort);
                connections.put(key, info);
                tryResolveUid(info, osProto, srcIp, srcPort, destIp, destPort);
            } else {
                info.packetCount++;
            }
            info.bytes += length;
        }
    }

    private void tryResolveUid(ConnectionInfo info, int protocol,
                               String localIp, int localPort,
                               String remoteIp, int remotePort) {
        if (connectivityManager == null) return;
        try {
            int uid = connectivityManager.getConnectionOwnerUid(protocol,
                    new InetSocketAddress(localIp, localPort),
                    new InetSocketAddress(remoteIp, remotePort));
            if (uid > 0 && uid != android.os.Process.INVALID_UID) {
                info.uid = uid;
                resolvePackageName(info, uid);
            }
        } catch (Exception ignored) {}
    }

    private void resolvePackageName(ConnectionInfo info, int uid) {
        if (uidToPackage.containsKey(uid)) {
            info.packageName = uidToPackage.get(uid);
            info.appName = uidToAppName.get(uid);
            return;
        }
        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                String pkg = packages[0];
                info.packageName = pkg;
                uidToPackage.put(uid, pkg);
                try {
                    ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                    info.appName = packageManager.getApplicationLabel(ai).toString();
                    uidToAppName.put(uid, info.appName);
                } catch (Exception e) {
                    info.appName = pkg;
                    uidToAppName.put(uid, pkg);
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopVpn() {
        running.set(false);
        for (UdpSession s : udpSessions.values()) {
            try { s.socket.close(); } catch (Exception ignored) {}
        }
        udpSessions.clear();
        liveUdpSessions = 0;
        for (TcpSession s : tcpSessions.values()) s.close();
        tcpSessions.clear();
        liveTcpSessions = 0;
        if (captureThread != null) {
            try { captureThread.join(2000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NetMonitor")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "NetMonitor VPN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
