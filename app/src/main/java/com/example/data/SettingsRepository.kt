package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.receiverDataStore by preferencesDataStore(name = "mirabox_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val RECEIVER_NAME = stringPreferencesKey("receiver_name")
        val REQUIRE_OK_APPROVAL = booleanPreferencesKey("require_ok_approval")
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val LANDSCAPE_ROTATION = stringPreferencesKey("landscape_rotation")
        val ASPECT_SCALE_MODE = stringPreferencesKey("aspect_scale_mode")
        val DEX_DPI_PROFILE = stringPreferencesKey("dex_dpi_profile")
        val DEX_TASKBAR_ENABLED = booleanPreferencesKey("dex_taskbar_enabled")
        val UIBC_CONTROL_MODE = stringPreferencesKey("uibc_control_mode")
        val AIR_MOUSE_SPEED = floatPreferencesKey("air_mouse_speed")
        val LANGUAGE = stringPreferencesKey("language")
        val TRUSTED_DEVICES = stringSetPreferencesKey("trusted_devices")
    }

    val settingsFlow: Flow<ReceiverSettings> = context.receiverDataStore.data.map { prefs ->
        ReceiverSettings(
            receiverName = prefs[Keys.RECEIVER_NAME] ?: "MiraBox-TV",
            requireOkApproval = prefs[Keys.REQUIRE_OK_APPROVAL] ?: true,
            displayMode = enumValueOrDefault(prefs[Keys.DISPLAY_MODE], DisplayMode.DEX_TV_DESKTOP),
            landscapeRotation = enumValueOrDefault(
                prefs[Keys.LANDSCAPE_ROTATION],
                LandscapeRotation.AUTO_LANDSCAPE
            ),
            aspectScaleMode = enumValueOrDefault(
                prefs[Keys.ASPECT_SCALE_MODE],
                AspectScaleMode.FILL_WIDESCREEN
            ),
            dexDpiProfile = enumValueOrDefault(
                prefs[Keys.DEX_DPI_PROFILE],
                DexDpiProfile.TV_1080P_160DPI
            ),
            dexTaskbarEnabled = prefs[Keys.DEX_TASKBAR_ENABLED] ?: true,
            uibcControlMode = enumValueOrDefault(
                prefs[Keys.UIBC_CONTROL_MODE],
                UibcControlMode.HYBRID_AUTO
            ),
            airMouseSpeed = prefs[Keys.AIR_MOUSE_SPEED] ?: 1.0f,
            language = enumValueOrDefault(prefs[Keys.LANGUAGE], AppLanguage.ARABIC),
            trustedDevices = prefs[Keys.TRUSTED_DEVICES] ?: emptySet()
        )
    }

    suspend fun updateReceiverName(name: String) {
        val clean = name.trim().take(24).ifEmpty { "MiraBox-TV" }
        context.receiverDataStore.edit { it[Keys.RECEIVER_NAME] = clean }
    }

    suspend fun setRequireOkApproval(required: Boolean) {
        context.receiverDataStore.edit { it[Keys.REQUIRE_OK_APPROVAL] = required }
    }

    suspend fun setDisplayMode(mode: DisplayMode) {
        context.receiverDataStore.edit { it[Keys.DISPLAY_MODE] = mode.name }
    }

    suspend fun setLandscapeRotation(rotation: LandscapeRotation) {
        context.receiverDataStore.edit { it[Keys.LANDSCAPE_ROTATION] = rotation.name }
    }

    suspend fun setAspectScaleMode(scale: AspectScaleMode) {
        context.receiverDataStore.edit { it[Keys.ASPECT_SCALE_MODE] = scale.name }
    }

    suspend fun setDexDpiProfile(profile: DexDpiProfile) {
        context.receiverDataStore.edit { it[Keys.DEX_DPI_PROFILE] = profile.name }
    }

    suspend fun setDexTaskbarEnabled(enabled: Boolean) {
        context.receiverDataStore.edit { it[Keys.DEX_TASKBAR_ENABLED] = enabled }
    }

    suspend fun setUibcControlMode(mode: UibcControlMode) {
        context.receiverDataStore.edit { it[Keys.UIBC_CONTROL_MODE] = mode.name }
    }

    suspend fun setAirMouseSpeed(speed: Float) {
        context.receiverDataStore.edit { it[Keys.AIR_MOUSE_SPEED] = speed.coerceIn(0.4f, 2.5f) }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.receiverDataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun addTrustedDevice(deviceId: String) {
        context.receiverDataStore.edit { prefs ->
            val current = prefs[Keys.TRUSTED_DEVICES] ?: emptySet()
            prefs[Keys.TRUSTED_DEVICES] = current + deviceId
        }
    }

    suspend fun removeTrustedDevice(deviceId: String) {
        context.receiverDataStore.edit { prefs ->
            val current = prefs[Keys.TRUSTED_DEVICES] ?: emptySet()
            prefs[Keys.TRUSTED_DEVICES] = current - deviceId
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
        return raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }
}
