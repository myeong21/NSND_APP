package com.jsm.nsnd.ui.sleepdata

import java.util.Locale

object ObservationPresentation {
    fun describe(events: Int?, valid: Double, unavailable: Double, risk: Double,
                 coverage: Double?, rate: Double?, peak: Int): String {
        fun duration(seconds: Double) = String.format(Locale.KOREAN, "%.1f분", seconds / 60.0)
        val eventText = events?.let { "${it}회 · 최고 ${peak}단계" } ?: "자료 없음 (기존 기록에서 추정하지 않음)"
        val coverageText = coverage?.let { String.format(Locale.KOREAN,"%.1f%%",it * 100) } ?: "자료 없음"
        val rateText = rate?.takeIf { valid > 0 }?.let { String.format(Locale.KOREAN,"%.1f회/시간",it) } ?: "계산 불가"
        return "독립 졸음 사건 $eventText\n" +
            "유효 관측 ${duration(valid)} · 측정 불가 ${duration(unavailable)}\n" +
            "위험 단계 관측 ${duration(risk)} · 유효 관측당 $rateText\n" +
            "관측 구간 내 유효 비율 $coverageText\n" +
            "감지를 켠 동안 수집한 구간 기준입니다. 감지 중단·수집 전 시간은 포함되지 않습니다. " +
            (if (valid <= 0) "유효 관측 자료가 없어 판정 자료 부족으로 표시합니다." else
                "단계별 횟수는 단계 상승 기록이며 독립 사건 수와 다릅니다. 점수는 참고용입니다.")
    }
}
