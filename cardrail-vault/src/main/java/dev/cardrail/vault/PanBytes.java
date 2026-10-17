package dev.cardrail.vault;

/** ASCII conversion for digit arrays, avoiding a Charset round trip through intermediate buffers we cannot wipe. */
final class PanBytes {

    private PanBytes() {
    }

    static byte[] toAscii(char[] digits) {
        byte[] bytes = new byte[digits.length];
        for (int i = 0; i < digits.length; i++) {
            bytes[i] = (byte) digits[i];
        }
        return bytes;
    }

    static char[] fromAscii(byte[] bytes) {
        char[] digits = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            digits[i] = (char) bytes[i];
        }
        return digits;
    }
}
