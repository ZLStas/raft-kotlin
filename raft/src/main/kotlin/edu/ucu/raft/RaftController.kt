package edu.ucu.raft

import edu.ucu.raft.log.Command
import edu.ucu.proto.AppendRequest
import edu.ucu.proto.AppendResponse
import edu.ucu.proto.VoteRequest
import edu.ucu.proto.VoteResponse
import edu.ucu.raft.actions.CommitAction
import edu.ucu.raft.actions.HeartbeatAction
import edu.ucu.raft.actions.VotingAction
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.adapters.RaftHandler
import edu.ucu.raft.clock.TermClock
import edu.ucu.raft.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlin.math.log


class RaftController(val config: RaftConfiguration,
                     val cluster: MutableList<ClusterNode> = mutableListOf()) : RaftHandler {

    val state = State(id = config.id)

    private val clock = TermClock(config.timerInterval)
    private val logger = KotlinLogging.logger {}
    private val heartbeat = HeartbeatAction(state, cluster)
    private val voting = VotingAction(state, cluster)
    private val commit = CommitAction(state, cluster)
    private val clockSubscription = clock.channel.openSubscription()
    //    private val stateLock: Mutex = Mutex(false)
    private lateinit var termSubscriber: Job
    private lateinit var heartbeatTimer: Timer
    private lateinit var stateSubscriber: Job


    private val stateLogger = fixedRateTimer("logger", initialDelay = 1000, period = 1000) {
        logger.info { "⛳️ $state" }
        if (state.current == NodeState.LEADER) {
            cluster.forEach {
                logger.info { "⚙️ Node: ${it.nodeId} - Next: ${it.nextIndex} Match: ${it.matchIndex}" }
            }
        }
    }

    private fun prepareStateSubscriber() {
        stateSubscriber = GlobalScope.launch {
            for ((prev, current) in state.updates.openSubscription()) {
                when {
                    current == NodeState.LEADER -> {
                        cluster.forEach { it.reinitializeIndex(state.log.lastIndex() + 1) }
                        clock.freeze()
                    }
                    prev == NodeState.LEADER && current != NodeState.LEADER -> {
                        clock.start()
                    }
                }
            }
        }
    }

    private fun prepareHeartbeatTimer() {
        heartbeatTimer = fixedRateTimer("heartbeat", initialDelay = config.heartbeatInterval, period = config.heartbeatInterval) {
            runBlocking {
                kotlin.runCatching {
                    //                    stateLock.withLock {
                    heartbeat.send()
                    commit.perform()
//                    }
                }.onFailure {
                    logger.error { "Failure: $it" }
                }
            }
        }
    }

    private fun prepareTermIncrementSubscriber() {
        termSubscriber = GlobalScope.launch {
            for (term in clockSubscription) {
                logger.info { "Starting term increment" }
                state.nextTerm(term)
                val result = voting.askVotes()
                if (result) {
                    state.promoteToLeader()
                } else {
                    logger.info { "---> 🤬 Can't promote to leader <---" }
                }
            }

        }
    }

    private suspend fun actualizeTerm(receivedTerm: Long) {
        if (clock.term < receivedTerm) {
            clock.update(receivedTerm)
        }
    }

    fun start() {
        runBlocking {
            clock.start()
            prepareTermIncrementSubscriber()
            prepareHeartbeatTimer()
            prepareStateSubscriber()
        }
    }

    fun stop() {
        runBlocking {
            clock.freeze()
            termSubscriber.cancelAndJoin()
            heartbeatTimer.cancel()
            stateSubscriber.cancelAndJoin()
        }
    }

    override suspend fun requestVote(request: VoteRequest): VoteResponse {
        actualizeTerm(request.term)
        val vote = state.requestVote(request)

        if (vote.voteGranted) {
            clock.reset()
        }
        logger.info { "🗽Vote request: ${request.candidateId} - term  ${request.term} - result: ${vote.voteGranted}" }
        return vote

    }

    override suspend fun appendEntries(request: AppendRequest): AppendResponse {
        actualizeTerm(request.term)
        val result = state.appendEntries(request)

        // Reset election timer if the leader's term is at least as large as ours
        if (request.term >= state.term) {
            clock.reset()
        }
        return result
    }


    suspend fun applyCommand(command: Command): Boolean {
        val index = state.applyCommand(command)
        val myTerm = state.term
        var waited = 0
        val maxWait = 5500 // 10 seconds, for example

        while (true) {
            if (index <= state.log.commitIndex) {
                // Check that the entry at this index is ours (term matches)
                val entry = state.log[index]
                if (entry != null && entry.term == myTerm) {
                    return true
                } else {
                    // Our entry was overwritten (e.g., lost due to leader change)
                    return false
                }
            }
            delay(50)
            waited += 50
            if (waited > maxWait) return false // Timeout
        }
    }


}