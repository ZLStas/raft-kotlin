package edu.ucu.raft.actions

import edu.ucu.proto.NetworkHeartbeatRequest
import edu.ucu.raft.adapters.ClusterNode
import edu.ucu.raft.clock.TermClock
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

class NetworkHeartbeatAction(val state: State, val cluster: List<ClusterNode>, val termClock: TermClock) {

    private val logger = KotlinLogging.logger {}

    private val log = state.log

    private var interval  = 0L

    suspend fun send() {
        logger.info { "Sending network heartbeat by  ${state.id}" }
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
                        }

                        !response.success -> {
                            logger.info { "Network Heartbeat response: ${response.success}" }
                        }
                    }
                }

            logger.info { "!! State check -> current: ${state.current}, isLeader: ${state.current == NodeState.LEADER}" }
            if (state.delays.size == cluster.size
                && state.leaderToNodeDelays.size == cluster.size
                && state.thetaM.size == cluster.size
            ) {
                val bestCandidateId = state.thetaM.minByOrNull { it.value ?: 0L }?.key
                val T_bccc = state.delays[bestCandidateId] ?: 0L
                val Tdlbc = state.leaderToNodeDelays[bestCandidateId]
                val maxTheta_M = state.thetaM.values.filterNotNull().maxOrNull() ?: 0L

                val a = 20L
                val b = 40L
                val T_max = 1700L
                val delta_me = 0.45
                val delta_c = 300

                val avgDelay = state.maxLm ?: 0L

                // Задаємо допустимий інтервал затримок, в якому очікується робота кластера
                val minDelay = 20L
                val maxDelay = 700L

                val clamped = avgDelay.coerceIn(minDelay.toLong(), maxDelay).toDouble()
                val delayFactor = (clamped - minDelay) / (maxDelay - minDelay)  // ∈ [0.0, 1.0]

                val candidateScale = 1 + delta_me * delayFactor       // ∈ [1, 1.4]
                val otherScale = 1 + (1-delta_me) * delayFactor           // ∈ [1, 1.6]

                val adaptiveBase = 700 + avgDelay.toDouble().coerceIn(minDelay.toDouble(), maxDelay.toDouble())

                var newInterval = if (bestCandidateId == state.id.toString()) {
                    val base = adaptiveBase * candidateScale
                    (base + (a..b).random()).toLong()
                } else {
                    val base = adaptiveBase * otherScale
                    val minExtra = minOf(
                        (Tdlbc!! + T_bccc - state.Tdlcc!!).toDouble(),
                        delta_c.toDouble()
                    )
                    (base + (a..b).random() + minExtra).toLong()
                }
                interval = newInterval
                termClock.updateIntervalBasedOnDelays(newInterval)
            }
        }

        logIntervalDataIntoFile(interval)
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
