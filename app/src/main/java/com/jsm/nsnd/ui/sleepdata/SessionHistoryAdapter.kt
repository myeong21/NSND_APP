package com.jsm.nsnd.ui.sleepdata

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ItemSessionHistoryBinding
import com.jsm.nsnd.network.model.ReportHistoryItem

/** Local and server sessions remain separate rows; counts and scores are never combined. */
data class SessionHistoryRow(
    val server: ReportHistoryItem? = null,
    val local: List<com.jsm.nsnd.data.local.LocalDetectionRecord> = emptyList()
) {
    val timestamp: Long get() = server?.started_at?.let(com.jsm.nsnd.ui.common.ServerTime::millis)
        ?: local.minOfOrNull { it.receivedAtMs } ?: 0L
}

class SessionHistoryAdapter(
    private val items: List<SessionHistoryRow>,
    private val onItemClick: (SessionHistoryRow) -> Unit
) : RecyclerView.Adapter<SessionHistoryAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSessionHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = items[position]
        holder.binding.tvSafetyBadge.visibility = View.VISIBLE
        if (row.server == null) {
            val records = row.local
            val first = records.minByOrNull { it.receivedAtMs }!!
            val demo = records.all { it.id.startsWith("DEMO-") }
            holder.binding.tvSessionDate.text = first.receiptText().take(16)
            holder.binding.tvSafetyBadge.text = if (demo) "시연용 로컬 기록" else "로컬 기록"
            holder.binding.tvSafetyBadge.setBackgroundResource(R.drawable.bg_badge_warn)
            holder.binding.tvSafetyBadge.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.status_warn))
            holder.binding.tvSessionDuration.text = "주행 시간 미제공"
            holder.binding.tvDrowsyCount.text = "감지 ${records.size}건"
            holder.itemView.setOnClickListener { onItemClick(row) }
            return
        }
        val item = row.server
        val context = holder.itemView.context

        // 날짜 파싱 (예: 2026-09-18T14:30:00 -> 2026-09-18 14:30)
        val dateText = com.jsm.nsnd.ui.common.ServerTime.display(item.started_at)
        holder.binding.tvSessionDate.text = "$dateText · 서버"

        // 안전 점수 및 등급
        val gradeText = when (item.grade) {
            "safe" -> "안전"
            "caution" -> "주의"
            "danger" -> "위험"
            else -> item.grade
        }
        holder.binding.tvSafetyBadge.text = "참고 ${item.safety_score}점 · $gradeText"

        when (item.grade) {
            "danger" -> {
                holder.binding.tvSafetyBadge.setBackgroundResource(R.drawable.bg_badge_danger)
                holder.binding.tvSafetyBadge.setTextColor(ContextCompat.getColor(context, R.color.status_danger))
            }
            "caution" -> {
                holder.binding.tvSafetyBadge.setBackgroundResource(R.drawable.bg_badge_warn)
                holder.binding.tvSafetyBadge.setTextColor(ContextCompat.getColor(context, R.color.status_warn))
            }
            else -> {
                holder.binding.tvSafetyBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                holder.binding.tvSafetyBadge.setTextColor(ContextCompat.getColor(context, R.color.status_safe))
            }
        }

        if (item.valid_observation_seconds <= 0) {
            // Keep the drive in history without showing an unsupported safety grade.
            holder.binding.tvSafetyBadge.text = ""
            holder.binding.tvSafetyBadge.visibility = View.GONE
        }

        // 주행 시간
        val duration = item.duration_minutes ?: 0
        holder.binding.tvSessionDuration.text = "${duration}분 주행 · " +
            String.format(java.util.Locale.US, "%.2f km", item.distance_meters / 1000.0)

        // 졸음 횟수
        holder.binding.tvDrowsyCount.text = item.independent_event_count?.let {
            "독립 사건 ${it}회 · 단계 상승 ${item.total_drowsy_count}건"
        } ?: "단계 상승 ${item.total_drowsy_count}건"

        holder.itemView.setOnClickListener {
            onItemClick(row)
        }
    }

    override fun getItemCount(): Int = items.size
}
