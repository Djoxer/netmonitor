package dev.djoxer.netmonitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.entity.TrafficSampleEntity;

public class TrafficChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillOutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillInPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<float[]> outPts = new ArrayList<>();
    private final List<float[]> inPts = new ArrayList<>();
    private String label = "24h traffic - collecting...";

    public TrafficChartView(Context context) {
        super(context);
        init();
    }

    public TrafficChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1f);

        int out = ContextCompat.getColor(getContext(), R.color.bar_ipv6);
        int in = ContextCompat.getColor(getContext(), R.color.bar_ipv4);

        outPaint.setColor(out);
        outPaint.setStyle(Paint.Style.STROKE);
        outPaint.setStrokeWidth(dp(2));
        outPaint.setStrokeJoin(Paint.Join.ROUND);
        outPaint.setStrokeCap(Paint.Cap.ROUND);

        inPaint.setColor(in);
        inPaint.setStyle(Paint.Style.STROKE);
        inPaint.setStrokeWidth(dp(2));
        inPaint.setStrokeJoin(Paint.Join.ROUND);
        inPaint.setStrokeCap(Paint.Cap.ROUND);

        fillOutPaint.setColor((out & 0x00FFFFFF) | 0x33000000);
        fillOutPaint.setStyle(Paint.Style.FILL);
        fillInPaint.setColor((in & 0x00FFFFFF) | 0x22000000);
        fillInPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(ContextCompat.getColor(getContext(), R.color.md_theme_on_surface_variant));
        textPaint.setTextSize(sp(11));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /**
     * Cumulative snapshots, oldest first. Deltas become B/s between points.
     * Ignores negative deltas (e.g. after Clear / session reset).
     */
    public void setSamples(List<TrafficSampleEntity> samples) {
        outPts.clear();
        inPts.clear();

        if (samples == null || samples.size() < 2) {
            label = "24h traffic - collecting...";
            invalidate();
            return;
        }

        int n = samples.size();
        List<Float> dOut = new ArrayList<>();
        List<Float> dIn = new ArrayList<>();
        float max = 1f;

        for (int i = 1; i < n; i++) {
            TrafficSampleEntity a = samples.get(i - 1);
            TrafficSampleEntity b = samples.get(i);
            long dt = b.timestamp - a.timestamp;
            if (dt < 500L) continue;

            long rawOut = b.bytesOut - a.bytesOut;
            long rawIn = b.bytesIn - a.bytesIn;
            // Session reset / clear -> skip jump
            if (rawOut < 0) rawOut = 0;
            if (rawIn < 0) rawIn = 0;

            float o = rawOut * 1000f / dt;
            float inn = rawIn * 1000f / dt;
            dOut.add(o);
            dIn.add(inn);
            max = Math.max(max, Math.max(o, inn));
        }

        if (dOut.size() < 1) {
            label = "24h traffic - collecting...";
            invalidate();
            return;
        }

        // Soft headroom so peaks are not glued to the top
        max *= 1.08f;

        int count = dOut.size();
        for (int i = 0; i < count; i++) {
            float x = count == 1 ? 0.5f : i / (float) (count - 1);
            outPts.add(new float[]{x, clamp01(dOut.get(i) / max)});
            inPts.add(new float[]{x, clamp01(dIn.get(i) / max)});
        }

        long spanMin = Math.max(1L,
                (samples.get(n - 1).timestamp - samples.get(0).timestamp) / 60000L);
        label = String.format(Locale.US,
                "24h  %d pts  ~%dm  peak %s/s",
                count, spanMin, formatRate((long) (max / 1.08f)));
        invalidate();
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static String formatRate(long bps) {
        if (bps < 1024) return bps + "B";
        if (bps < 1024 * 1024) return (bps / 1024) + "KB";
        return String.format(Locale.US, "%.1fMB", bps / (1024.0 * 1024.0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(6);
        float padR = dp(6);
        float padT = dp(18);
        float padB = dp(6);
        float cw = w - padL - padR;
        float ch = h - padT - padB;

        for (int i = 0; i <= 4; i++) {
            float y = padT + ch * i / 4f;
            canvas.drawLine(padL, y, padL + cw, y, gridPaint);
        }

        canvas.drawText(label, padL, padT - dp(5), textPaint);

        drawFilled(canvas, outPts, fillOutPaint, padL, padT, cw, ch);
        drawFilled(canvas, inPts, fillInPaint, padL, padT, cw, ch);
        drawSeries(canvas, outPts, outPaint, padL, padT, cw, ch);
        drawSeries(canvas, inPts, inPaint, padL, padT, cw, ch);
    }

    private void drawSeries(Canvas c, List<float[]> pts, Paint paint,
                            float padL, float padT, float cw, float ch) {
        if (pts.size() < 2) return;
        Path path = new Path();
        for (int i = 0; i < pts.size(); i++) {
            float x = padL + pts.get(i)[0] * cw;
            float y = padT + (1f - pts.get(i)[1]) * ch;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, paint);
    }

    private void drawFilled(Canvas c, List<float[]> pts, Paint paint,
                            float padL, float padT, float cw, float ch) {
        if (pts.size() < 2) return;
        Path path = new Path();
        float base = padT + ch;
        float x0 = padL + pts.get(0)[0] * cw;
        path.moveTo(x0, base);
        for (int i = 0; i < pts.size(); i++) {
            float x = padL + pts.get(i)[0] * cw;
            float y = padT + (1f - pts.get(i)[1]) * ch;
            path.lineTo(x, y);
        }
        float xN = padL + pts.get(pts.size() - 1)[0] * cw;
        path.lineTo(xN, base);
        path.close();
        c.drawPath(path, paint);
    }
}
