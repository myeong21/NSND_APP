package com.jsm.nsnd.ui.contact

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jsm.nsnd.databinding.ItemContactCardBinding

class ContactAdapter(
    private val items: MutableList<ContactItem>,
    private val onEdit: (ContactItem, Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    inner class ContactViewHolder(
        private val binding: ItemContactCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContactItem, position: Int) {
            binding.tvContactName.text = item.name
            binding.tvContactPhone.text = item.phone
            binding.tvContactMessage.text = "\"${item.message}\""

            // 편집 버튼
            binding.btnEdit.setOnClickListener {
                onEdit(item, position)
            }

            // 삭제 버튼 - 1개 남으면 비활성화
            if (items.size <= 1) {
                binding.btnDelete.alpha = 0.3f
                binding.btnDelete.isEnabled = false
            } else {
                binding.btnDelete.alpha = 1.0f
                binding.btnDelete.isEnabled = true
                binding.btnDelete.setOnClickListener {
                    onDelete(position)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount() = items.size
}