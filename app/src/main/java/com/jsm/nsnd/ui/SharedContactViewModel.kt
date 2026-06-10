package com.jsm.nsnd.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.jsm.nsnd.ui.contact.ContactItem

class SharedContactViewModel : ViewModel() {
    val contacts = MutableLiveData<List<ContactItem>>(emptyList())
}