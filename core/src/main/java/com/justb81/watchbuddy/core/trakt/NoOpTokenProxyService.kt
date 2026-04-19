package com.justb81.watchbuddy.core.trakt

class NoOpTokenProxyService : TokenProxyService {

    override suspend fun exchangeDeviceCode(body: ProxyTokenRequest): ProxyTokenResponse =
        throw UnsupportedOperationException("managed backend disabled")

    override suspend fun refreshToken(body: ProxyRefreshRequest): ProxyTokenResponse =
        throw UnsupportedOperationException("managed backend disabled")
}
