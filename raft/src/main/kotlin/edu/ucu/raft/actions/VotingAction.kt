package edu.ucu.raft.actions

import edu.ucu.proto.VoteRequest
import edu.ucu.proto.VoteResponse
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.state.State
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging

class VotingAction(val state: State, val cluster: List<ClusterNode>) {

private val logger = KotlinLogging.logger {}
    private fun checkTerm(response: VoteResponse) {
        if (response.term > this.state.term) {

        }
    }

    // Initial waiting time and previous value tracking
    private var previousWaitingTime: Long = 75
    
    // Smoothing factors
    private val maxChangePercentage = 0.25 // Max 25% change per heartbeat
    private val minWaitingTime = 25L
    private val maxWaitingTime = 500L

    suspend fun askVotes(): Boolean {
        // Calculate waiting time based on maxLm with a fallback to 50ms
        // Add 25ms padding to account for network variability
        val targetWaitingTime = state.maxLm?.takeIf { it > 0 }?.plus(25) ?: 75
       
        // Get smoothed waiting time
        val waitingTime = calculateSmoothWaitingTime(targetWaitingTime)

        if (cluster.isEmpty()) return false
        val majority = Math.floorDiv(cluster.size, 2)
        val request = VoteRequest.newBuilder().setTerm(state.term).setCandidateId(state.id)
                .setLastLogIndex(state.log.lastIndex())
                .setLastLogTerm(state.log.lastTerm() ?: -1).build()

        val responses = cluster.map { node -> GlobalScope.async { node.requestVote(request) } }
            .map { withTimeoutOrNull(waitingTime) { it.await() } }
                .filterNotNull()

        responses.forEach { checkTerm(it) }
        val votes = responses.filter { it.voteGranted }.count()

        logger.info { "Votes: $votes, majority: $majority,  votes >= majority: ${votes >= majority}" }
        return votes >= majority
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