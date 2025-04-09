package edu.ucu.raft.grpc

import edu.ucu.proto.*
import edu.ucu.raft.adapters.RaftHandler
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking


class ClusterNodeService(val raft: RaftHandler) : ClusterNodeGrpc.ClusterNodeImplBase() {

    override fun requestVote(request: VoteRequest, responseObserver: StreamObserver<VoteResponse>) {
        val result = runBlocking { raft.requestVote(request) }
        responseObserver.onNext(result)
        responseObserver.onCompleted()
    }

    override fun appendEntries(request: AppendRequest, responseObserver: StreamObserver<AppendResponse>) {
        val result = runBlocking {
            raft.appendEntries(request)
        }
        responseObserver.onNext(result)
        responseObserver.onCompleted()
    }

    override fun appendNetworkHeartbeat(request: NetworkHeartbeatRequest, responseObserver: StreamObserver<NetworkHeartbeatResponse>) {
        val result = runBlocking {
            raft.appendNetworkHeartbeat(request)
        }
        responseObserver.onNext(result)
        responseObserver.onCompleted()
    }

}
