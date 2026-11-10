package com.offlineface

import com.facebook.react.bridge.ReactApplicationContext

class OfflineFaceModule(reactContext: ReactApplicationContext) :
  NativeOfflineFaceSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeOfflineFaceSpec.NAME
  }
}
