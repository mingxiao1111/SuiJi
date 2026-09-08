package com.zhao.suiji.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 列表与保存状态的相对时间显示（计划 4.4）。 */
object TimeUtils {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MDHM = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val YMDHM = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

    fun formatRelative(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(timeMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val diffMin = (nowMs - timeMs) / 60_000
        return when {
            diffMin < 1 -> "刚刚"
            diffMin < 60 -> "${diffMin}分钟前"
            time.toLocalDate() == now.toLocalDate() -> "今天 ${time.format(HM)}"
            time.toLocalDate() == now.toLocalDate().minusDays(1) -> "昨天 ${time.format(HM)}"
            time.year == now.year -> time.format(MDHM)
            else -> time.format(YMDHM)
        }
    }

    fun formatTime(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(HM)

    /** 列表分组桶（M6 B1：今天/昨天/本周/更早）。 */
    enum class TimeBucket(val label: String) {
        TODAY("今天"),
        YESTERDAY("昨天"),
        THIS_WEEK("本周"),
        EARLIER("更早"),
    }

    fun bucketOf(timeMs: Long, nowMs: Long = System.currentTimeMillis()): TimeBucket {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when {
            time == now -> TimeBucket.TODAY
            time == now.minusDays(1) -> TimeBucket.YESTERDAY
            time >= now.minusDays(now.dayOfWeek.value - 1L) -> TimeBucket.THIS_WEEK // 本周一起
            else -> TimeBucket.EARLIER
        }
    }

    /** 编辑页元信息行的时间：M月d日 HH:mm。 */
    fun formatMeta(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

    /** 备份文件里的绝对时间：yyyy-MM-dd HH:mm（与时区无关的毫秒值另行存储）。 */
    fun formatDateTime(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
