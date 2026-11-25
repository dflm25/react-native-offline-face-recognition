package com.offlineface

/** Reject frames whose central region is overwhelmingly clipped. */
internal object FaceExposure {
  fun acceptable(pixels: IntArray, size: Int): Boolean {
    if (size < 4 || pixels.size != size * size) return false
    var dark = 0
    var bright = 0
    var count = 0
    for (y in size / 4 until size * 3 / 4) {
      for (x in size / 4 until size * 3 / 4) {
        val value = pixels[y * size + x]
        if (value <= 10) dark++
        if (value >= 245) bright++
        count++
      }
    }
    return dark * 100 < count * 85 && bright * 100 < count * 85
  }
}
