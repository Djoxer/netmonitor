package dev.djoxer.netmonitor.block;

/**
 * Time window in which the app is BLOCKED.
 * Outside this window the app is allowed (unless permanently blocked).
 * daysMask: bit0=Monday ... bit6=Sunday
 */
public class BlockSchedule {

    public final int id;
    public final String packageName;
    public final int daysMask;
    public final int startMinute; // 0..1439
    public final int endMinute;   // 0..1439 (if end < start → overnight)

    public BlockSchedule(int id, String packageName, int daysMask, int startMinute, int endMinute) {
        this.id = id;
        this.packageName = packageName;
        this.daysMask = daysMask;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    public boolean isActiveNow(int dayIndexMon0, int minuteOfDay) {
        if (((daysMask >> dayIndexMon0) & 1) == 0) return false;

        if (startMinute <= endMinute) {
            return minuteOfDay >= startMinute && minuteOfDay < endMinute;
        } else {
            // e.g. 22:00 – 06:00
            return minuteOfDay >= startMinute || minuteOfDay < endMinute;
        }
    }
}
