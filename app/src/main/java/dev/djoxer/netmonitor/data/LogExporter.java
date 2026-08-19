package dev.djoxer.netmonitor.data;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.data.entity.LogEventEntity;

public class LogExporter {

    private static final String TAG = "LogExporter";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess(Intent shareIntent);
        void onError(String message);
    }

    public enum Format {
        CSV, JSON
    }

    private final Context appContext;

    public LogExporter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void exportAsync(Format format, Callback callback) {
        IO.execute(() -> {
            try {
                List<LogEventEntity> events = AppDatabase.getInstance(appContext)
                        .logEventDao()
                        .getRecent(5000);

                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                String fileName = format == Format.CSV
                        ? "netmonitor_log_" + stamp + ".csv"
                        : "netmonitor_log_" + stamp + ".json";

                File dir = new File(appContext.getCacheDir(), "exports");
                if (!dir.exists() && !dir.mkdirs()) {
                    postError(callback, "Could not create export directory");
                    return;
                }

                File out = new File(dir, fileName);
                String content = format == Format.CSV ? toCsv(events) : toJson(events);

                try (OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(out), StandardCharsets.UTF_8)) {
                    w.write(content);
                }

                Uri uri = FileProvider.getUriForFile(
                        appContext,
                        appContext.getPackageName() + ".fileprovider",
                        out);

                String mime = format == Format.CSV ? "text/csv" : "application/json";

                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType(mime);
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.putExtra(Intent.EXTRA_SUBJECT, "NetMonitor log export");
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(share, "Export log");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (callback != null) {
                    callback.onSuccess(chooser);
                }
            } catch (Exception e) {
                Log.e(TAG, "export failed", e);
                postError(callback, e.getMessage() != null ? e.getMessage() : "Export failed");
            }
        });
    }

    private void postError(Callback callback, String msg) {
        if (callback != null) callback.onError(msg);
    }

    private static String toCsv(List<LogEventEntity> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,timestamp,iso_time,packageName,appName,eventType,direction,detail\n");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        for (LogEventEntity e : events) {
            sb.append(e.id).append(',')
                    .append(e.timestamp).append(',')
                    .append(csv(iso.format(new Date(e.timestamp)))).append(',')
                    .append(csv(e.packageName)).append(',')
                    .append(csv(e.appName)).append(',')
                    .append(csv(e.eventType)).append(',')
                    .append(csv(e.direction)).append(',')
                    .append(csv(e.detail)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        boolean needQuotes = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        String escaped = v.replace("\"", "\"\"");
        return needQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private static String toJson(List<LogEventEntity> events) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < events.size(); i++) {
            LogEventEntity e = events.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  {")
                    .append("\"id\":").append(e.id).append(',')
                    .append("\"timestamp\":").append(e.timestamp).append(',')
                    .append("\"iso_time\":").append(jsonStr(iso.format(new Date(e.timestamp)))).append(',')
                    .append("\"packageName\":").append(jsonStr(e.packageName)).append(',')
                    .append("\"appName\":").append(jsonStr(e.appName)).append(',')
                    .append("\"eventType\":").append(jsonStr(e.eventType)).append(',')
                    .append("\"direction\":").append(jsonStr(e.direction)).append(',')
                    .append("\"detail\":").append(jsonStr(e.detail))
                    .append('}');
        }
        sb.append("\n]\n");
        return sb.toString();
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
