package com.offlineface

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap

class OfflineFaceModule(reactContext: ReactApplicationContext) :
  NativeOfflineFaceSpec(reactContext) {

  private var pendingPromise: Promise? = null
  private val store = FaceTemplateStore(reactContext)

  private val activityEventListener =
    object : BaseActivityEventListener() {
      override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
      ) {
        if (requestCode != REQUEST_CAPTURE) {
          return
        }
        val promise = pendingPromise ?: return
        pendingPromise = null

        if (resultCode != Activity.RESULT_OK || data == null) {
          promise.resolve(failureMap("cancelled"))
          return
        }

        val map = Arguments.createMap()
        val success = data.getBooleanExtra(EXTRA_SUCCESS, false)
        map.putBoolean("success", success)
        if (success) {
          data.getStringExtra(EXTRA_TEMPLATE_ID)?.let { map.putString("templateId", it) }
          data.getStringExtra(EXTRA_PHOTO)?.let { map.putString("photoBase64", it) }
          if (data.hasExtra(EXTRA_SCORE)) {
            map.putDouble("score", data.getDoubleExtra(EXTRA_SCORE, 0.0))
          }
        } else {
          map.putString("error", data.getStringExtra(EXTRA_ERROR) ?: "unknown_error")
          if (data.hasExtra(EXTRA_SCORE)) {
            map.putDouble("score", data.getDoubleExtra(EXTRA_SCORE, 0.0))
          }
        }
        if (data.hasExtra(EXTRA_LIVENESS)) {
          map.putBoolean("livenessPassed", data.getBooleanExtra(EXTRA_LIVENESS, false))
        }
        promise.resolve(map)
      }
    }

  init {
    reactContext.addActivityEventListener(activityEventListener)
  }

  override fun registerFace(options: ReadableMap, promise: Promise) {
    startCapture(MODE_REGISTER, options, promise)
  }

  override fun verifyFace(options: ReadableMap, promise: Promise) {
    startCapture(MODE_VERIFY, options, promise)
  }

  override fun hasFaceTemplate(promise: Promise) {
    try {
      promise.resolve(store.hasTemplate())
    } catch (_: Throwable) {
      promise.resolve(false)
    }
  }

  override fun deleteFaceTemplate(promise: Promise) {
    try {
      store.delete()
      promise.resolve(null)
    } catch (_: Throwable) {
      promise.reject("storage_error", "Could not delete the local face template")
    }
  }

  override fun getFaceCapabilities(promise: Promise) {
    val map: WritableMap = Arguments.createMap()
    val available = hasFrontCamera()
    map.putBoolean("available", available)
    map.putString("platform", "android")
    map.putBoolean("supportsLiveness", available)
    promise.resolve(map)
  }

  override fun invalidate() {
    reactApplicationContext.removeActivityEventListener(activityEventListener)
    pendingPromise?.reject("unknown_error", "Native module was invalidated")
    pendingPromise = null
    super.invalidate()
  }

  private fun startCapture(mode: String, options: ReadableMap?, promise: Promise) {
    if (pendingPromise != null) {
      promise.reject("already_in_progress", "A face capture is already in progress")
      return
    }

    val parsed = FaceCaptureOptions.fromMap(options)
    if (parsed == null) {
      promise.reject("invalid_options", "Invalid timeout, threshold or liveness option")
      return
    }

    if (mode == MODE_VERIFY && !store.hasTemplate()) {
      promise.resolve(failureMap("no_template"))
      return
    }

    val activity = reactApplicationContext.currentActivity
    if (activity == null) {
      promise.reject("camera_unavailable", "No foreground activity is available")
      return
    }

    pendingPromise = promise
    activity.runOnUiThread {
      try {
        val intent =
          Intent(activity, FaceCaptureActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_TIMEOUT, parsed.timeoutMs)
            putExtra(EXTRA_REQUIRE_LIVENESS, parsed.requireLiveness)
            putExtra(EXTRA_THRESHOLD, parsed.threshold)
            putExtra(EXTRA_ALLOW_GLASSES, parsed.allowGlasses)
            putExtra(EXTRA_GLASSES_SENSITIVITY, parsed.glassesSensitivity)
            putExtra(EXTRA_RETURN_PHOTO, mode == MODE_REGISTER && parsed.returnPhoto)
          }
        activity.startActivityForResult(intent, REQUEST_CAPTURE)
      } catch (_: Throwable) {
        if (pendingPromise === promise) {
          pendingPromise = null
        }
        promise.reject("camera_unavailable", "Could not open the face capture screen")
      }
    }
  }

  private fun hasFrontCamera(): Boolean {
    if (!reactApplicationContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT)) {
      return false
    }
    return try {
      val manager =
        reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
          ?: return false
      manager.cameraIdList.any { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
          CameraCharacteristics.LENS_FACING_FRONT
      }
    } catch (_: Throwable) {
      false
    }
  }

  private fun failureMap(error: String): WritableMap {
    return Arguments.createMap().apply {
      putBoolean("success", false)
      putString("error", error)
    }
  }

  companion object {
    const val NAME = NativeOfflineFaceSpec.NAME
    private const val REQUEST_CAPTURE = 4816
  }
}
