package dev.djoxer.netmonitor.ui;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.BlockSchedule;
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;

public class AppDetailDialog extends DialogFragment {

    private static final String ARG_KEY = "key";
    private static final String ARG_NAME = "name";
    private static final String ARG_PKG = "pkg";
    private static final String ARG_UID = "uid";

    private AppGroup group;
    private RuleRepository ruleRepository;
    private ScheduleAdapter scheduleAdapter;
    private Switch switchPermanent;

    public static AppDetailDialog newInstance(AppGroup group) {
        AppDetailDialog d = new AppDetailDialog();
        Bundle b = new Bundle();
        b.putString(ARG_KEY, group.key);
        b.putString(ARG_NAME, group.displayName);
        b.putString(ARG_PKG, group.packageName);
        b.putInt(ARG_UID, group.uid);
        d.setArguments(b);
        d.group = group;
        return d;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ruleRepository = new RuleRepository(requireContext());

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_app_detail, null, false);

        ImageView icon = view.findViewById(R.id.detailIcon);
        TextView name = view.findViewById(R.id.detailAppName);
        TextView pkg = view.findViewById(R.id.detailPackage);
        switchPermanent = view.findViewById(R.id.switchPermanentBlock);
        RecyclerView recyclerSchedules = view.findViewById(R.id.recyclerSchedules);
        Button btnAdd = view.findViewById(R.id.btnAddSchedule);
        TextView connections = view.findViewById(R.id.detailConnections);
        Button btnClose = view.findViewById(R.id.btnCloseDetail);

        Bundle args = getArguments();
        String displayName = args != null ? args.getString(ARG_NAME, "?") : "?";
        String packageName = args != null ? args.getString(ARG_PKG) : null;
        int uid = args != null ? args.getInt(ARG_UID, -1) : -1;

        // Block identity: real package or synthetic "uid:1234"
        final String blockKey;
        if (packageName != null && !packageName.isEmpty()) {
            blockKey = packageName;
        } else if (uid > 0) {
            blockKey = "uid:" + uid;
        } else if (args != null && args.getString(ARG_KEY) != null
                && !args.getString(ARG_KEY).isEmpty()) {
            blockKey = args.getString(ARG_KEY); // includes "unknown"
        } else {
            blockKey = "unknown";
        }

        final boolean canBlock = blockKey != null && !blockKey.isEmpty();

        name.setText(displayName);
        pkg.setText(packageName != null ? packageName : "no package");

        if (group != null && group.icon != null) {
            icon.setImageDrawable(group.icon);
        } else {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        // Connections text
        StringBuilder sb = new StringBuilder();
        int connCount = 0;
        if (group != null && group.connections != null) {
            connCount = group.connections.size();
            sb.append("Connections: ").append(connCount).append("\n\n");
            int n = 0;
            for (ConnectionInfo c : group.connections) {
                sb.append(c.protocol).append(" ")
                        .append(c.hostname != null ? c.hostname : c.destIp)
                        .append(":").append(c.destPort)
                        .append("  ↑").append(fmt(c.bytesOut))
                        .append(" ↓").append(fmt(c.bytesIn))
                        .append("\n");
                if (++n >= 100) {
                    sb.append("…\n");
                    break;
                }
            }
        }
        if (connCount == 0) {
            sb.append("Connections: 0\n\nNo connections.");
        }
        connections.setText(sb.toString());

        // Permanent block
        boolean perm = canBlock
                && BlockManager.getInstance().isPermanentlyBlocked(blockKey);
        switchPermanent.setChecked(perm);
        switchPermanent.setEnabled(canBlock);
        switchPermanent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!canBlock) return;
            ruleRepository.setPermanentBlockAsync(blockKey, uid, displayName, isChecked);
            if (uid > 0) {
                BlockManager.getInstance().registerUid(uid, blockKey);
            }
        });

        // Schedules
        scheduleAdapter = new ScheduleAdapter(schedule -> {
            if (!canBlock) return;
            ruleRepository.deleteScheduleAsync(schedule.id, blockKey, () ->
                    requireActivity().runOnUiThread(this::reloadSchedules));
        });
        recyclerSchedules.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerSchedules.setAdapter(scheduleAdapter);

        btnAdd.setEnabled(canBlock);
        btnAdd.setOnClickListener(v -> showAddScheduleDialog(blockKey));

        btnClose.setOnClickListener(v -> dismiss());

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();

        // Load schedules after view ready
        view.post(this::reloadSchedules);

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    private void reloadSchedules() {
        Bundle args = getArguments();
        String packageName = args != null ? args.getString(ARG_PKG) : null;
        int uid = args != null ? args.getInt(ARG_UID, -1) : -1;

        String blockKey;
        if (packageName != null && !packageName.isEmpty()) {
            blockKey = packageName;
        } else if (uid > 0) {
            blockKey = "uid:" + uid;
        } else {
            blockKey = args != null ? args.getString(ARG_KEY) : null;
        }

        if (blockKey == null || blockKey.isEmpty() || scheduleAdapter == null) {
            if (scheduleAdapter != null) scheduleAdapter.submit(null);
            return;
        }
        ruleRepository.getSchedulesAsync(blockKey, list -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> scheduleAdapter.submit(list));
        });
    }

    private void showAddScheduleDialog(String packageName) {
        if (packageName == null) return;

        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_schedule, null, false);

        CheckBox dayAll = form.findViewById(R.id.dayAll);
        CheckBox[] days = {
                form.findViewById(R.id.dayMon),
                form.findViewById(R.id.dayTue),
                form.findViewById(R.id.dayWed),
                form.findViewById(R.id.dayThu),
                form.findViewById(R.id.dayFri),
                form.findViewById(R.id.daySat),
                form.findViewById(R.id.daySun)
        };
        EditText editStart = form.findViewById(R.id.editStart);
        EditText editEnd = form.findViewById(R.id.editEnd);

        // All → check/uncheck every day
        dayAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (CheckBox day : days) {
                day.setOnCheckedChangeListener(null);
                day.setChecked(isChecked);
            }
            for (CheckBox day : days) {
                day.setOnCheckedChangeListener((b, checked) -> syncAllCheckbox(dayAll, days));
            }
        });

        // Single day change → update All state
        for (CheckBox day : days) {
            day.setOnCheckedChangeListener((b, checked) -> syncAllCheckbox(dayAll, days));
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Add block schedule")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < 7; i++) {
                        if (days[i].isChecked()) mask |= (1 << i);
                    }
                    if (mask == 0) {
                        Toast.makeText(requireContext(), "Select at least one day", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Integer start = parseTime(editStart.getText().toString().trim());
                    Integer end = parseTime(editEnd.getText().toString().trim());
                    if (start == null || end == null) {
                        Toast.makeText(requireContext(), "Time format HH:mm", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ruleRepository.addScheduleAsync(packageName, mask, start, end, () ->
                            requireActivity().runOnUiThread(this::reloadSchedules));
                })
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> spaceDialogButtons(dialog));
        dialog.show();
    }

    private void spaceDialogButtons(AlertDialog dialog) {
        final int gapPx = (int) (12 * requireContext().getResources().getDisplayMetrics().density);

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        if (positive != null && positive.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) positive.getLayoutParams();
            lp.setMarginStart(gapPx);
            positive.setLayoutParams(lp);
        }
        if (negative != null && negative.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) negative.getLayoutParams();
            lp.setMarginEnd(gapPx);
            negative.setLayoutParams(lp);
        }
    }

    private void syncAllCheckbox(CheckBox dayAll, CheckBox[] days) {
        boolean allChecked = true;
        for (CheckBox day : days) {
            if (!day.isChecked()) {
                allChecked = false;
                break;
            }
        }
        dayAll.setOnCheckedChangeListener(null);
        dayAll.setChecked(allChecked);
        dayAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (CheckBox d : days) {
                d.setOnCheckedChangeListener(null);
                d.setChecked(isChecked);
            }
            for (CheckBox d : days) {
                d.setOnCheckedChangeListener((b, checked) -> syncAllCheckbox(dayAll, days));
            }
        });
    }

    private Integer parseTime(String text) {
        try {
            String[] p = text.split(":");
            if (p.length != 2) return null;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return h * 60 + m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.getDefault(), "%.1fMB", bytes / (1024f * 1024f));
    }
}
