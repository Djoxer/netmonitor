package dev.djoxer.netmonitor.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import dev.djoxer.netmonitor.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NetVpnService extends VpnService {

    private static final String TAG = "NetVpnService";
    public static final String ACTION_START = "dev.djoxer.netmonitor.START_VPN";
    public static final String ACTION_STOP  = "dev.djoxer.netmonitor.STOP_VPN";

    private static final String CHANNEL_ID = "netmonitor_vpn";
    private static final int NOTIFICATION_ID = 1;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread captureThread = null;
    private volatile boolean running = false;

    // Simple connection tracker (max 200 entries)
    private static final Map<String, ConnectionInfo> connections = new LinkedHashMap<>(200, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ConnectionInfo> eldest) {
            return size() > 200;
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("VPN starting..."));
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (running) return;

        try {
            Builder builder = new Builder();
            builder.setSession("NetMonitor");
            builder.setMtu(1500);
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");
            builder.addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                stopSelf();
                return;
            }

            running = true;
            updateNotification("Monitoring traffic...");

            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.start();

            Log.i(TAG, "VPN started");

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopVpn();
            stopSelf();
        }
    }

    private void captureLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(32767);

        try {
            while (running) {
                int length = in.read(packet.array());
                if (length > 0) {
                    parsePacket(packet.array(), length);
                    // Passthrough
                    out.write(packet.array(), 0, length);
                }
                packet.clear();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Capture error", e);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void parsePacket(byte[] data, int length) {
        if (length < 20) return; // too short for IP header

        // IP version
        int version = (data[0] >> 4) & 0x0F;
        if (version != 4) return; // only IPv4 for now

        int headerLength = (data[0] & 0x0F) * 4;
        if (length < headerLength) return;

        int protocol = data[9] & 0xFF;
        String protoName;
        if (protocol == 6) protoName = "TCP";
        else if (protocol == 17) protoName = "UDP";
        else return; // ignore others for now

        // Destination IP (bytes 16-19)
        String destIp = (data[16] & 0xFF) + "." +
                (data[17] & 0xFF) + "." +
                (data[18] & 0xFF) + "." +
                (data[19] & 0xFF);

        int destPort = 0;
        if (length >= headerLength + 4) {
            destPort = ((data[headerLength + 2] & 0xFF) << 8) |
                    (data[headerLength + 3] & 0xFF);
        }

        // Ignore local / invalid
        if (destIp.startsWith("10.0.0.") || destPort == 0) return;

        String key = protoName + "|" + destIp + "|" + destPort;

        synchronized (connections) {
            ConnectionInfo info = connections.get(key);
            if (info == null) {
                info = new ConnectionInfo(protoName, destIp, destPort);
                connections.put(key, info);
            } else {
                info.packetCount++;
            }
            info.bytes += length;
        }
    }

    private void stopVpn() {
        running = false;
        if (captureThread != null) {
            try { captureThread.join(1000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
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
