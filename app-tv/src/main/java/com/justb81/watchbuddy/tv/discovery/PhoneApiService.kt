package com.justb81.watchbuddy.tv.discovery

import com.justb81.watchbuddy.core.model.TokenResponse
import retrofit2.http.GET

/** Retrofit client for the phone's CompanionHttpServer (dynamic base URL). */
interface PhoneApiService {

    @GET("/auth/token")
    suspend fun getAccessToken(): TokenResponse
}
