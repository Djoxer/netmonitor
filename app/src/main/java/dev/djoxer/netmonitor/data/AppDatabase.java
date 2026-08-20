package dev.djoxer.netmonitor.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import dev.djoxer.netmonitor.data.dao.BlockRuleDao;
import dev.djoxer.netmonitor.data.dao.LogEventDao;
import dev.djoxer.netmonitor.data.dao.ScheduleDao;
import dev.djoxer.netmonitor.data.dao.TrafficSampleDao;
import dev.djoxer.netmonitor.data.entity.BlockRuleEntity;
import dev.djoxer.netmonitor.data.entity.LogEventEntity;
import dev.djoxer.netmonitor.data.entity.ScheduleEntity;
import dev.djoxer.netmonitor.data.entity.TrafficSampleEntity;

@Database(
        entities = {
                BlockRuleEntity.class,
                ScheduleEntity.class,
                LogEventEntity.class,
                TrafficSampleEntity.class
        },
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract BlockRuleDao blockRuleDao();
    public abstract ScheduleDao scheduleDao();
    public abstract LogEventDao logEventDao();
    public abstract TrafficSampleDao trafficSampleDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "netmonitor.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
