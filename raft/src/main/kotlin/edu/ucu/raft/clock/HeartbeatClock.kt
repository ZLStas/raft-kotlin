package edu.ucu.raft.clock

import edu.ucu.raft.actions.HeartbeatAction
import edu.ucu.raft.actions.CommitAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.*
import kotlin.concurrent.fixedRateTimer

/**
 * Manages the heartbeat timer for a Raft node, allowing dynamic adjustment of the interval
 * based on network conditions.
 */
class HeartbeatClock(
    private var initialInterval: Long,
    private val heartbeatAction: HeartbeatAction,
    private val commitAction: CommitAction
) {
    private val logger = KotlinLogging.logger {}
    
    // The current interval between heartbeats
    private var _interval: Long = initialInterval
    val interval: Long
        get() = _interval
    
    private val mutex: Mutex = Mutex(false)
    private var stopped = true
    private lateinit var timer: Timer
    
    /**
     * Start the heartbeat timer
     */
    suspend fun start() {
        mutex.withLock {
            if (stopped) {
                stopped = false
                schedule()
            }
        }
    }
    
    /**
     * Schedule the heartbeat timer with the current interval
     */
    private fun schedule() {
        timer = fixedRateTimer(initialDelay = _interval, period = _interval) {
            runBlocking {
                kotlin.runCatching {
                    // Execute the heartbeat action first
                    heartbeatAction.send()
                    // Then execute the commit action
                    commitAction.perform()
                }.onFailure {
                    logger.error { "Heartbeat or commit action failure: $it" }
                }
            }
        }
    }
    
    /**
     * Update the heartbeat interval and reschedule the timer
     */
    suspend fun updateInterval(newInterval: Long) {
        mutex.withLock {
            if (newInterval == _interval) {
                // No change, don't reschedule
                return
            }
            
            // Log the change
            logger.info { "⏱️ Updating heartbeat interval: ${_interval}ms → ${newInterval}ms" }
            
            // Update the interval
            _interval = newInterval
            
            // Reschedule if running
            if (!stopped) {
                timer.cancel()
                schedule()
            }
        }
    }
    
    /**
     * Stop the heartbeat timer
     */
    suspend fun stop() {
        mutex.withLock {
            if (!stopped) {
                stopped = true
                timer.cancel()
            }
        }
    }
} 