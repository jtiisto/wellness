package dev.jtiisto.wellness.core.ble.buffer

/**
 * Where [IntervalBuffer] batches go. Implemented over the `hr_samples` DAO in
 * `:core:data`; this module never sees Room.
 *
 * The contract is all-or-nothing as far as the buffer is concerned: **throw**
 * and the batch stays buffered for a later retry, return and it is considered
 * durable and dropped. A partial write that returns normally silently loses
 * beats, so an implementation that cannot store the whole list must throw.
 */
fun interface HrSampleSink {
    suspend fun store(samples: List<BufferedSample>)
}
