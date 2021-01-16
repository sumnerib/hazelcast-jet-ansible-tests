/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.tests.kinesis;

import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.impl.pipeline.SinkImpl;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.IMap;

import java.util.PriorityQueue;

import static com.hazelcast.jet.core.ProcessorMetaSupplier.forceTotalParallelismOne;
import static com.hazelcast.jet.impl.pipeline.SinkImpl.Type.TOTAL_PARALLELISM_ONE;

public class KinesisVerificationP extends AbstractProcessor {

    public static final String CONSUMED_MESSAGES_MAP_NAME = "KinesisTest_latestCounters";

    private static final int QUEUE_SIZE_LIMIT = 5_000;
    private static final int PRINT_LOG_ITEMS = 20_000;

    private final String clusterName;

    private long counter;
    private final PriorityQueue<Long> queue = new PriorityQueue<>();
    private ILogger logger;
    private IMap<String, Long> map;

    public KinesisVerificationP(String clusterName) {
        this.clusterName = clusterName;
    }

    @Override
    protected void init(Context context) {
        logger = context.logger();
        map = context.jetInstance().getMap(CONSUMED_MESSAGES_MAP_NAME);
    }

    @Override
    public boolean tryProcess(int ordinal, Object item) {
        long value = Long.parseLong(item.toString());
        queue.add(value);
        // try to verify head of verification queue
        for (Long peeked; (peeked = queue.peek()) != null; ) {
            if (peeked > counter) {
                // the item might arrive later
                break;
            } else if (peeked == counter) {
                if (counter % PRINT_LOG_ITEMS == 0) {
                    logger.info(String.format("[%s] Processed correctly item %d", clusterName, counter));
                }
                // correct head of queue
                queue.remove();
                counter++;
                map.setAsync(clusterName, counter);
            } else {
                // duplicate key, remove
                queue.remove();
                logger.info(String.format("[%s] duplicate key, ignored. peeked: %d, counter: %d, item: %d",
                        clusterName, peeked, counter, value));
            }
        }
        if (queue.size() >= QUEUE_SIZE_LIMIT) {
            throw new AssertionError(String.format("[%s] Queue size exceeded while waiting for the next "
                            + "item. Limit=%d, expected next=%d, "
                            + "next in queue: %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, ...",
                    clusterName, QUEUE_SIZE_LIMIT, counter, queue.poll(), queue.poll(), queue.poll(), queue.poll(),
                    queue.poll(), queue.poll(), queue.poll(), queue.poll(), queue.poll(), queue.poll()));
        }
        return true;
    }

    @Override
    public boolean saveToSnapshot() {
        logger.info(String.format("[%s] saveToSnapshot counter: %d, size: %d, peek: %d",
                clusterName, counter, queue.size(), queue.peek()));
        return tryEmitToSnapshot(clusterName, Tuple2.tuple2(counter, queue));
    }

    @Override
    protected void restoreFromSnapshot(Object ignored, Object value) {
        Tuple2<Long, PriorityQueue<Long>> tuple = (Tuple2) value;
        counter = tuple.f0();
        queue.addAll(tuple.f1());

        logger.info(String.format("[%s] restoreFromSnapshot counter: %d, size: %d, peek: %d",
                clusterName, counter, queue.size(), queue.peek()));
    }

    static Sink<String> sink(String cluster) {
        String sinkName = "KinesisVerificationSink";
        return new SinkImpl<>(sinkName,
                forceTotalParallelismOne(ProcessorSupplier.of(() -> new KinesisVerificationP(cluster)), sinkName),
                TOTAL_PARALLELISM_ONE);
    }

}
