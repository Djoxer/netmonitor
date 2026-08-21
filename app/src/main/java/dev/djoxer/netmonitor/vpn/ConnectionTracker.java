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

import dev.djoxer.netmonitor.block.BlockManager;

public class ConnectionTracker {

    private static final String TAG = "ConnectionTracker";
    private static final String TUN_PREFIX = "10.0.0.";

    public final AtomicLong udpForwarded = new AtomicLong(0);
    public final AtomicLong tcpForwarded = new AtomicLong(0);
    public final AtomicLong tcpSeen = new AtomicLong(0);
    public final AtomicLong tcpSynSeen = new AtomicLong(0);
    public final AtomicLong tcpSessionsCreated = new AtomicLong(0);
    public final AtomicLong tcpConnectErrors = new AtomicLong(0);
    public final AtomicLong tcpClientPayloads = new AtomicLong(0);
    public final AtomicLong packetsSeen = new AtomicLong(0);
    public final AtomicLong bytesIpv4 = new AtomicLong(0);
    public final AtomicLong bytesIpv6 = new AtomicLong(0);

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
    private final HostnameResolver hostnameResolver = new HostnameResolver();

    public void init(ConnectivityManager cm, PackageManager pm) {
        this.connectivityManager = cm;
        this.packageManager = pm;
    }

    public HostnameResolver getHostnameResolver() {
        return hostnameResolver;
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
        hostnameResolver.clear();
        udpForwarded.set(0);
        tcpForwarded.set(0);
        tcpSeen.set(0);
        tcpSynSeen.set(0);
        tcpSessionsCreated.set(0);
        tcpConnectErrors.set(0);
        tcpClientPayloads.set(0);
        packetsSeen.set(0);
        bytesIpv4.set(0);
        bytesIpv6.set(0);
    }

    public void onPacket(byte[] data, int length) {
        packetsSeen.incrementAndGet();
        if (length < 20) return;

        hostnameResolver.inspectPacket(data, length);

        IpPacket ip = IpPacket.parse(data, length);
        if (ip == null) return;
        if (ip.protocol != 6 && ip.protocol != 17) return;

        String protoName = ip.protocol == 6 ? "TCP" : "UDP";
        int osProto = ip.protocol == 6
                ? android.system.OsConstants.IPPROTO_TCP
                : android.system.OsConstants.IPPROTO_UDP;

        if (length < ip.headerLength + 4) return;

        int srcPort = ((data[ip.headerLength] & 0xFF) << 8) | (data[ip.headerLength + 1] & 0xFF);
        int destPort = ((data[ip.headerLength + 2] & 0xFF) << 8) | (data[ip.headerLength + 3] & 0xFF);
        if (srcPort == 0 || destPort == 0) return;

        final boolean inbound = ip.isTunLocal();
        final String remoteIp;
        final int remotePort;
        final int localPort;
        final String localIp;

        if (inbound) {
            remoteIp = ip.srcIpStr;
            remotePort = srcPort;
            localPort = destPort;
            localIp = ip.dstIpStr;
        } else {
            remoteIp = ip.dstIpStr;
            remotePort = destPort;
            localPort = srcPort;
            localIp = ip.srcIpStr;
        }

        if (ip.version == 4 && remoteIp.startsWith("10.0.0.")) return;
        if (ip.version == 6 && remoteIp.startsWith("fd00:1:fd00:1:")) return;
        if (remoteIp.startsWith(TUN_PREFIX)) return;

        String key = protoName + "|" + localPort + "|" + remoteIp + "|" + remotePort;

        synchronized (connections) {
            ConnectionInfo info = connections.get(key);
            if (info == null) {
                info = new ConnectionInfo(protoName, remoteIp, remotePort, localPort);
                connections.put(key, info);
                resolveUid(info, osProto, localIp, localPort, remoteIp, remotePort);
            } else {
                info.packetCount++;
                if (info.uid <= 0) {
                    resolveUid(info, osProto, localIp, localPort, remoteIp, remotePort);
                }
            }

            // Presence only – byte counters come from successful forward
            if (inbound) {
                info.seenIn = true;
            } else {
                info.seenOut = true;
            }

            if (info.hostname == null) {
                String host = hostnameResolver.getHostname(remoteIp);
                if (host != null) {
                    info.hostname = host;
                }
            }
        }
    }

    /**
     * Used by forwarders to resolve owner UID for a 5-tuple-like key.
     */
    public int resolveUidForForward(int osProto, String localIp, int localPort,
                                    String remoteIp, int remotePort) {
        if (connectivityManager == null) return -1;
        int uid = lookupOwnerUid(osProto, localIp, localPort, remoteIp, remotePort);
        if (uid <= 0 || uid == android.os.Process.INVALID_UID) {
            uid = lookupOwnerUid(osProto, remoteIp, remotePort, localIp, localPort);
        }
        if (uid > 0 && uid != android.os.Process.INVALID_UID) {
            // Ensure package is registered for BlockManager
            ConnectionInfo tmp = new ConnectionInfo(
                    osProto == OsConstants.IPPROTO_TCP ? "TCP" : "UDP",
                    remoteIp, remotePort, localPort);
            tmp.uid = uid;
            resolvePackage(tmp, uid);
            return uid;
        }
        return -1;
    }

    public String getPackageForUid(int uid) {
        return uidToPackage.get(uid);
    }

    public String getAppNameForUid(int uid) {
        return uidToAppName.get(uid);
    }

    private void resolveUid(ConnectionInfo info, int protocol,
                            String localIp, int localPort,
                            String remoteIp, int remotePort) {
        if (connectivityManager == null) return;

        int uid = lookupOwnerUid(protocol, localIp, localPort, remoteIp, remotePort);
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
            BlockManager.getInstance().registerUid(uid, info.packageName);
            return;
        }
        if (packageManager == null) return;

        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) return;

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

            BlockManager.getInstance().registerUid(uid, chosen);
        } catch (Exception e) {
            Log.w(TAG, "resolvePackage failed for uid " + uid, e);
        }
    }

    /**
     * Account traffic that never appears on TUN read (userspace forwarder RX path).
     */
    public void addForwardedTraffic(String protocol, int localPort, String remoteIp, int remotePort,
                                    long bytes, boolean inbound) {
        if (remoteIp == null || localPort <= 0 || remotePort <= 0 || bytes <= 0) return;
        if (remoteIp.startsWith(TUN_PREFIX)) return;

        if (remoteIp.indexOf(':') >= 0) {
            bytesIpv6.addAndGet(bytes);
        } else {
            bytesIpv4.addAndGet(bytes);
        }

        String key = protocol + "|" + localPort + "|" + remoteIp + "|" + remotePort;

        synchronized (connections) {
            ConnectionInfo info = connections.get(key);
            if (info == null) {
                info = new ConnectionInfo(protocol, remoteIp, remotePort, localPort);
                connections.put(key, info);
                if (info.hostname == null) {
                    String host = hostnameResolver.getHostname(remoteIp);
                    if (host != null) info.hostname = host;
                }
            } else {
                info.packetCount++;
            }

            if (inbound) {
                info.seenIn = true;
                info.bytesIn += bytes;
            } else {
                info.seenOut = true;
                info.bytesOut += bytes;
            }
        }
    }
}
