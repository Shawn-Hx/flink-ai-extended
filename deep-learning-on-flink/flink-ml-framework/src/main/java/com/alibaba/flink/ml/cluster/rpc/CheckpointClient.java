package com.alibaba.flink.ml.cluster.rpc;

import com.alibaba.flink.ml.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckpointClient extends AbstractGrpcClient {
    private static final Logger LOG = LoggerFactory.getLogger(CheckpointClient.class);

    private CheckpointServiceGrpc.CheckpointServiceBlockingStub blockingStub;

    public CheckpointClient(String host, int port, ManagedChannel channel) {
        super(host, port, channel);
        blockingStub = CheckpointServiceGrpc.newBlockingStub(grpcChannel);
    }

    public CheckpointClient(String host, int port) {
        this(host, port, ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build());
    }

    public String getState() {
        return grpcChannel.getState(true).name();
    }

    public CheckpointResponse snapshotState(long checkpointId) {
        CheckpointRequest request = CheckpointRequest.newBuilder().setCheckpointId(checkpointId).build();
        return blockingStub.snapshotState(request);
    }

    @Override
    String serverName() {
        return "PythonCheckpointServer";
    }
}
