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

    suspend fun askVotes(): Boolean {
        logger.info { "Asking for votes by ${state.id}" }
        if (cluster.isEmpty()) return false
        val majority = Math.floorDiv(cluster.size, 2)
        val request = VoteRequest.newBuilder().setTerm(state.term).setCandidateId(state.id)
                .setLastLogIndex(state.log.lastIndex())
                .setLastLogTerm(state.log.lastTerm() ?: -1).build()

        val responses = cluster.map { node -> GlobalScope.async { node.requestVote(request) } }
                .map { withTimeoutOrNull(200) { it.await() } }
                .filterNotNull()

        responses.forEach { checkTerm(it) }
        val votes = responses.filter { it.voteGranted }.count()

        logger.info { "Votes: $votes, majority: $majority,  votes >= majority: ${votes >= majority}" }
        return votes >= majority
    }
}