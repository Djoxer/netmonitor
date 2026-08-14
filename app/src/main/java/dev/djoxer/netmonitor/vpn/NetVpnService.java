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
import dev.djoxer.netmonitor.R;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Local VPN service that captures all device traffic.
 * This is the non-root foundation of NetMonitor.
 */
public class NetVpnService extends VpnService {

    private static final String TAG = "NetVpnService";
    public static final String ACTION_START = "dev.djoxer.netmonitor.START_VPN";
    public static final String ACTION_STOP  = "dev.djoxer.netmonitor.STOP_VPN";

    private static final String CHANNEL_ID = "netmonitor_vpn";
    private static final int NOTIFICATION_ID = 1;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread captureThread = null;
    private volatile boolean running = false;

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

            // Local address for the TUN interface
            builder.addAddress("10.0.0.2", 32);

            // Route all traffic through us
            builder.addRoute("0.0.0.0", 0);

            // Optional: DNS
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");

            // Important: allow the app itself to still have network
            builder.addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                stopSelf();
                return;
            }

            running = true;
            updateNotification("VPN running – monitoring traffic");

            // Start a simple capture loop (for now just keeps the tunnel alive)
            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.start();

            Log.i(TAG, "VPN started successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopVpn();
            stopSelf();
        }
    }

    private void captureLoop() {
        if (vpnInterface == null) return;

        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(32767);

        try {
            while (running) {
                // Read packet from TUN
                int length = in.read(packet.array());
                if (length > 0) {
                    // For now we just write it back (passthrough)
                    // Later we will parse and log connections here
                    out.write(packet.array(), 0, length);
                }
                packet.clear();
            }
        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "Capture loop error", e);
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void stopVpn() {
        running = false;

        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException ignored) {}
            captureThread = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
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
                    CHANNEL_ID,
                    "NetMonitor VPN",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when traffic monitoring is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    public static boolean isRunning() {
        // Simple static flag – later we can improve this
        return false; // will be improved
    }
}
