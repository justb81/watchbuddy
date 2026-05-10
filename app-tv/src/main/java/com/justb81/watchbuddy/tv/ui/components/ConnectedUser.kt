package com.justb81.watchbuddy.tv.ui.components

/**
 * Represents a connected phone user selectable in the scope picker.
 *
 * @param id Stable unique identifier (e.g. [DeviceCapability.deviceId]).
 * @param displayName Human-readable name for the user, shown in the picker row.
 */
data class ConnectedUser(val id: String, val displayName: String)
