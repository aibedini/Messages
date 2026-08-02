package com.autonomousone.messages.utils

import android.text.format.DateUtils

fun formatSmsDate(time: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}