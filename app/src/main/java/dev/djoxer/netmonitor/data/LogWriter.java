package dev.djoxer.netmonitor.data;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.djoxer.netmonitor.data.entity.LogEventEntity;

/**
 * Non-blocking log API for the capture path.
 * Events are queued and flushed to Room on a background thread.
 */
public class LogWriter {

    private static final String TAG = "LogWriter";
    private static final int MAX_QUEUE = 5000;

    private static LogWriter INSTANCE;

    private final BlockingQueue<LogEventEntity> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private AppDatabase db;

    public static synchronized LogWriter getInstance() {
        if (INSTANCE == null) INSTANCE = new LogWriter();
        return INSTANCE;
    }

    public void start(Context context) {
        db = AppDatabase.getInstance(context);
        if (running.getAndSet(true)) return;
        worker = new Thread(this::loop, "NetMonitor-LogWriter");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }

    /** Safe to call from capture thread – never blocks long */
    public void log(String packageName, String appName, String eventType,
                    String direction, String detail) {
        LogEventEntity e = new LogEventEntity(
                System.currentTimeMillis(),
                packageName,
                appName,
                eventType,
                direction,
                detail);
        if (!queue.offer(e)) {
            queue.poll(); // drop oldest under pressure
            queue.offer(e);
        }
    }

    private void loop() {
        List<LogEventEntity> batch = new ArrayList<>(64);
        while (running.get()) {
            try {
                LogEventEntity first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, 63);
                if (db != null && !batch.isEmpty()) {
                    db.logEventDao().insertAll(batch);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "log flush failed", e);
            }
        }
    }
}
