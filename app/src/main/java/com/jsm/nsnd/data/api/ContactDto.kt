package com.jsm.nsnd.data.api

data class ContactDto(
    val id: Int,
    val name: String,
    val phone: String,
    val message: String
)

data class ContactRequest(
    val name: String,
    val phone: String,
    val message: String
)