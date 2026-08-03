package com.autonomousone.messages.utils

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Legacy relative date formatter preserved for compatibility.
 */
fun formatSmsDate(time: Long): String {
    if (time <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

/**
 * Formats timestamps for conversation list items (HomeScreen / SmsItem).
 * Examples: "10:45 AM", "Yesterday", "Tue", "Oct 24"
 */
fun formatConversationDate(time: Long): String {
    if (time <= 0) return ""
    
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = time }

    return when {
        // Today -> Show time (e.g., "10:45 AM")
        isSameDay(now, target) -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
        }
        // Yesterday -> "Yesterday"
        isYesterday(now, target) -> {
            "Yesterday"
        }
        // Same week -> Day name (e.g., "Mon")
        isSameWeek(now, target) -> {
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(time))
        }
        // Same year -> "MMM d" (e.g., "Oct 24")
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
        }
        // Different year -> "MM/dd/yy"
        else -> {
            SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(Date(time))
        }
    }
}

/**
 * Formats timestamps for individual chat bubbles (e.g., "10:45 AM").
 */
fun formatMessageTime(time: Long): String {
    if (time <= 0) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
}

/**
 * Formats dates for sticky chat section headers (e.g., "Today", "Yesterday", "Monday, Oct 24").
 */
fun formatDateHeader(time: Long): String {
    if (time <= 0) return ""

    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = time }

    return when {
        isSameDay(now, target) -> "Today"
        isYesterday(now, target) -> "Yesterday"
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> {
            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(time))
        }
        else -> {
            SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(time))
        }
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, target: Calendar): Boolean {
    val temp = now.clone() as Calendar
    temp.add(Calendar.DAY_OF_YEAR, -1)
    return isSameDay(temp, target)
}

private fun isSameWeek(now: Calendar, target: Calendar): Boolean {
    val diffMillis = now.timeInMillis - target.timeInMillis
    val diffDays = diffMillis / (24 * 60 * 60 * 1000)
    return diffDays < 7 && now.get(Calendar.DAY_OF_WEEK) >= target.get(Calendar.DAY_OF_WEEK)
}