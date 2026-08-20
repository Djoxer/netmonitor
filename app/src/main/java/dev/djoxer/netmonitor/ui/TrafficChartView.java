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

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.entity.TrafficSampleEntity;

public class TrafficChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<float[]> outPts = new ArrayList<>();
    private final List<float[]> inPts = new ArrayList<>();
    private String label = "24h traffic";

    public TrafficChartView(Context context) {
        super(context);
        init();
    }

    public TrafficChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        outPaint.setColor(ContextCompat.getColor(getContext(), R.color.bar_ipv6));
        outPaint.setStyle(Paint.Style.STROKE);
        outPaint.setStrokeWidth(2.5f);
        inPaint.setColor(ContextCompat.getColor(getContext(), R.color.bar_ipv4));
        inPaint.setStyle(Paint.Style.STROKE);
        inPaint.setStrokeWidth(2.5f);
        textPaint.setColor(ContextCompat.getColor(getContext(), R.color.md_theme_on_surface_variant));
        textPaint.setTextSize(sp(11));
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /**
     * samples ordered by time ascending; values are cumulative snapshots.
     */
    public void setSamples(List<TrafficSampleEntity> samples) {
        outPts.clear();
        inPts.clear();
        if (samples == null || samples.size() < 2) {
            label = "24h traffic - collecting…";
            invalidate();
            return;
        }

        int n = samples.size();
        float[] dOut = new float[n - 1];
        float[] dIn = new float[n - 1];
        float max = 1f;

        for (int i = 1; i < n; i++) {
            long dt = Math.max(1L, samples.get(i).timestamp - samples.get(i - 1).timestamp);
            // bytes per second between samples
            dOut[i - 1] = Math.max(0f,
                    (samples.get(i).bytesOut - samples.get(i - 1).bytesOut) * 1000f / dt);
            dIn[i - 1] = Math.max(0f,
                    (samples.get(i).bytesIn - samples.get(i - 1).bytesIn) * 1000f / dt);
            max = Math.max(max, Math.max(dOut[i - 1], dIn[i - 1]));
        }

        for (int i = 0; i < dOut.length; i++) {
            float x = i / (float) Math.max(1, dOut.length - 1);
            outPts.add(new float[]{x, dOut[i] / max});
            inPts.add(new float[]{x, dIn[i] / max});
        }

        label = String.format("24h  peak ~ %s/s", formatRate((long) max));
        invalidate();
    }

    private static String formatRate(long bps) {
        if (bps < 1024) return bps + "B";
        if (bps < 1024 * 1024) return (bps / 1024) + "KB";
        return String.format("%.1fMB", bps / (1024.0 * 1024.0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(4);
        float padR = dp(4);
        float padT = dp(16);
        float padB = dp(4);
        float cw = w - padL - padR;
        float ch = h - padT - padB;

        // grid
        for (int i = 0; i <= 4; i++) {
            float y = padT + ch * i / 4f;
            canvas.drawLine(padL, y, padL + cw, y, gridPaint);
        }

        canvas.drawText(label, padL, padT - dp(4), textPaint);

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

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
