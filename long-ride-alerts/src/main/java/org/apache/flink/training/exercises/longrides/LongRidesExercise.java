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

package org.apache.flink.training.exercises.longrides;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.training.exercises.common.datatypes.TaxiRide;
import org.apache.flink.training.exercises.common.sources.TaxiRideGenerator;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * The "Long Ride Alerts" exercise.
 *
 * <p>The goal for this exercise is to emit the rideIds for taxi rides with a duration of more than
 * two hours. You should assume that TaxiRide events can be lost, but there are no duplicates.
 *
 * <p>You should eventually clear any state you create.
 */
public class LongRidesExercise {
    private final SourceFunction<TaxiRide> source;
    private final SinkFunction<Long> sink;

    /** Creates a job using the source and sink provided. */
    public LongRidesExercise(SourceFunction<TaxiRide> source, SinkFunction<Long> sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * Creates and executes the long rides pipeline.
     *
     * @return {JobExecutionResult}
     * @throws Exception which occurs during job execution.
     */
    public JobExecutionResult execute() throws Exception {

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // start the data generator
        DataStream<TaxiRide> rides = env.addSource(source);

        // the WatermarkStrategy specifies how to extract timestamps and generate watermarks
        WatermarkStrategy<TaxiRide> watermarkStrategy =
                WatermarkStrategy.<TaxiRide>forBoundedOutOfOrderness(Duration.ofSeconds(60))
                        .withTimestampAssigner(
                                (ride, streamRecordTimestamp) -> ride.getEventTimeMillis());

        // create the pipeline
        rides.assignTimestampsAndWatermarks(watermarkStrategy)
                .keyBy(ride -> ride.rideId)
                .process(new AlertFunction())
                .addSink(sink);

        // execute the pipeline and return the result
        return env.execute("Long Taxi Rides");
    }

    /**
     * Main method.
     *
     * @throws Exception which occurs during job execution.
     */
    public static void main(String[] args) throws Exception {
        LongRidesExercise job =
                new LongRidesExercise(new TaxiRideGenerator(), new PrintSinkFunction<>());

        job.execute();
    }

    @VisibleForTesting
    public static class AlertFunction extends KeyedProcessFunction<Long, TaxiRide, Long> {
        private transient ValueState<TaxiRide> rideState;

        @Override
        public void open(Configuration config) {
            ValueStateDescriptor<TaxiRide> rideStateDescriptor =
                    new ValueStateDescriptor<>("ride event", TaxiRide.class);
            rideState = getRuntimeContext().getState(rideStateDescriptor);
        }

        @Override
        public void processElement(TaxiRide ride, Context context, Collector<Long> out)
                throws Exception {
            TaxiRide savedRide = rideState.value();

            if (savedRide == null) {
                // we've never seen a START or END event for this rideId before
                rideState.update(ride);
                if (ride.isStart) {
                    // register a timer for 2 hours from now
                    context.timerService()
                            .registerEventTimeTimer(ride.getEventTimeMillis() + 2 * 60 * 60 * 1000);
                }
            } else {
                // we've seen the other event for this rideId
                if (ride.isStart != savedRide.isStart) {
                    // the events are START and END, so the ride is complete
                    long start =
                            ride.isStart
                                    ? ride.getEventTimeMillis()
                                    : savedRide.getEventTimeMillis();
                    long end =
                            ride.isStart
                                    ? savedRide.getEventTimeMillis()
                                    : ride.getEventTimeMillis();

                    if (end - start > 2 * 60 * 60 * 1000) {
                        out.collect(ride.rideId);
                    }
                }
                // clear the state and any timers
                if (savedRide.isStart) {
                    context.timerService()
                            .deleteEventTimeTimer(
                                    savedRide.getEventTimeMillis() + 2 * 60 * 60 * 1000);
                }
                rideState.clear();
            }
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext context, Collector<Long> out)
                throws Exception {
            // if a timer fires, it means we have a START event, but never saw an END
            TaxiRide savedRide = rideState.value();
            if (savedRide != null && savedRide.isStart) {
                out.collect(savedRide.rideId);
                rideState.clear();
            }
        }
    }
}
