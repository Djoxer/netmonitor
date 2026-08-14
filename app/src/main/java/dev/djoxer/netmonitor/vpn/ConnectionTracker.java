package dev.djoxer.netmonitor.vpn;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.system.OsConstants;
import android.util.Log;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class ConnectionTracker {

    private static final String TAG = "ConnectionTracker";

    public final AtomicLong udpForwarded = new AtomicLong(0);
    public final AtomicLong tcpForwarded = new AtomicLong(0);
    public final AtomicLong tcpSeen = new AtomicLong(0);
    public final AtomicLong tcpSynSeen = new AtomicLong(0);
    public final AtomicLong tcpSessionsCreated = new AtomicLong(0);
    public final AtomicLong tcpConnectErrors = new AtomicLong(0);
    public final AtomicLong tcpClientPayloads = new AtomicLong(0);
    public final AtomicLong packetsSeen = new AtomicLong(0);

    private final Map<Integer, String> uidToPackage = new HashMap<>();
    private final Map<Integer, String> uidToAppName = new HashMap<>();

    private final Map<String, ConnectionInfo> connections = new LinkedHashMap<>(500, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ConnectionInfo> eldest) {
            return size() > 500;
        }
    };

    private ConnectivityManager connectivityManager;
    private PackageManager packageManager;

    public void init(ConnectivityManager cm, PackageManager pm) {
        this.connectivityManager = cm;
        this.packageManager = pm;
    }

    public List<ConnectionInfo> getConnections() {
        synchronized (connections) {
            return new ArrayList<>(connections.values());
        }
    }

    public void clear() {
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

    public void onPacket(byte[] data, int length) {
        packetsSeen.incrementAndGet();
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

        if (destIp.startsWith("10.0.0.") || destPort == 0 || srcPort == 0) return;

        String key = protoName + "|" + srcPort + "|" + destIp + "|" + destPort;

        synchronized (connections) {
            ConnectionInfo info = connections.get(key);
            if (info == null) {
                info = new ConnectionInfo(protoName, destIp, destPort, srcPort);
                connections.put(key, info);
                resolveUid(info, osProto, srcIp, srcPort, destIp, destPort);
            } else {
                info.packetCount++;
                // retry resolution once if still unknown
                if (info.uid <= 0) {
                    resolveUid(info, osProto, srcIp, srcPort, destIp, destPort);
                }
            }
            info.bytes += length;
        }
    }

    private void resolveUid(ConnectionInfo info, int protocol,
                            String localIp, int localPort,
                            String remoteIp, int remotePort) {
        if (connectivityManager == null) return;

        int uid = lookupOwnerUid(protocol, localIp, localPort, remoteIp, remotePort);

        // Sometimes the API wants the endpoints swapped depending on direction
        if (uid <= 0 || uid == android.os.Process.INVALID_UID) {
            uid = lookupOwnerUid(protocol, remoteIp, remotePort, localIp, localPort);
        }

        if (uid > 0 && uid != android.os.Process.INVALID_UID) {
            info.uid = uid;
            resolvePackage(info, uid);
        }
    }

    private int lookupOwnerUid(int protocol,
                               String localIp, int localPort,
                               String remoteIp, int remotePort) {
        try {
            return connectivityManager.getConnectionOwnerUid(
                    protocol,
                    new InetSocketAddress(localIp, localPort),
                    new InetSocketAddress(remoteIp, remotePort));
        } catch (Exception e) {
            return -1;
        }
    }

    private void resolvePackage(ConnectionInfo info, int uid) {
        if (uidToPackage.containsKey(uid)) {
            info.packageName = uidToPackage.get(uid);
            info.appName = uidToAppName.get(uid);
            return;
        }
        if (packageManager == null) return;

        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) {
                // system / isolated uid without package
                info.appName = null;
                return;
            }

            // Prefer a non-system-looking package if multiple share the UID
            String chosen = packages[0];
            for (String pkg : packages) {
                if (!pkg.startsWith("android.") && !pkg.startsWith("com.android.")) {
                    chosen = pkg;
                    break;
                }
            }

            info.packageName = chosen;
            uidToPackage.put(uid, chosen);

            try {
                ApplicationInfo ai = packageManager.getApplicationInfo(chosen, 0);
                String label = packageManager.getApplicationLabel(ai).toString();
                info.appName = label;
                uidToAppName.put(uid, label);
            } catch (Exception e) {
                info.appName = chosen;
                uidToAppName.put(uid, chosen);
            }
        } catch (Exception e) {
            Log.w(TAG, "resolvePackage failed for uid " + uid, e);
        }
    }
}
