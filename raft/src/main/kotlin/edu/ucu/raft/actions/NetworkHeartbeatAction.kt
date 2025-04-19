package edu.ucu.raft.actions

import edu.ucu.proto.NetworkHeartbeatRequest
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.clock.TermClock
import edu.ucu.raft.state.NodeState
import edu.ucu.raft.state.State
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import mu.KotlinLogging

class NetworkHeartbeatAction(val state: State, val cluster: List<ClusterNode>, val termClock: TermClock) {

    private val logger = KotlinLogging.logger {}

    private val log = state.log

    suspend fun send() {
        if (state.current != NodeState.LEADER) {
            cluster.map {

                val requestBuilder = NetworkHeartbeatRequest.newBuilder()
                    .setFrom(state.id.toString())
                    .setTimeSent(System.currentTimeMillis())

                state.maxLm?.let {
                    requestBuilder.setMaxLm(it)
                }

                state.Tdlcc?.let {
                    requestBuilder.setLatencyFromLeader(it)
                }

                val request = requestBuilder.build()

                GlobalScope.async {
                    val response = it.appendNetworkHeartbeat(request)
                    if (response == null) null else it to response
                }
            }.map { it.await() }
                .filterNotNull()
                .forEach { (node, response) ->
                    when {
                        response.success -> {
                            state.delays[response.from] = response.timeReceived - response.timeSent
                            state.maxLm = culculateMaxLm()
                            state.thetaM[state.id.toString()] = state.maxLm

                            if (state.delays.size == cluster.size
                                && state.leaderToNodeDelays.size == cluster.size
                                && state.thetaM.size == cluster.size
                            ) {
                                val bestCandidateId = state.thetaM.minByOrNull { it.value ?: 0L }?.key
                                val T_bccc = state.delays[bestCandidateId] ?: 0L
                                val Tdlbc = state.leaderToNodeDelays[bestCandidateId]
                                val maxTheta_M = state.thetaM.values.filterNotNull().maxOrNull() ?: 0L

                                val a = 10L
                                val b = 30L
                                val T_max = 1600L
                                val delta_me = 0.63
                                val delta_c = 150
                                var newInterval : Long
                                if (state.Tdlcc != null && maxTheta_M != state.maxLm && 4 * state.maxLm!!  < T_max * delta_me) {
                                     newInterval  =  (T_max * delta_me).toLong()
                                } else {
                                    newInterval = if (bestCandidateId == state.id.toString()) {
                                        val base = maxOf(
                                            (state.maxLm!!.toDouble() / maxTheta_M.toDouble()) * T_max,
                                            T_max * delta_me
                                        )
                                        (base + (a..b).random()).toLong()
                                    } else {
                                        val base = maxOf(
                                            (state.maxLm!!.toDouble() / maxTheta_M.toDouble()) * T_max,
                                            T_max * (1 - delta_me)
                                        )
                                        val minExtra = minOf(
                                            (Tdlbc!! + T_bccc - state.Tdlcc!!).toDouble(),
                                            delta_c.toDouble()
                                        )
                                        (base + (a..b).random() + minExtra).toLong()
                                    }
                                }

                                logger.info {
                                    """
                                        📡 New interval: $newInterval
                                        🧠 Node ID: ${state.id}
                                        ✅ Is best candidate: ${bestCandidateId == state.id.toString()}

                                        📊 maxLm: ${state.maxLm!!.toDouble()}
                                        📊 maxTheta_M: ${maxTheta_M.toDouble()}
                                        📊 maxLm / maxTheta_M: ${state.maxLm!!.toDouble() / maxTheta_M.toDouble()}
                                        📊 (maxLm / maxTheta_M) * T_max: ${(state.maxLm!!.toDouble() / maxTheta_M.toDouble()) * T_max}

                                        🛠 T_max * delta_me: ${T_max * delta_me}
                                        ⏱ Tdlcc: ${state.Tdlcc}
                                        ⛳️ leaderToNodeDelays: ${state.leaderToNodeDelays}
                                        📈 thetaM.values: ${state.thetaM.values}

                                        🧪 4 * maxLm < T_max * delta_me: ${4 * state.maxLm!!} < ${T_max * delta_me} → ${4 * state.maxLm!! < T_max * delta_me}
                                        """.trimIndent()
                                }
                                termClock.updateIntervalBasedOnDelays(newInterval)
                            }
                        }

                        !response.success -> {
                            logger.info { "Network Heartbeat response: ${response.success}" }
                        }
                    }
                }

        }
    }

    private fun culculateMaxLm(): Long {
        val sortedLatencies = state.delays.values.filterNotNull().sorted()
        val majorityIndex = (cluster.size / 2.0).toInt()
        return sortedLatencies.take(majorityIndex).maxOrNull() ?: 0L
    }

}
