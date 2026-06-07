package com.jsm.nsnd.data.api

import retrofit2.Call
import retrofit2.http.*

interface ContactApi {
    @GET("contacts")
    fun getContacts(@Header("Authorization") token: String): Call<List<ContactDto>>

    @POST("contacts")
    fun createContact(
        @Header("Authorization") token: String,
        @Body body: ContactRequest
    ): Call<ContactDto>

    @PUT("contacts/{id}")
    fun updateContact(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body body: ContactRequest
    ): Call<ContactDto>

    @DELETE("contacts/{id}")
    fun deleteContact(
        @Header("Authorization") token: String,
        @Path("id") id: Int
    ): Call<Void>
}