package dev.djoxer.netmonitor.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "block_schedules")
public class ScheduleEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public String packageName;
    public int daysMask;
    public int startMinute;
    public int endMinute;

    public ScheduleEntity(String packageName, int daysMask, int startMinute, int endMinute) {
        this.packageName = packageName;
        this.daysMask = daysMask;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }
}
