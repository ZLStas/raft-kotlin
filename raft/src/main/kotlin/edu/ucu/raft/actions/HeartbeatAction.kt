package edu.ucu.raft.actions

import edu.ucu.proto.AppendRequest
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.state.NodeState
import edu.ucu.raft.state.State
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.lang.RuntimeException
import kotlin.math.max
import kotlin.math.min

class HeartbeatAction(val state: State, val cluster: List<ClusterNode>) {

    private val logger = KotlinLogging.logger {}

    private val log = state.log

    // Initial waiting time and previous value tracking
    private var previousWaitingTime: Long = 75
    
    // Smoothing factors
    private val maxChangePercentage = 0.04 // Max 4% change per heartbeat
    private val minWaitingTime = 25L
    private val maxWaitingTime = 500L

    suspend fun send() {
        if (state.current == NodeState.LEADER) {
            // Calculate target waiting time based on maxLm
            val targetWaitingTime = state.maxLm?.takeIf { it > 0 }?.plus(25) ?: 75
            
            // Get smoothed waiting time
            val waitingTime = calculateSmoothWaitingTime(targetWaitingTime)
            logger.info { "Asking for votes by ${state.id}, waitingTime: ${waitingTime}" }

            cluster.map {
                val prevIndex = it.nextIndex - 1
                val prevTerm = if (prevIndex != -1) log[prevIndex]?.term ?: throw RuntimeException("WAT") else -1

                val entries = log.starting(prevIndex + 1)
                val request = AppendRequest.newBuilder()
                    .setTerm(state.term).setLeaderId(state.id).setLeaderCommit(state.log.commitIndex)
                    .setPrevLogIndex(prevIndex).setPrevLogTerm(prevTerm)
                    .addAllEntries(entries)
                    .setTimeSent(System.currentTimeMillis())
                    .build()


                GlobalScope.async {
                    val response = it.appendEntries(request)
                    if (response == null) null else it to response
                }
            }.map { withTimeoutOrNull(waitingTime) { it.await() } }
                    .filterNotNull()
                    .forEach { (node, response) ->
                        when {
                            response.success -> {
                                    // The last entry sent in this AppendEntries was prevIndex + entries.size
                                val prevIndex = node.nextIndex - 1
                                val entriesSent = log.starting(prevIndex + 1)
                                val lastReplicated = prevIndex + entriesSent.size
                                node.matchIndex = lastReplicated
                                node.nextIndex = lastReplicated + 1
                            }

                            !response.success -> {
                                logger.info { "Heartbeat response: ${response.success}-${response.term}" }
                                node.decreaseIndex()
                            }
                        }
                    }


        }
    }

        /**
     * Calculate a smoothed waiting time based on the target value,
     * limiting the rate of change to avoid abrupt transitions.
     * 
     * @param targetWaitingTime The ideal waiting time based on current conditions
     * @return A smoothly adjusted waiting time
     */
    private fun calculateSmoothWaitingTime(targetWaitingTime: Long): Long {
        // Calculate smooth change (limited to maxChangePercentage)
        val diff = targetWaitingTime - previousWaitingTime
        val maxChange = (previousWaitingTime * maxChangePercentage).toLong()
        val smoothedChange = when {
            diff > maxChange -> maxChange
            diff < -maxChange -> -maxChange
            else -> diff
        }
        
        // Apply smoothed change with bounds
        val newWaitingTime = (previousWaitingTime + smoothedChange)
            .coerceIn(minWaitingTime, maxWaitingTime)
        
        // Store for next iteration
        previousWaitingTime = newWaitingTime
        
        logger.info { "Heartbeat waitingTime: $newWaitingTime (target: $targetWaitingTime, change: $smoothedChange)" }
        
        return newWaitingTime
    }

}
