package com.jsm.nsnd.ui.contact

import android.app.Dialog
import android.content.Intent
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
import com.jsm.nsnd.data.api.ApiClient
import com.jsm.nsnd.data.api.ContactDto
import com.jsm.nsnd.data.api.ContactRequest
import com.jsm.nsnd.data.session.SessionManager
import com.jsm.nsnd.ui.auth.LoginActivity
import com.jsm.nsnd.ui.common.ApiErrorMessage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ContactFragment : Fragment() {

    private var _binding: FragmentContactBinding? = null
    private val binding get() = _binding!!

    private val contactList = mutableListOf<ContactItem>()
    private lateinit var adapter: ContactAdapter
    private val sharedViewModel: SharedContactViewModel by activityViewModels()
    private val sessionManager by lazy { SessionManager(requireContext()) }
    private var isServerBusy = false
    private var lastSyncAt = 0L

    private fun authHeader(): String = sessionManager.getAuthHeader()

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
        replaceContacts(ContactLocalStore.load(requireContext()), saveCache = false)
        setupFab()
        setupSendButton()
        syncContactsFromServer()
    }

    // ─────────────────────────────────────────
    // RecyclerView 설정
    // ─────────────────────────────────────────
    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            items = contactList,
            onEdit = { item, position ->
                if (allowServerChange()) showContactDialog(item, position)
            },
            onDelete = { position ->
                if (allowServerChange()) deleteContact(position)
            }
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
            if (allowServerChange()) showContactDialog(null, -1)
        }
    }

    private fun allowServerChange(): Boolean {
        if (!isServerBusy) return true
        Toast.makeText(requireContext(), "연락처를 서버와 동기화하고 있습니다", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun setServerBusy(busy: Boolean) {
        isServerBusy = busy
        _binding?.fabAddContact?.isEnabled = !busy
        _binding?.fabAddContact?.alpha = if (busy) 0.45f else 1f
    }

    // ─────────────────────────────────────────
    // 서버 연락처 동기화
    // ─────────────────────────────────────────
    private fun syncContactsFromServer() {
        if (isServerBusy) return
        val cachedContacts = contactList.toList()
        setServerBusy(true)
        ApiClient.contactApi(requireContext()).getContacts(authHeader())
            .enqueue(object : Callback<List<ContactDto>> {
                override fun onResponse(
                    call: Call<List<ContactDto>>,
                    response: Response<List<ContactDto>>
                ) {
                    if (!isAdded || _binding == null) return
                    if (response.code() == 401) {
                        handleSessionExpired()
                        return
                    }
                    val serverContacts = response.body()
                    if (!response.isSuccessful || serverContacts == null) {
                        setServerBusy(false)
                        showServerError(ApiErrorMessage.fromResponse(response))
                        return
                    }

                    if (!ContactLocalStore.isServerMigrationComplete(requireContext()) &&
                        cachedContacts.isNotEmpty()
                    ) {
                        migrateCachedContacts(serverContacts, cachedContacts)
                    } else {
                        ContactLocalStore.markServerMigrationComplete(requireContext())
                        replaceContacts(serverContacts.map { it.toItem() })
                        lastSyncAt = System.currentTimeMillis()
                        setServerBusy(false)
                    }
                }

                override fun onFailure(call: Call<List<ContactDto>>, error: Throwable) {
                    if (!isAdded || _binding == null) return
                    setServerBusy(false)
                    showServerError(ApiErrorMessage.fromThrowable(error, "연락처 동기화"))
                }
            })
    }

    /** 기존 로컬 연락처 중 서버에 없는 항목만 최초 1회 업로드합니다. */
    private fun migrateCachedContacts(
        serverContacts: List<ContactDto>,
        cachedContacts: List<ContactItem>
    ) {
        val normalizedCached = cachedContacts.map { item ->
            item.copy(phone = item.phone.filter(Char::isDigit))
        }
        val invalidContacts = normalizedCached
            .filter { it.name.isBlank() || it.phone.length !in 8..15 || it.message.isBlank() }
            .map { it.copy(id = 0) }
            .toMutableList()
        val existingPhones = serverContacts.map { it.phone.filter(Char::isDigit) }.toSet()
        val pending = normalizedCached
            .filter { it.name.isNotBlank() && it.phone.length in 8..15 && it.message.isNotBlank() }
            .distinctBy { it.phone }
            .filter { it.phone !in existingPhones }
        if (pending.isEmpty()) {
            finishCachedMigration(serverContacts, invalidContacts)
            return
        }
        uploadCachedContact(pending, 0, serverContacts.toMutableList(), invalidContacts)
    }

    private fun uploadCachedContact(
        pending: List<ContactItem>,
        index: Int,
        serverContacts: MutableList<ContactDto>,
        unresolvedContacts: MutableList<ContactItem>
    ) {
        if (index >= pending.size) {
            finishCachedMigration(serverContacts, unresolvedContacts)
            return
        }

        val item = pending[index]
        ApiClient.contactApi(requireContext())
            .createContact(authHeader(), ContactRequest(item.name, item.phone, item.message))
            .enqueue(object : Callback<ContactDto> {
                override fun onResponse(call: Call<ContactDto>, response: Response<ContactDto>) {
                    if (!isAdded || _binding == null) return
                    if (response.code() == 401) {
                        handleSessionExpired()
                        return
                    }
                    val created = response.body()
                    if (!response.isSuccessful || created == null) {
                        if (response.code() == 409 || response.code() == 422) {
                            unresolvedContacts.add(item.copy(id = 0))
                            uploadCachedContact(
                                pending,
                                index + 1,
                                serverContacts,
                                unresolvedContacts
                            )
                        } else {
                            setServerBusy(false)
                            showServerError("기존 연락처 이전에 실패했습니다. ${ApiErrorMessage.fromResponse(response)}")
                        }
                        return
                    }
                    serverContacts.add(created)
                    uploadCachedContact(
                        pending,
                        index + 1,
                        serverContacts,
                        unresolvedContacts
                    )
                }

                override fun onFailure(call: Call<ContactDto>, error: Throwable) {
                    if (!isAdded || _binding == null) return
                    setServerBusy(false)
                    showServerError(ApiErrorMessage.fromThrowable(error, "기존 연락처 이전"))
                }
            })
    }

    private fun finishCachedMigration(
        serverContacts: List<ContactDto>,
        unresolvedContacts: List<ContactItem>
    ) {
        if (unresolvedContacts.isEmpty()) {
            ContactLocalStore.markServerMigrationComplete(requireContext())
        }
        val merged = serverContacts.map { it.toItem() } + unresolvedContacts
        replaceContacts(merged)
        lastSyncAt = System.currentTimeMillis()
        setServerBusy(false)
        Toast.makeText(
            requireContext(),
            if (unresolvedContacts.isEmpty()) {
                "기존 연락처를 서버에 동기화했습니다"
            } else {
                "전화번호가 올바르지 않은 연락처 ${unresolvedContacts.size}개는 이전하지 않았습니다. 수정하거나 삭제해주세요."
            },
            Toast.LENGTH_LONG
        ).show()
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
            val normalizedPhone = phone.filter(Char::isDigit)
            if (normalizedPhone.length !in 8..15) {
                dialogBinding.tilPhone.error = "전화번호를 8~15자리 숫자로 입력해주세요"
                return@setOnClickListener
            }
            if (contactList.any { it.id != existingItem?.id && it.phone.filter(Char::isDigit) == normalizedPhone }) {
                dialogBinding.tilPhone.error = "이미 등록된 전화번호입니다"
                return@setOnClickListener
            }

            submitContact(
                dialog = dialog,
                dialogBinding = dialogBinding,
                existingItem = existingItem,
                fallbackPosition = position,
                request = ContactRequest(name, normalizedPhone, message)
            )
        }

        dialog.show()
    }

    private fun submitContact(
        dialog: Dialog,
        dialogBinding: DialogContactBinding,
        existingItem: ContactItem?,
        fallbackPosition: Int,
        request: ContactRequest
    ) {
        dialogBinding.btnDialogSave.isEnabled = false
        dialogBinding.btnDialogSave.text = "저장 중…"
        val call = if (existingItem == null || existingItem.id <= 0) {
            ApiClient.contactApi(requireContext()).createContact(authHeader(), request)
        } else {
            ApiClient.contactApi(requireContext()).updateContact(authHeader(), existingItem.id, request)
        }

        call.enqueue(object : Callback<ContactDto> {
            override fun onResponse(call: Call<ContactDto>, response: Response<ContactDto>) {
                if (!isAdded || _binding == null) {
                    dialog.dismiss()
                    return
                }
                if (response.code() == 401) {
                    dialog.dismiss()
                    handleSessionExpired()
                    return
                }
                val saved = response.body()
                if (!response.isSuccessful || saved == null) {
                    restoreDialogSaveButton(dialogBinding)
                    showServerError(ApiErrorMessage.fromResponse(response))
                    return
                }

                val savedItem = saved.toItem()
                if (existingItem == null) {
                    contactList.add(savedItem)
                } else {
                    val currentIndex = contactList.indexOfFirst { it.id == existingItem.id }
                        .takeIf { it >= 0 } ?: fallbackPosition
                    if (currentIndex in contactList.indices) contactList[currentIndex] = savedItem
                }
                replaceContacts(contactList.toList())
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    if (existingItem == null) "연락처를 서버에 저장했습니다" else "연락처를 수정했습니다",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onFailure(call: Call<ContactDto>, error: Throwable) {
                if (!isAdded || _binding == null) return
                restoreDialogSaveButton(dialogBinding)
                showServerError(ApiErrorMessage.fromThrowable(error, "연락처 저장"))
            }
        })
    }

    private fun restoreDialogSaveButton(dialogBinding: DialogContactBinding) {
        dialogBinding.btnDialogSave.isEnabled = true
        dialogBinding.btnDialogSave.text = "저장"
    }

    // ─────────────────────────────────────────
    // 연락처 삭제
    // ─────────────────────────────────────────
    private fun deleteContact(position: Int) {
        val item = contactList.getOrNull(position) ?: return
        if (item.id <= 0) {
            contactList.removeAt(position)
            replaceContacts(contactList.toList())
            Toast.makeText(requireContext(), "이전하지 못한 연락처를 삭제했습니다", Toast.LENGTH_SHORT).show()
            syncContactsFromServer()
            return
        }
        if (contactList.size <= 1) {
            Toast.makeText(
                requireContext(),
                getString(R.string.contact_min_warning),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        setServerBusy(true)
        ApiClient.contactApi(requireContext()).deleteContact(authHeader(), item.id)
            .enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (!isAdded || _binding == null) return
                    if (response.code() == 401) {
                        handleSessionExpired()
                        return
                    }
                    if (!response.isSuccessful) {
                        setServerBusy(false)
                        showServerError(ApiErrorMessage.fromResponse(response))
                        return
                    }
                    contactList.removeAll { it.id == item.id }
                    replaceContacts(contactList.toList())
                    setServerBusy(false)
                    Toast.makeText(requireContext(), "연락처를 삭제했습니다", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(call: Call<Void>, error: Throwable) {
                    if (!isAdded || _binding == null) return
                    setServerBusy(false)
                    showServerError(ApiErrorMessage.fromThrowable(error, "연락처 삭제"))
                }
            })
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

        try {
            val smsManager = SmsManager.getDefault()
            contactList.forEach { contact ->
                smsManager.sendTextMessage(contact.phone, null, contact.message, null, null)
            }
            Toast.makeText(
                requireContext(),
                "연락처 ${contactList.size}개에 발송했습니다",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "SMS 발송에 실패했습니다: ${e.message ?: "알 수 없는 오류"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ─────────────────────────────────────────
    // 목록 표시 및 계정별 오프라인 캐시 갱신
    // ─────────────────────────────────────────
    private fun replaceContacts(items: List<ContactItem>, saveCache: Boolean = true) {
        contactList.clear()
        contactList.addAll(items)
        if (contactList.isEmpty()) {
            binding.tvContactEmpty.visibility = View.VISIBLE
            binding.rvContacts.visibility = View.GONE
        } else {
            binding.tvContactEmpty.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
        sharedViewModel.contacts.value = contactList.toList()
        if (saveCache) ContactLocalStore.save(requireContext(), contactList)
    }

    private fun showServerError(message: String) {
        Toast.makeText(
            requireContext(),
            "$message\n마지막으로 동기화한 연락처는 기기에 유지됩니다.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleSessionExpired() {
        setServerBusy(false)
        sessionManager.clear()
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    private fun ContactDto.toItem() = ContactItem(id, name, phone, message)
    override fun onResume() {
        super.onResume()
        if (_binding != null && System.currentTimeMillis() - lastSyncAt > 5_000L) {
            syncContactsFromServer()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
