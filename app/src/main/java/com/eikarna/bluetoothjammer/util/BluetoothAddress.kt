package util

/**
 * Pure-Kotlin helpers for Bluetooth MAC addresses.
 *
 * Kept free of Android framework types so the validation logic can be covered by
 * fast host-side unit tests (see BluetoothAddressTest). This mirrors the contract of
 * [android.bluetooth.BluetoothAdapter.checkBluetoothAddress] but is testable off-device.
 */
object BluetoothAddress {

    // Six colon-separated, upper- or lower-case hex octets, e.g. "00:11:22:AA:BB:CC".
    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** True when [address] is a syntactically valid Bluetooth MAC address. */
    fun isValid(address: String?): Boolean {
        if (address == null) return false
        return MAC_REGEX.matches(address.trim())
    }

    /**
     * Normalizes user input into the canonical upper-case colon-separated form, or returns
     * null when the input cannot be interpreted as a MAC address.
     *
     * Accepts input that uses '-' separators or no separators at all (12 hex chars), so a
     * pasted address like "0011.22aabbcc" or "00-11-22-aa-bb-cc" still works.
     */
    fun normalize(input: String?): String? {
        if (input == null) return null
        val hex = input.trim().filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || !hex.all { it in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }
}
