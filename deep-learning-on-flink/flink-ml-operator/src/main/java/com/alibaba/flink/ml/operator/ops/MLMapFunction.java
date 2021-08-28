/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.flink.ml.operator.ops;

import com.alibaba.flink.ml.cluster.ExecutionMode;
import com.alibaba.flink.ml.cluster.MLConfig;
import com.alibaba.flink.ml.cluster.node.MLContext;
import com.alibaba.flink.ml.cluster.rpc.CheckpointClient;
import com.alibaba.flink.ml.cluster.rpc.NodeServer;
import com.alibaba.flink.ml.data.DataExchange;
import com.alibaba.flink.ml.operator.util.PythonException;
import com.alibaba.flink.ml.operator.util.PythonFileUtil;
import com.alibaba.flink.ml.cluster.role.BaseRole;

import com.alibaba.flink.ml.operator.util.PythonRuntimeException;
import com.alibaba.flink.ml.proto.CheckpointResponse;
import com.alibaba.flink.ml.util.IpHostUtil;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * machine learning node has input and output,MLMapFunction is a util function to help create MLFlatMapOp class object.
 * @param <IN> machine learning node input class.
 * @param <OUT> machine learning node output class.
 */
public class MLMapFunction<IN, OUT> implements Closeable, Serializable {
	private static final Duration WAIT_INTERVAL = Duration.ofSeconds(30);

	private BaseRole role;
	private MLConfig config;
	private TypeInformation<IN> inTI;
	private TypeInformation<OUT> outTI;
	private MLContext mlContext;
	private FutureTask<Void> serverFuture;
	private ExecutionMode mode;
	private CheckpointClient checkpointClient;
	private transient DataExchange<IN, OUT> dataExchange;
	private volatile Collector<OUT> collector = null;
	private transient ListState<String> checkpointMessage;

	private static final Logger LOG = LoggerFactory.getLogger(MLMapFunction.class);

	public MLMapFunction(ExecutionMode mode, BaseRole role, MLConfig config, TypeInformation<IN> inTI,
			TypeInformation<OUT> outTI) {
		this.mode = mode;
		this.role = role;
		this.config = config;
		this.outTI = outTI;
		this.inTI = inTI;
	}

	private boolean isCoordinator(int index) {
		return role.name().equals("chief") || role.name().equals("worker") && index == 0;
	}

	/**
	 * create machine learning node and data exchange object.
	 * @param runtimeContext flink operator RuntimeContext.
	 * @throws Exception
	 */
	public void open(RuntimeContext runtimeContext) throws Exception {
		ResourcesUtils.parseGpuInfo(runtimeContext, config);

		int index = runtimeContext.getIndexOfThisSubtask();
		mlContext = new MLContext(mode, config, role.name(), index, config.getEnvPath(), null);

		boolean startCheckpointServer = config.getSaveFuncName() != null && isCoordinator(index);
		if (startCheckpointServer) {
			// choose a free port to start python checkpoint grpc server
			int port = IpHostUtil.getFreePort();
			mlContext.setCheckpointServerPort(port);
		}

		Iterator<String> iterator = checkpointMessage.get().iterator();
		if (iterator.hasNext()) {
			mlContext.setCheckpointMessage(iterator.next());
		}
		if (!isCoordinator(index)) {
			// maintain the checkpoint message state on chief/worker-0 node
			checkpointMessage.clear();
		}

		PythonFileUtil.preparePythonFilesForExec(runtimeContext, mlContext);

		dataExchange = new DataExchange<>(mlContext);

		try {
			serverFuture = new FutureTask<>(new NodeServer(mlContext, role.name()), null);
			Thread t = new Thread(serverFuture);
			t.setDaemon(true);
			t.setName("NodeServer_" + mlContext.getIdentity());
			t.start();
		} catch (Exception e) {
			LOG.error("Fail to start node service.", e);
			throw new IOException(e.getMessage());
		}
		System.out.println("start:" + mlContext.getRoleName() + " index:" + mlContext.getIndex());

		if (startCheckpointServer) {
			String host = IpHostUtil.getIpAddress();
			int port = mlContext.getCheckpointServerPort();
			checkpointClient = new CheckpointClient(host, port);
			try {
				if (checkpointClient.waitForReady(WAIT_INTERVAL)) {
					LOG.info("Connect to python checkpoint server {}:{}. on {}",
							host, port, mlContext.getIdentity());
				} else {
					throw new PythonException(
							String.format("Cannot connect to python checkpoint server %s:%d", host, port));
				}
			} catch (InterruptedException e) {
			    checkpointClient.close();
				LOG.error("Fail to connect to python checkpoint server", e);
				throw e;
			}
		}
	}

	/**
	 * stop machine learning node and resource.
	 */
	@Override
	public void close() {
		if (mlContext != null && mlContext.getOutputQueue() != null) {
			mlContext.getOutputQueue().markFinished();
		}
		if (checkpointClient != null) {
			checkpointClient.close();
		}

		// wait for tf thread finish
		try {
			//as in batch mode, we can't user timer to drain queue, so drain it here
			drainRead(collector, true);
			if (serverFuture != null && !serverFuture.isCancelled()) {
				serverFuture.get();
			}
		} catch (InterruptedException e) {
			LOG.error("Interrupted waiting for server join {}.", e.getMessage());
			serverFuture.cancel(true);
		} catch (ExecutionException e) {
			LOG.error(mlContext.getIdentity() + " node server failed");
			throw new RuntimeException(e);
		} finally {
			serverFuture = null;

			LOG.info("Records output: " + dataExchange.getReadRecords());

			if (mlContext != null) {
				try {
					mlContext.close();
				} catch (IOException e) {
					LOG.error("Fail to close mlContext.", e);
				}
				mlContext = null;
			}
		}
	}

	/**
	 * process input data and collect results.
	 * @param value input object.
	 * @param out output result.
	 * @throws Exception
	 */
	void flatMap(IN value, Collector<OUT> out) throws Exception {
		collector = out;

		//put the read & write in a loop to avoid dead lock between write queue and read queue.
		boolean writeSuccess = false;
		do {
			drainRead(out, false);

			writeSuccess = dataExchange.write(value);
			if (!writeSuccess) {
				Thread.yield();
			}
		} while (!writeSuccess);
	}

	public TypeInformation<OUT> getProducedType() {
		return outTI;
	}

	private void drainRead(Collector<OUT> out, boolean readUntilEOF) {
		while (true) {
			try {
				Object r = dataExchange.read(readUntilEOF);
				if (r != null) {
					out.collect((OUT) r);
				} else {
					break;
				}
			} catch (InterruptedIOException iioe) {
				LOG.info("{} Reading from is interrupted, canceling the server", mlContext.getIdentity());
				serverFuture.cancel(true);
			} catch (IOException e) {
				LOG.error("Fail to read data from python.", e);
			}
		}
	}

	void snapshotState(FunctionSnapshotContext snapshotContext) throws Exception {
		if (checkpointClient != null) {
			long checkpointId = snapshotContext.getCheckpointId();
			CheckpointResponse response = checkpointClient.snapshotState(checkpointId);
			if (response.getCode() == 0) {
			    checkpointMessage.clear();
			    checkpointMessage.add(response.getMessage());
			} else {
			    LOG.error("Exception in python for checkpoint-{}: {}", checkpointId, response.getMessage());
				throw new PythonRuntimeException(response.getMessage());
			}
		}
	}

	void initializeState(FunctionInitializationContext initializationContext) throws Exception {
		ListStateDescriptor<String> descriptor = new ListStateDescriptor<>(
				"checkpoint-message",
				TypeInformation.of(new TypeHint<String>() {}));
		// insure each user script process can get the same checkpoint message
		checkpointMessage = initializationContext.getOperatorStateStore().getUnionListState(descriptor);
		if (initializationContext.isRestored()) {
		    LOG.info("restore checkpoint message state");
		}
	}
}
