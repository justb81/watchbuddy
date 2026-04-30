package com.justb81.watchbuddy.phone.auth

/**
 * Thrown when the Android Keystore is unavailable (locked, compromised, or unsupported
 * hardware) so callers can distinguish a hard hardware failure from a normal
 * "no token stored" result. The UI layer translates this into a user-facing
 * "Your device's secure storage is unavailable" message.
 */
class AuthUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
