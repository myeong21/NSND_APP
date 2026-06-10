package com.jsm.nsnd.ui.contact

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.jsm.nsnd.R
import com.jsm.nsnd.databinding.DialogContactBinding
import com.jsm.nsnd.databinding.FragmentContactBinding

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import androidx.fragment.app.activityViewModels
import com.jsm.nsnd.ui.SharedContactViewModel

class ContactFragment : Fragment() {

    private var _binding: FragmentContactBinding? = null
    private val binding get() = _binding!!

    private val contactList = mutableListOf<ContactItem>()
    private lateinit var adapter: ContactAdapter
    private val sharedViewModel: SharedContactViewModel by activityViewModels()

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendAllContacts()
            } else {
                Toast.makeText(requireContext(), "SMS 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFab()
        setupSendButton()
        updateEmptyState()
    }

    // ─────────────────────────────────────────
    // RecyclerView 설정
    // ─────────────────────────────────────────
    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            items = contactList,
            onEdit = { item, position -> showContactDialog(item, position) },
            onDelete = { position -> deleteContact(position) }
        )
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ContactFragment.adapter
        }
    }

    // ─────────────────────────────────────────
    // FAB 클릭 - 연락처 추가
    // ─────────────────────────────────────────
    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            showContactDialog(null, -1)
        }
    }

    // ─────────────────────────────────────────
    // 임시 발송 추가
    // ─────────────────────────────────────────
    private fun setupSendButton() {
        binding.btnSendContacts.setOnClickListener {
            requestSmsPermissionAndSend()
        }
    }

    // ─────────────────────────────────────────
    // 연락처 추가 / 편집 다이얼로그
    // ─────────────────────────────────────────
    private fun showContactDialog(existingItem: ContactItem?, position: Int) {
        val dialog = Dialog(requireContext())
        val dialogBinding = DialogContactBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 편집 모드일 때 기존 값 채우기
        val isEditMode = existingItem != null
        dialogBinding.tvDialogTitle.text = if (isEditMode) "연락처 편집" else "연락처 추가"
        if (isEditMode) {
            dialogBinding.etName.setText(existingItem!!.name)
            dialogBinding.etPhone.setText(existingItem.phone)
        }

        // 메시지 선택 스피너 설정
        val messages = resources.getStringArray(R.array.contact_messages)
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            messages
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        dialogBinding.spinnerMessage.adapter = spinnerAdapter

        // 편집 모드일 때 기존 메시지 선택
        if (isEditMode) {
            val messageIndex = messages.indexOf(existingItem!!.message)
            if (messageIndex >= 0) dialogBinding.spinnerMessage.setSelection(messageIndex)
        }

        // 취소 버튼
        dialogBinding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        // 저장 버튼
        dialogBinding.btnDialogSave.setOnClickListener {
            val name = dialogBinding.etName.text.toString().trim()
            val phone = dialogBinding.etPhone.text.toString().trim()
            val message = dialogBinding.spinnerMessage.selectedItem.toString()

            // 입력값 검증
            if (name.isEmpty()) {
                dialogBinding.tilName.error = "이름을 입력해주세요"
                return@setOnClickListener
            }
            if (phone.isEmpty()) {
                dialogBinding.tilPhone.error = "연락처를 입력해주세요"
                return@setOnClickListener
            }

            val newItem = ContactItem(
                id = existingItem?.id ?: System.currentTimeMillis().toInt(),
                name = name,
                phone = phone,
                message = message
            )

            if (isEditMode) {
                contactList[position] = newItem
                adapter.notifyItemChanged(position)
            } else {
                contactList.add(newItem)
                adapter.notifyItemInserted(contactList.size - 1)
            }

            // TODO: 젯슨 나노 서버에 연락처 저장 요청으로 교체
            updateEmptyState()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ─────────────────────────────────────────
    // 연락처 삭제
    // ─────────────────────────────────────────
    private fun deleteContact(position: Int) {
        if (contactList.size <= 1) {
            Toast.makeText(
                requireContext(),
                getString(R.string.contact_min_warning),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // TODO: 젯슨 나노 서버에 연락처 삭제 요청으로 교체
        contactList.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, contactList.size)
        updateEmptyState()
    }

    private fun requestSmsPermissionAndSend() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            sendAllContacts()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun sendAllContacts() {
        if (contactList.isEmpty()) {
            Toast.makeText(requireContext(), "발송할 연락처가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val smsManager = SmsManager.getDefault()
        contactList.forEach { contact ->
            smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
        }

        Toast.makeText(
            requireContext(),
            "연락처 ${contactList.size}개에 발송했습니다",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ─────────────────────────────────────────
    // 빈 상태 표시
    // ─────────────────────────────────────────
    private fun updateEmptyState() {
        if (contactList.isEmpty()) {
            binding.tvContactEmpty.visibility = View.VISIBLE
            binding.rvContacts.visibility = View.GONE
        } else {
            binding.tvContactEmpty.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
        sharedViewModel.contacts.value = contactList.toList()  // 추가
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}