package edu.ucu.raft.actions

import edu.ucu.proto.NetworkHeartbeatRequest
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.clock.TermClock
import edu.ucu.raft.clock.HeartbeatClock
import edu.ucu.raft.state.NodeState
import edu.ucu.raft.state.State
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import mu.KotlinLogging

class NetworkHeartbeatAction(val state: State, val cluster: List<ClusterNode>, val termClock: TermClock, val heartbeatClock: HeartbeatClock) {

    private val logger = KotlinLogging.logger {}

    private val log = state.log

    private var interval = 0L

    suspend fun send() {
        logger.info { "Sending network heartbeat by  ${state.id}" }
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
                    }

                    !response.success -> {
                        logger.info { "Network Heartbeat response: ${response.success}" }
                    }
                }
            }

        logger.info { "!! State check -> current: ${state.current}, isLeader: ${state.current == NodeState.LEADER}" }
        logger.info { "Conditions check:" }
        logger.info { "- delays size: ${state.delays.size} >= ${cluster.size} -> ${state.delays.size >= cluster.size}" }
        logger.info { "- leaderToNodeDelays size: ${state.leaderToNodeDelays.size} >= ${cluster.size} -> ${state.leaderToNodeDelays.size >= cluster.size}" }
        logger.info { "- thetaM size: ${state.thetaM.size} >= ${cluster.size} -> ${state.thetaM.size >= cluster.size}" }
        logger.info { "All conditions met: ${state.delays.size >= cluster.size && state.leaderToNodeDelays.size >= cluster.size && state.thetaM.size >= cluster.size}" }
        if (state.delays.size >= cluster.size
            && state.leaderToNodeDelays.size >= cluster.size
            && state.thetaM.size >= cluster.size
        ) {
            val bestCandidateId = state.thetaM.minByOrNull { it.value ?: 0L }?.key
            val T_bccc = state.delays[bestCandidateId] ?: 0L
            val Tdlbc = state.leaderToNodeDelays[bestCandidateId] ?: 0L
            val maxTheta_M = state.thetaM.values.filterNotNull().maxOrNull() ?: 0L

            if (state.current != NodeState.LEADER) {
                val a = 10L
                val b = 20L
                val T_max = 2500L
                val delta_me = 0.35
                val delta_c = 500

                val avgDelay = state.leaderToNodeDelays[state.id.toString()]  ?: 0L

                // Adjusted delay bounds to better handle network degradation
                val minDelay = 20L
                val maxDelay = 500L

                val clamped = avgDelay.coerceIn(minDelay.toLong(), maxDelay).toDouble()
                val delayFactor = (clamped - minDelay) / (maxDelay - minDelay)  // ∈ [0.0, 1.0]

                val candidateScale = 1 + (0.25 * delayFactor)       // ∈ [1, 1.35]
                val otherScale = 1 + (0.35 * delayFactor )          // ∈ [1, 1.65]

                logger.info { "📊 Scale factors - otherScale: $otherScale, delayFactor: $delayFactor, candidateScale: $candidateScale" }
                logger.info { "⏱️ Delays - avgDelay: $avgDelay, minDelay: $minDelay, maxDelay: $maxDelay" }

                val adaptiveBase = 400 + avgDelay.toDouble().coerceIn(minDelay.toDouble(), maxDelay.toDouble())

                var newInterval = if (bestCandidateId == state.id.toString()) {
                    val base = adaptiveBase * candidateScale
                    logger.info { "Calculating interval for candidate node ${state.id}:" }
                    logger.info { "- adaptiveBase: $adaptiveBase" }
                    logger.info { "- candidateScale: $candidateScale" }
                    logger.info { "- base (adaptiveBase * candidateScale): $base" }
                    val randomExtra = (a..b).random()
                    logger.info { "- random extra (${a}..${b}): $randomExtra" }
                    val result = (base + randomExtra).toLong()
                    logger.info { "Final interval: $result" }
                    result
                } else {
                    val base = adaptiveBase * otherScale
//                    val minExtra = minOf(
//                        (Tdlbc!! + T_bccc - (state.Tdlcc ?: 0L)).toDouble(),
//                        delta_c.toDouble()
//                    )
                    (base + (a..b).random()).toLong()
                }
                interval = newInterval
                logIntervalDataIntoFile(interval)
                termClock.updateIntervalBasedOnDelays(newInterval)
            } else { 
                // Log the updated interval
                val newHeartbeatInterval = (state.maxLm ?: 400).plus(400)
                logger.info { "🔄 Leader adjusting heartbeat interval based on network conditions state.maxLm: ${state.maxLm}, newHeartbeatInterval: $newHeartbeatInterval" }
                logIntervalDataIntoFile(newHeartbeatInterval)
            }
        }
    }

    private fun logIntervalDataIntoFile(newInterval: Long) {
        val logDir = Paths.get("/logs")
        Files.createDirectories(logDir)
        val nodeLogFile = logDir.resolve("node_${state.id}.log").toFile()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))

        val logEntry = "[$now] Interval update for Node: ${state.id} → $newInterval"

        println("📝 Writing log to $nodeLogFile")
        nodeLogFile.appendText(logEntry + "\n")
    }

    private fun culculateMaxLm(): Long {
        val sortedLatencies = state.delays.values.filterNotNull().sorted()
        val majorityIndex = (cluster.size / 2.0).toInt()
        return sortedLatencies.take(majorityIndex).maxOrNull() ?: 0L
    }

}
