package com.example.data

enum class DisplayMode {
    STANDARD_MIRROR,
    FORCE_LANDSCAPE,
    DEX_TV_DESKTOP
}

enum class LandscapeRotation(val degrees: Int) {
    AUTO_LANDSCAPE(-1),
    ROT_0(0),
    ROT_90(90),
    ROT_270(270)
}

enum class AspectScaleMode {
    FIT_16_9,
    FILL_WIDESCREEN,
    STRETCH_FULL
}

enum class DexDpiProfile(
    val width: Int,
    val height: Int,
    val refreshHz: Int,
    val dpi: Int,
    val wfdCeaCode: String
) {
    TV_1080P_160DPI(1920, 1080, 60, 160, "00010000"),
    TV_4K_240DPI(3840, 2160, 30, 240, "00080000"),
    COMPACT_720P_120DPI(1280, 720, 60, 120, "00000080")
}

enum class SecondScreenProfile(
    val width: Int,
    val height: Int,
    val dpi: Int
) {
    OFF(0, 0, 0),
    TABLET_720P(1280, 720, 160),
    TABLET_1080P(1920, 1080, 213),
    TABLET_SMOOTH(1024, 576, 136)
}

enum class UibcControlMode {
    HYBRID_AUTO,
    DPAD_KEYS,
    AIR_MOUSE_POINTER
}

enum class AppLanguage(val tag: String) {
    ARABIC("ar"),
    ENGLISH("en")
}

data class ReceiverSettings(
    val receiverName: String = "MiraBox-TV",
    val requireOkApproval: Boolean = true,
    val displayMode: DisplayMode = DisplayMode.DEX_TV_DESKTOP,
    val landscapeRotation: LandscapeRotation = LandscapeRotation.AUTO_LANDSCAPE,
    val aspectScaleMode: AspectScaleMode = AspectScaleMode.FILL_WIDESCREEN,
    val dexDpiProfile: DexDpiProfile = DexDpiProfile.TV_1080P_160DPI,
    val dexTaskbarEnabled: Boolean = true,
    val uibcControlMode: UibcControlMode = UibcControlMode.DPAD_KEYS,
    val airMouseSpeed: Float = 1.0f,
    val secondScreenProfile: SecondScreenProfile = SecondScreenProfile.TABLET_720P,
    val launchTabletLauncherOnCast: Boolean = true,
    val language: AppLanguage = AppLanguage.ARABIC,
    val trustedDevices: Set<String> = emptySet()
)
