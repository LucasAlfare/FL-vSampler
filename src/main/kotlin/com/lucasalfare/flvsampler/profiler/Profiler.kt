package com.lucasalfare.flvsampler.profiler

/**
 * Small utility used to measure execution time and JVM memory consumption
 * during the rendering pipeline.
 *
 * The profiler is intentionally simple and has no effect on the rendering
 * architecture itself.
 */
object Profiler {

  /**
   * Prints the current JVM heap usage.
   *
   * @param tag descriptive label identifying the current operation.
   */
  fun logMemory(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMb =
      (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)

    println("[PROFILER] Memory — $tag: $usedMb MB used / $maxMb MB JVM maximum")
  }

  /**
   * Measures the execution time and memory usage of an operation.
   *
   * @param tag descriptive label for the operation.
   * @param block operation to execute.
   * @return the value returned by [block].
   */
  inline fun <T> measure(tag: String, block: () -> T): T {
    println()
    println("[PROFILER] Starting: $tag")
    logMemory("Before $tag")

    val start = System.currentTimeMillis()
    val result = block()
    val elapsed = System.currentTimeMillis() - start

    logMemory("After $tag")
    println("[PROFILER] Completed: $tag in $elapsed ms (${elapsed / 1000.0}s)")

    return result
  }
}