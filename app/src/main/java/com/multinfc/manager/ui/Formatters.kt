package com.multinfc.manager.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "未知"
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))
}
