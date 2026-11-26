package com.offlineface

/** Checks for clipped pixels in the central face region and, optionally, in each half. */
internal object FaceExposure {
  fun acceptable(pixels: IntArray, size: Int, checkSides: Boolean = false): Boolean {
    if (size < 4 || pixels.size != size * size) return false
    val start = size / 4
    val middle = size / 2
    val end = size * 3 / 4
    if (!regionIsUsable(pixels, size, start until end, 85)) return false
    return !checkSides ||
      (regionIsUsable(pixels, size, start until middle, 60) &&
        regionIsUsable(pixels, size, middle until end, 60))
  }

  private fun regionIsUsable(pixels: IntArray, size: Int, columns: IntRange, limit: Int): Boolean {
    var dark = 0
    var bright = 0
    var count = 0
    for (y in size / 4 until size * 3 / 4) {
      for (x in columns) {
        val value = pixels[y * size + x]
        if (value <= 10) dark++
        if (value >= 245) bright++
        count++
      }
    }
    return dark * 100 < count * limit && bright * 100 < count * limit
  }
}
