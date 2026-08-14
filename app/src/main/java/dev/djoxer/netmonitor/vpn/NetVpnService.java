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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    // Debug counters
    public static final AtomicLong udpForwarded = new AtomicLong(0);
    public static final AtomicLong tcpSeen = new AtomicLong(0);
    public static final AtomicLong packetsSeen = new AtomicLong(0);

    private ParcelFileDescriptor vpnInterface = null;
    private Thread captureThread = null;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ConnectivityManager connectivityManager;
    private PackageManager packageManager;

    private final Map<Integer, String> uidToPackage = new HashMap<>();
    private final Map<Integer, String> uidToAppName = new HashMap<>();

    private final Map<String, UdpSession> udpSessions = new ConcurrentHashMap<>();

    private static final Map<String, ConnectionInfo> connections = new LinkedHashMap<>(300, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ConnectionInfo> eldest) {
            return size() > 300;
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
        tcpSeen.set(0);
        packetsSeen.set(0);
    }

    public static boolean isBlockMode() {
        return blockMode;
    }

    public static int getUdpSessionCount() {
        // approximate – not perfectly synchronized
        return 0; // will be set from instance if needed
    }

    private static volatile int liveUdpSessions = 0;

    public static int getLiveUdpSessions() {
        return liveUdpSessions;
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
                blockMode ? "Block mode" : "Forward mode (UDP)"));
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
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }

            running.set(true);
            updateNotification(blockMode ? "Block mode active" : "Forward mode (UDP only)");

            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();

            Log.i(TAG, "VPN started – blockMode=" + blockMode);

        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            stopVpn();
            stopSelf();
        }
    }

    private void captureLoop() {
        FileInputStream in = null;
        FileOutputStream out = null;

        try {
            in = new FileInputStream(vpnInterface.getFileDescriptor());
            out = new FileOutputStream(vpnInterface.getFileDescriptor());
            byte[] buffer = new byte[32767];

            while (running.get()) {
                int length = in.read(buffer);
                if (length <= 0) continue;

                packetsSeen.incrementAndGet();
                parsePacket(buffer, length);

                if (blockMode) {
                    continue; // drop
                }

                int version = (buffer[0] >> 4) & 0x0F;
                if (version == 4) {
                    int protocol = buffer[9] & 0xFF;
                    if (protocol == 17) { // UDP
                        handleUdpForward(buffer, length, out);
                    } else if (protocol == 6) { // TCP
                        tcpSeen.incrementAndGet();
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "captureLoop error", e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private void handleUdpForward(byte[] data, int length, FileOutputStream tunOut) {
        try {
            int headerLength = (data[0] & 0x0F) * 4;
            if (length < headerLength + 8) return;

            byte[] srcIpBytes = new byte[]{data[12], data[13], data[14], data[15]};
            int srcPort = ((data[headerLength] & 0xFF) << 8) | (data[headerLength + 1] & 0xFF);

            byte[] dstIpBytes = new byte[]{data[16], data[17], data[18], data[19]};
            int dstPort = ((data[headerLength + 2] & 0xFF) << 8) | (data[headerLength + 3] & 0xFF);

            int payloadOffset = headerLength + 8;
            int payloadLen = length - payloadOffset;
            if (payloadLen <= 0) return;

            String remoteIp = InetAddress.getByAddress(dstIpBytes).getHostAddress();
            String key = srcPort + "|" + remoteIp + "|" + dstPort;

            UdpSession session = udpSessions.get(key);
            if (session == null) {
                DatagramSocket socket = new DatagramSocket();
                protect(socket);
                socket.connect(InetAddress.getByAddress(dstIpBytes), dstPort);

                session = new UdpSession(socket, srcIpBytes, srcPort, dstIpBytes, dstPort, tunOut);
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
        final byte[] clientIp;
        final int clientPort;
        final byte[] remoteIp;
        final int remotePort;
        final FileOutputStream tunOut;

        UdpSession(DatagramSocket socket, byte[] clientIp, int clientPort,
                   byte[] remoteIp, int remotePort, FileOutputStream tunOut) {
            this.socket = socket;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.tunOut = tunOut;
        }

        @Override
        public void run() {
            byte[] buf = new byte[65535];
            try {
                while (running.get() && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    byte[] response = buildUdpPacket(
                            remoteIp, remotePort,
                            clientIp, clientPort,
                            packet.getData(), packet.getOffset(), packet.getLength());

                    synchronized (tunOut) {
                        tunOut.write(response);
                    }
                }
            } catch (Exception e) {
                // normal on close
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
                udpSessions.values().remove(this);
                liveUdpSessions = udpSessions.size();
            }
        }
    }

    private byte[] buildUdpPacket(byte[] srcIp, int srcPort,
                                  byte[] dstIp, int dstPort,
                                  byte[] payload, int offset, int len) {
        int totalLen = 20 + 8 + len;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) 0x45);
        buf.put((byte) 0);
        buf.putShort((short) totalLen);
        buf.putShort((short) 0);
        buf.putShort((short) 0x4000);
        buf.put((byte) 64);
        buf.put((byte) 17);
        buf.putShort((short) 0);
        buf.put(srcIp);
        buf.put(dstIp);

        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) (8 + len));
        buf.putShort((short) 0);

        buf.put(payload, offset, len);
        return buf.array();
    }

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
        } else {
            return;
        }

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
            InetSocketAddress local = new InetSocketAddress(localIp, localPort);
            InetSocketAddress remote = new InetSocketAddress(remoteIp, remotePort);
            int uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote);
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
                    String name = packageManager.getApplicationLabel(ai).toString();
                    info.appName = name;
                    uidToAppName.put(uid, name);
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

        if (captureThread != null) {
            try { captureThread.join(2000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }

        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception e) {
                Log.e(TAG, "Error closing interface", e);
            }
            vpnInterface = null;
        }
        Log.i(TAG, "VPN stopped");
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, openIntent,
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
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
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
