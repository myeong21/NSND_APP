package com.jsm.nsnd.ui.sleepdata

data class SleepEventItem(
    val time: String,
    val location: String,
    val stage: Int,
    val localRecord: com.jsm.nsnd.data.local.LocalDetectionRecord? = null,
    val serverRecord: com.jsm.nsnd.ui.common.MapRecord? = null
)
