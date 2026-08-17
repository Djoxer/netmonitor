package dev.djoxer.netmonitor.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.BlockSchedule;
import dev.djoxer.netmonitor.data.entity.BlockRuleEntity;
import dev.djoxer.netmonitor.data.entity.ScheduleEntity;

public class RuleRepository {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AppDatabase db;

    public interface Callback {
        void onDone();
    }

    public interface SchedulesCallback {
        void onResult(List<BlockSchedule> schedules);
    }

    public RuleRepository(Context context) {
        db = AppDatabase.getInstance(context);
    }

    public void loadIntoMemoryAsync(Runnable onDone) {
        IO.execute(() -> {
            Set<String> permanent = new HashSet<>();
            Map<String, List<BlockSchedule>> scheduleMap = new HashMap<>();

            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                if (r.permanentlyBlocked) permanent.add(r.packageName);
                if (r.uid > 0) {
                    BlockManager.getInstance().registerUid(r.uid, r.packageName);
                }
            }

            for (ScheduleEntity s : db.scheduleDao().getAll()) {
                List<BlockSchedule> list = scheduleMap.get(s.packageName);
                if (list == null) {
                    list = new ArrayList<>();
                    scheduleMap.put(s.packageName, list);
                }
                list.add(new BlockSchedule(s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }

            BlockManager.getInstance().loadFromDisk(permanent, scheduleMap);
            if (onDone != null) onDone.run();
        });
    }

    public void setPermanentBlockAsync(String packageName, int uid, String appName, boolean blocked) {
        IO.execute(() -> {
            db.blockRuleDao().upsert(new BlockRuleEntity(packageName, uid, appName, blocked));
            BlockManager.getInstance().setPermanentBlock(packageName, blocked);
            if (uid > 0) BlockManager.getInstance().registerUid(uid, packageName);
        });
    }

    public void getSchedulesAsync(String packageName, SchedulesCallback callback) {
        IO.execute(() -> {
            List<ScheduleEntity> entities = db.scheduleDao().getForPackage(packageName);
            List<BlockSchedule> result = new ArrayList<>();
            for (ScheduleEntity s : entities) {
                result.add(new BlockSchedule(s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }
            if (callback != null) {
                callback.onResult(result);
            }
        });
    }

    public void addScheduleAsync(String packageName, int daysMask, int startMinute, int endMinute,
                                 Callback callback) {
        IO.execute(() -> {
            ScheduleEntity entity = new ScheduleEntity(packageName, daysMask, startMinute, endMinute);
            long id = db.scheduleDao().insert(entity);
            entity.id = (int) id;
            reloadPackageSchedules(packageName);
            if (callback != null) callback.onDone();
        });
    }

    public void deleteScheduleAsync(int scheduleId, String packageName, Callback callback) {
        IO.execute(() -> {
            db.scheduleDao().deleteById(scheduleId);
            reloadPackageSchedules(packageName);
            if (callback != null) callback.onDone();
        });
    }

    private void reloadPackageSchedules(String packageName) {
        List<ScheduleEntity> entities = db.scheduleDao().getForPackage(packageName);
        List<BlockSchedule> list = new ArrayList<>();
        for (ScheduleEntity s : entities) {
            list.add(new BlockSchedule(s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
        }
        BlockManager.getInstance().setSchedules(packageName, list);
    }
}
