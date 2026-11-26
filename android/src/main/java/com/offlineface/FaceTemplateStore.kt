package com.offlineface

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores exactly one encrypted face template in the app's no-backup directory.
 *
 * - AES-256-GCM key lives in Android Keystore and is not exportable.
 * - The template file is excluded from Android auto-backup because it lives in
 *   `noBackupFilesDir`; a restored ciphertext would be useless anyway.
 * - No plaintext template, image or embedding ever touches disk.
 */
internal class FaceTemplateStore(context: Context) {
  private val appContext = context.applicationContext
  private val templateFile = File(appContext.noBackupFilesDir, FILE_NAME)

  @Synchronized
  fun hasTemplate(): Boolean {
    return try {
      load()?.descriptors?.isNotEmpty() == true
    } catch (_: Throwable) {
      false
    }
  }

  @Synchronized
  fun load(): FaceTemplateRecord? {
    if (!templateFile.exists()) {
      return null
    }

    return try {
      val key = existingKey() ?: return null
      val envelope = templateFile.readBytes()
      if (envelope.size < HEADER_SIZE + 16 || String(envelope, 0, 4, Charsets.US_ASCII) != MAGIC) {
        return null
      }

      val iv = envelope.copyOfRange(4, HEADER_SIZE)
      val ciphertext = envelope.copyOfRange(HEADER_SIZE, envelope.size)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
      cipher.updateAAD(AAD)
      val plaintext = cipher.doFinal(ciphertext)
      decode(plaintext)
    } catch (_: Throwable) {
      null
    }
  }

  @Synchronized
  fun save(descriptors: List<FloatArray>): FaceTemplateRecord? {
    if (descriptors.isEmpty() || descriptors.any { it.size != FaceFeatureExtractor.DESCRIPTOR_SIZE }) {
      return null
    }

    return try {
      val key = keyForWrite() ?: return null
      val record =
        FaceTemplateRecord(
          templateId = UUID.randomUUID().toString(),
          format = FaceDescriptorFormat.LIGHTING_NORMALIZED,
          descriptors = descriptors.map { it.copyOf() },
          createdAt = System.currentTimeMillis(),
        )
      val plaintext = encode(record).toByteArray(Charsets.UTF_8)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      cipher.updateAAD(AAD)
      val ciphertext = cipher.doFinal(plaintext)
      val iv = cipher.iv

      if (iv.size != IV_SIZE) {
        return null
      }

      val envelope = ByteArrayOutputStream()
      envelope.write(MAGIC.toByteArray(Charsets.US_ASCII))
      envelope.write(iv)
      envelope.write(ciphertext)

      val tempFile = File(templateFile.parentFile, "${templateFile.name}.tmp")
      try {
        FileOutputStream(tempFile).use { output ->
          output.write(envelope.toByteArray())
          output.fd.sync()
        }
        // Same-directory rename replaces the old template only after the new write succeeds.
        Os.rename(tempFile.absolutePath, templateFile.absolutePath)
      } finally {
        tempFile.delete()
      }
      record
    } catch (_: Throwable) {
      null
    }
  }

  @Synchronized
  fun delete() {
    try {
      templateFile.delete()
      File(templateFile.parentFile, "${templateFile.name}.tmp").delete()
    } catch (_: Throwable) {
      // Best effort; the template is unreadable without the Keystore key.
    }
    try {
      val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
      if (keyStore.containsAlias(KEY_ALIAS)) {
        keyStore.deleteEntry(KEY_ALIAS)
      }
    } catch (_: Throwable) {
      // Best effort.
    }
  }

  private fun existingKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    return entry?.secretKey
  }

  private fun keyForWrite(): SecretKey? {
    existingKey()?.let { return it }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build()
    generator.init(spec)
    return generator.generateKey()
  }

  private fun encode(record: FaceTemplateRecord): String {
    val descriptors = JSONArray()
    record.descriptors.forEach { descriptor ->
      descriptors.put(Base64.encodeToString(toLittleEndian(descriptor), Base64.NO_WRAP))
    }
    return JSONObject()
      .put("version", record.format.version)
      .put("templateId", record.templateId)
      .put("createdAt", record.createdAt)
      .put("platform", "android")
      .put("descriptors", descriptors)
      .toString()
  }

  private fun decode(plaintext: ByteArray): FaceTemplateRecord? {
    val json = JSONObject(String(plaintext, Charsets.UTF_8))
    val version = json.optInt("version", -1)
    val format = FaceDescriptorFormat.entries.firstOrNull { it.version == version } ?: return null
    val templateId = json.optString("templateId", "")
    if (templateId.isEmpty()) {
      return null
    }
    val array = json.optJSONArray("descriptors") ?: return null
    if (array.length() < 1) {
      return null
    }

    val descriptors = ArrayList<FloatArray>(array.length())
    for (index in 0 until array.length()) {
      val encoded = array.optString(index, "")
      if (encoded.isEmpty()) {
        return null
      }
      val bytes =
        try {
          Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
          return null
        }
      if (bytes.size != FaceFeatureExtractor.DESCRIPTOR_SIZE * Float.SIZE_BYTES) {
        return null
      }
      val descriptor = fromLittleEndian(bytes)
      if (descriptor.any { it.isNaN() || it.isInfinite() }) {
        return null
      }
      descriptors.add(descriptor)
    }

    return FaceTemplateRecord(
      templateId = templateId,
      format = format,
      descriptors = descriptors,
      createdAt = json.optLong("createdAt", 0L),
    )
  }

  private fun toLittleEndian(values: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buffer.putFloat(it) }
    return buffer.array()
  }

  private fun fromLittleEndian(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val values = FloatArray(bytes.size / Float.SIZE_BYTES)
    for (index in values.indices) {
      values[index] = buffer.getFloat()
    }
    return values
  }

  private companion object {
    const val FILE_NAME = "react_native_offline_face_v1.bin"
    const val MAGIC = "OF01"
    const val HEADER_SIZE = 4 + 12
    const val IV_SIZE = 12
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "react-native-offline-face.template.v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    val AAD = "react-native-offline-face:v1".toByteArray(Charsets.UTF_8)
  }
}
