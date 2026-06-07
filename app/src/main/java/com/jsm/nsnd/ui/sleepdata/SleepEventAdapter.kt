package com.jsm.nsnd.ui.sleepdata

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.ItemSleepEventCardBinding

class SleepEventAdapter(
    private val items: List<SleepEventItem>
) : RecyclerView.Adapter<SleepEventAdapter.SleepEventViewHolder>() {

    inner class SleepEventViewHolder(
        private val binding: ItemSleepEventCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SleepEventItem) {
            binding.tvSleepTime.text = item.time
            binding.tvSleepLocation.text = item.location

            // 단계별 배지 스타일
            when (item.stage) {
                1 -> {
                    binding.tvSleepStage.text =
                        binding.root.context.getString(R.string.sleep_stage_1)
                    binding.tvSleepStage.setBackgroundResource(R.drawable.bg_badge_stage1)
                    binding.tvSleepStage.setTextColor(
                        binding.root.context.getColor(R.color.stage_1)
                    )
                }
                2 -> {
                    binding.tvSleepStage.text =
                        binding.root.context.getString(R.string.sleep_stage_2)
                    binding.tvSleepStage.setBackgroundResource(R.drawable.bg_badge_stage2)
                    binding.tvSleepStage.setTextColor(
                        binding.root.context.getColor(R.color.stage_2)
                    )
                }
                3 -> {
                    binding.tvSleepStage.text =
                        binding.root.context.getString(R.string.sleep_stage_3)
                    binding.tvSleepStage.setBackgroundResource(R.drawable.bg_badge_stage3)
                    binding.tvSleepStage.setTextColor(
                        binding.root.context.getColor(R.color.stage_3)
                    )
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SleepEventViewHolder {
        val binding = ItemSleepEventCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SleepEventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SleepEventViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}