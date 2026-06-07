package com.jsm.nsnd.ui.sleepdata

// TODO: 젯슨 나노 연동 후 실제 데이터 모델로 교체
data class SleepEventItem(
    val time: String,
    val location: String,
    val stage: Int
)