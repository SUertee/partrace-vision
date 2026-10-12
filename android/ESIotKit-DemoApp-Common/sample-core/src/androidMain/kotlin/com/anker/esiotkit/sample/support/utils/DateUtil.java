package com.anker.esiotkit.sample.support.utils;

import android.text.TextUtils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 日期工具类。
 */
public class DateUtil {


    public static String formatYMDHMS(long time) {
        if (time <= 0)
            return "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String altertime = sdf.format(time);
        return altertime;
    }
    /**
     * @param time 毫秒
     * @return
     */
    public static String formatYMDSeparByDot(long time) {
        if (time <= 0)
            return "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd");
        String altertime = sdf.format(time);
        return altertime;
    }

    /**
     * @param time 毫秒
     * @return
     */
    public static String formatTimeHMS(long time) {
        if (time <= 0)
            return "00:00:00";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String altertime = sdf.format(time);
        return altertime;
    }

    /**
     * @param time 毫秒
     * @return
     */
    public static String formatTimeMS(long time) {
        if (time <= 0)
            return "00:00";
        SimpleDateFormat sdf = new SimpleDateFormat("mm:ss");
        String altertime = sdf.format(time);
        return altertime;
    }

    public static String formatTimeHM(long time) {
        if (time <= 0)
            return "00:00";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        String altertime = sdf.format(time);
        return altertime;
    }

    /**
     * 判断两个时间戳是否为同一天
     *
     * @param date1 日期
     * @param date2 日期
     */
    public static boolean isSameDate(long date1, long date2) {
        return isSameDate(new Date(date1), new Date(date2));
    }

    /**
     * 判断两个日期是否为同一天
     *
     * @param date1 日期
     * @param date2 日期
     */
    public static boolean isSameDate(Date date1, Date date2) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);

        boolean isSameYear = cal1.get(Calendar.YEAR) == cal2
                .get(Calendar.YEAR);
        boolean isSameMonth = isSameYear
                && cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH);
        boolean isSameDate = isSameMonth
                && cal1.get(Calendar.DAY_OF_MONTH) == cal2
                .get(Calendar.DAY_OF_MONTH);
        return isSameDate;
    }

    /**
     * 时间戳获取年份
     */
    public static int getYear(long date) {
        return getYear(new Date(date));
    }
    public static int getMonth(long date) {
        return getMonth(new Date(date));
    }
    public static int getDay(long date) {
        return getDay(new Date(date));
    }

    /**
     * 日期获取年份
     */
    public static int getYear(Date date) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date);
        return cal1.get(Calendar.YEAR);
    }
    public static int getMonth(Date date) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date);
        return cal1.get(Calendar.MONTH);
    }
    public static int getDay(Date date) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date);
        return cal1.get(Calendar.DAY_OF_MONTH);
    }
    public static int getHour(Date date) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date);
        return cal1.get(Calendar.HOUR_OF_DAY);
    }
    /**
     *
     * */
    public static String formatMEDIUM(long date) {
        return formatMEDIUM(new Date(date));
    }

    /**
     *
     * */
    public static String formatMEDIUM(Date date) {
        Locale locale = new Locale("en", "US");
        DateFormat.getDateInstance(DateFormat.LONG, locale);
        DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale);
        return dateFormat.format(date);
    }


    public static String formatTimeHMSecond(int seconds) {
        if (seconds <= 0)
            return "00:00";

        int hour = seconds / 3600;
        int minute = (seconds % 3600) / 60;
        return String.format("%02d:%02d", hour, minute);
    }

    /**
     * 时间戳转换成日期格式字符串
     * @param time 精确到秒的字符串
     * @param format
     * @return
     */
    public static String timeStamp2Date(Long time,String format) {
        if(format == null || format.isEmpty()){
            format = "yyyy_MM_dd_HH_mm_ss";
        }
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return sdf.format(new Date(time));
    }

    /**
     * @param dateStr example "2022-03-22 00:00:00"
     * @param format example "yyyy-MM-dd hh:mm:ss"
     * @return
     */
    public static Date parseDate(String dateStr, String format) {
        try {
            if (!TextUtils.isEmpty(dateStr)) {
                SimpleDateFormat simpleDate = new SimpleDateFormat(format, Locale.getDefault());
                return simpleDate.parse(dateStr);
            } else {
                return new Date();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new Date();
        }
    }

    public static long formatData(String dateStr, String format) {
        Date date = parseDate(dateStr, format);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.getTimeInMillis();
    }

}
