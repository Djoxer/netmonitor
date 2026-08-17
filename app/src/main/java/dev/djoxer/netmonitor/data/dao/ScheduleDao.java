package dev.djoxer.netmonitor.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.djoxer.netmonitor.data.entity.ScheduleEntity;

@Dao
public interface ScheduleDao {

    @Query("SELECT * FROM block_schedules")
    List<ScheduleEntity> getAll();

    @Query("SELECT * FROM block_schedules WHERE packageName = :pkg")
    List<ScheduleEntity> getForPackage(String pkg);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ScheduleEntity entity);

    @Query("DELETE FROM block_schedules WHERE packageName = :pkg")
    void deleteForPackage(String pkg);

    @Query("DELETE FROM block_schedules WHERE id = :id")
    void deleteById(int id);
}
