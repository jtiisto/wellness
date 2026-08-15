package dev.jtiisto.wellness.core.data.network

/**
 * A calendar date in **local** time, `YYYY-MM-DD`.
 *
 * Never a timestamp: journal days are whatever the device calls "today", and
 * weekday integers (0=Sun…6=Sat) are derived from these, not from UTC. Sorts
 * and range-compares lexically, which for this format is chronological.
 */
typealias DateString = String
