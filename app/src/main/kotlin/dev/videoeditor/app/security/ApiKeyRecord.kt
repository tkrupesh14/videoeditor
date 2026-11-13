package dev.videoeditor.app.security

import java.util.Base64

/** Stored record format: `base64(NO_WRAP)(0x01 || iv[12] || ciphertext || tag[16])` (A1.2). */
object ApiKeyRecord {
    const val VERSION: Byte = 0x01
    const val IV_BYTES = 12
    const val TAG_BYTES = 16

    fun encode(sealed: ApiKeyCipher.Sealed): String {
        require(sealed.iv.size == IV_BYTES) { "GCM IV must be $IV_BYTES bytes" }
        require(sealed.ciphertextAndTag.size >= TAG_BYTES) { "ciphertext shorter than the GCM tag" }
        val out = ByteArray(1 + IV_BYTES + sealed.ciphertextAndTag.size)
        out[0] = VERSION
        sealed.iv.copyInto(out, 1)
        sealed.ciphertextAndTag.copyInto(out, 1 + IV_BYTES)
        return Base64.getEncoder().encodeToString(out)
    }

    /** @throws CorruptRecordException for anything that is not a well-formed v1 record. */
    fun decode(text: String): ApiKeyCipher.Sealed {
        val bytes =
            try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                throw CorruptRecordException("record is not base64")
            }
        if (bytes.size < 1 + IV_BYTES + TAG_BYTES + 1) throw CorruptRecordException("record too short")
        if (bytes[0] != VERSION) throw CorruptRecordException("unknown record version")
        return ApiKeyCipher.Sealed(
            iv = bytes.copyOfRange(1, 1 + IV_BYTES),
            ciphertextAndTag = bytes.copyOfRange(1 + IV_BYTES, bytes.size),
        )
    }
}
