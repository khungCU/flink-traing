# Bridging Legacy `SourceFunction` to Modern Flink `Source` API

This document addresses how you can adapt a legacy Flink `SourceFunction` to work with the modern `org.apache.flink.api.connector.source.Source` API, especially relevant for future Flink versions (e.g., a hypothetical Flink 2.x) where the `StreamExecutionEnvironment.addSource()` method is expected to be removed.

---

### 1. Will `SourceFunction` still be directly usable in Flink 2.x?

**No, not directly.**

The `StreamExecutionEnvironment.addSource()` method, which is the only way to integrate a `SourceFunction` into a Flink pipeline, is already marked as `@Deprecated` in recent Flink 1.x releases. In a major version upgrade like a hypothetical Flink 2.x, deprecated APIs are typically removed entirely.

Therefore, you should plan for a future where `addSource()` does not exist, and `SourceFunction` can no longer be directly used in your Flink pipelines.

---

### 2. Can you wrap `SourceFunction` to bridge it to a `Source` class?

**Yes, absolutely.**

While Flink's core framework is unlikely to provide an official, generic wrapper class (to encourage native `Source` implementations), you can write your own custom bridge. This is an effective migration strategy if you have complex or numerous `SourceFunction` implementations that cannot be immediately rewritten to the full `Source` API.

This custom wrapper will implement the `org.apache.flink.api.connector.source.Source` interface but will internally delegate its data generation logic to your existing `SourceFunction`.

**Important Considerations for a Wrapper:**

*   **Non-Parallel Nature:** A `SourceFunction` is inherently monolithic; it doesn't have a concept of "splits" or parallel subtasks in the same way a `Source` does. Therefore, your wrapper will essentially treat the entire `SourceFunction` as a single, non-parallel unit of work (i.e., one "split" assigned to one "reader").
*   **Thread Management:** The `SourceFunction.run()` method typically runs in a loop. Your `SourceReader` implementation within the wrapper will need to manage this `SourceFunction` in a separate thread and transfer data from it to the `SourceReader`'s output using a thread-safe mechanism (e.g., a `BlockingQueue`).
*   **Complexity:** Building this wrapper involves implementing several interfaces (`Source`, `SplitEnumerator`, `SourceReader`) and managing thread communication. While feasible, it is more complex than simply calling `addSource()`.

---

### Conceptual Example of a `SourceFunction` Wrapper

Here is a high-level Java skeleton of how you could implement such a wrapper. This demonstrates the necessary components and the internal bridging logic.

```java
import org.apache.flink.api.connector.source.*;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark; // For SourceContext

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A conceptual wrapper to bridge a legacy, non-parallel SourceFunction
 * to the modern Flink Source API.
 *
 * NOTE: This should be considered a temporary migration aid. A full rewrite
 * to a native Source is the best long-term solution. This wrapper forces
 * parallelism to 1 and assumes the SourceFunction runs indefinitely.
 * Checkpointing and failure recovery would require more advanced state management
 * not fully covered in this conceptual example.
 *
 * @param <T> The type of elements produced by the source.
 */
public class LegacySourceWrapper<T> implements Source<T, LegacySourceWrapper.LegacySplit, Void> {

    private final SourceFunction<T> legacySource;

    public LegacySourceWrapper(SourceFunction<T> legacySource) {
        this.legacySource = legacySource;
    }

    // --- Implementing the Flink Source Interface ---

    @Override
    public Boundedness getBoundedness() {
        // Legacy SourceFunctions are typically for unbounded streaming data.
        return Boundedness.CONTINUOUS_UNBOUNDED;
    }

    @Override
    public SourceReader<T, LegacySplit> createReader(SourceReaderContext readerContext) {
        return new LegacyReader<>(legacySource);
    }

    @Override
    public SplitEnumerator<LegacySplit, Void> createEnumerator(SplitEnumeratorContext<LegacySplit> enumContext) {
        // Since SourceFunction is monolithic, we'll only ever have one split.
        // The enumerator's job is simple: create and assign this single split.
        return new SingleSplitEnumerator(enumContext);
    }

    // For simplicity, we assume no enumerator state needs to be checkpointed.
    @Override
    public SimpleVersionedSerializer<Void> getEnumeratorCheckpointSerializer() {
        return null;
    }

    // A serializer for our custom LegacySplit class.
    @Override
    public SimpleVersionedSerializer<LegacySplit> getSplitSerializer() {
        return new LegacySplit.Serializer();
    }

    // --- Helper Classes for the Wrapper Implementation ---

    /**
     * A placeholder "split" representing the entire legacy SourceFunction's work.
     * Since SourceFunction is monolithic, there is only one logical split.
     */
    public static class LegacySplit implements SourceSplit {
        private static final long serialVersionUID = 1L; // For serialization

        @Override
        public String splitId() {
            return "legacy-source-split";
        }

        /** Simple serializer for the LegacySplit. */
        public static class Serializer implements SimpleVersionedSerializer<LegacySplit> {
            @Override
            public int getVersion() { return 0; }
            @Override
            public byte[] serialize(LegacySplit obj) { return new byte[0]; } // Empty as no state
            @Override
            public LegacySplit deserialize(int version, byte[] serialized) { return new LegacySplit(); }
        }
    }

    /**
     * The `SourceReader` is where the legacy `SourceFunction` is executed.
     * It runs the `SourceFunction` in a background thread and buffers its output.
     */
    private static class LegacyReader<T> implements SourceReader<T, LegacySplit> {
        private final SourceFunction<T> sourceFunction;
        private final BlockingQueue<T> queue = new ArrayBlockingQueue<>(1024); // Buffer for elements
        private final AtomicBoolean isRunning = new AtomicBoolean(true);
        private ExecutorService executorService;

        public LegacyReader(SourceFunction<T> sourceFunction) {
            this.sourceFunction = sourceFunction;
        }

        @Override
        public void start() {
            executorService = Executors.newSingleThreadExecutor();
            executorService.submit(() -> {
                try {
                    // Execute the legacy SourceFunction's run method
                    sourceFunction.run(new QueueSourceContext<>(queue, isRunning));
                } catch (Exception e) {
                    // Log error and potentially fail the task
                    System.err.println("Error in legacy source function: " + e.getMessage());
                    throw new RuntimeException("Legacy source function failed", e);
                } finally {
                    isRunning.set(false); // Indicate that the source has finished
                }
            });
        }

        @Override
        public InputStatus pollNext(ReaderOutput<T> output) throws Exception {
            // If the source function has stopped and the queue is empty, we are done.
            if (!isRunning.get() && queue.isEmpty()) {
                return InputStatus.END_OF_INPUT;
            }

            // Attempt to get an element from the queue.
            T element = queue.poll();
            if (element != null) {
                output.collect(element);
                return InputStatus.MORE_AVAILABLE;
            } else if (isRunning.get()) {
                // No elements available right now, but the source is still running.
                return InputStatus.NOTHING_AVAILABLE;
            } else {
                // Source has stopped and queue is empty.
                return InputStatus.END_OF_INPUT;
            }
        }

        @Override
        public void addSplits(java.util.List<LegacySplit> splits) {
            // For a single-split source, this won't typically be called dynamically.
        }

        @Override
        public CompletableFuture<Void> isAvailable() {
            // Indicate availability if queue has elements or source is still running
            if (!queue.isEmpty() || isRunning.get()) {
                return CompletableFuture.completedFuture(null);
            }
            return new CompletableFuture<>(); // Block until more data is available or source stops
        }

        @Override
        public java.util.List<LegacySplit> snapshotState(long checkpointId) {
            // For a simple wrapper, checkpointing the SourceFunction's state directly
            // is complex and not covered here. This would typically involve using
            // SourceFunction.snapshotState() if it supports it, or rewriting to the new state APIs.
            return Collections.emptyList();
        }

        @Override
        public void notifyNoMoreSplits() {
            // Called by the enumerator when no more splits are to be expected.
            // For our single split, this is effectively a no-op as we handle end_of_input
            // based on the SourceFunction's run lifecycle.
        }

        @Override
        public void close() throws Exception {
            isRunning.set(false); // Signal the SourceFunction to stop
            if (sourceFunction instanceof org.apache.flink.streaming.api.functions.source.StoppableFunction) {
                ((org.apache.flink.streaming.api.functions.source.StoppableFunction) sourceFunction).stop();
            }
            sourceFunction.cancel(); // Call cancel to interrupt if stuck
            if (executorService != null) {
                executorService.shutdownNow(); // Force shutdown of the background thread
            }
        }
    }

    /**
     * A simple enumerator for a single, non-parallel split.
     * It assigns the single split to the first reader that connects.
     */
    private static class SingleSplitEnumerator implements SplitEnumerator<LegacySplit, Void> {
        private final SplitEnumeratorContext<LegacySplit> context;
        private boolean assigned = false; // Flag to ensure split is assigned only once

        public SingleSplitEnumerator(SplitEnumeratorContext<LegacySplit> context) {
            this.context = context;
        }

        @Override
        public void start() {
            // On start, if a reader is already connected, assign the split.
            // Otherwise, it will be assigned when a reader requests it.
            if (!assigned && !context.registeredReaders().isEmpty()) {
                context.assignSplit(new LegacySplit(), context.registeredReaders().keySet().iterator().next());
                assigned = true;
            }
        }

        @Override
        public void handleSplitRequest(int subtaskId, String requesterHostname) {
            // When a reader requests a split, assign it if not already assigned.
            if (!assigned) {
                context.assignSplit(new LegacySplit(), subtaskId);
                assigned = true;
            }
        }

        @Override
        public void addSplitsBack(java.util.List<LegacySplit> splits, int subtaskId) {
            // If the reader fails, the split is added back. For a single-split, non-parallel source,
            // this implies restarting the reader with the same split.
        }

        @Override
        public void addReader(int subtaskId) {
            // A new reader connected. If the split hasn't been assigned, do it now.
            if (!assigned) {
                context.assignSplit(new LegacySplit(), subtaskId);
                assigned = true;
            }
        }

        @Override
        public Void snapshotState(long checkpointId) { return null; } // No state to snapshot for this enumerator

        @Override
        public void close() {}
    }

    /**
     * A custom `SourceContext` implementation that redirects the elements
     * emitted by the legacy `SourceFunction` into a `BlockingQueue`.
     */
    private static class QueueSourceContext<T> implements SourceFunction.SourceContext<T> {
        private final BlockingQueue<T> queue;
        private final Object checkpointLock = new Object();
        private final AtomicBoolean isRunning;

        public QueueSourceContext(BlockingQueue<T> queue, AtomicBoolean isRunning) {
            this.queue = queue;
            this.isRunning = isRunning;
        }

        @Override
        public void collect(T element) {
            try {
                if (isRunning.get()) { // Only collect if the reader is still active
                    queue.put(element);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Queue put interrupted", e);
            }
        }

        // For simplicity, watermark/timestamp logic is expected to be handled
        // by a WatermarkStrategy after the source, similar to env.addSource().
        @Override
        public void collectWithTimestamp(T element, long timestamp) { collect(element); }
        @Override
        public void emitWatermark(Watermark mark) { /* No-op */ }
        @Override
        public void markAsTemporarilyIdle() { /* No-op */ }

        @Override
        public Object getCheckpointLock() {
            return checkpointLock; // Required for SourceFunction checkpointing
        }

        @Override
        public void close() { /* No-op */ }
    }
}
```

### How to Use This Wrapper

Once you have implemented this `LegacySourceWrapper` (or a similar custom bridge) within your project, you can integrate your old `SourceFunction` into a modern Flink pipeline using `StreamExecutionEnvironment.fromSource()`:

```java
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
// Assume your LegacySourceWrapper and MyComplexTaxiRideSource are available
// import your.package.LegacySourceWrapper;
// import your.package.MyComplexTaxiRideSource;
// import org.apache.flink.training.exercises.common.datatypes.TaxiRide; // Example data type

public class MyPipelineUsingBridgedSource {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // IMPORTANT: For this wrapper, parallelism MUST be 1.
        // Legacy SourceFunctions are not designed for parallel execution by themselves.
        env.setParallelism(1); 

        // Your existing, custom SourceFunction instance
        SourceFunction<TaxiRide> myLegacyRideSource = new MyComplexTaxiRideSource(); // Replace with your actual SourceFunction

        // Wrap it using your custom bridge class
        Source<TaxiRide, ?, ?> modernSource = new LegacySourceWrapper<>(myLegacyRideSource);

        // Use it with the modern fromSource API!
        DataStream<TaxiRide> stream = env.fromSource(
            modernSource,
            // You can apply your WatermarkStrategy here.
            // The LegacySourceWrapper collects elements without timestamps directly into the queue.
            WatermarkStrategy.<TaxiRide>forMonotonousTimestamps() // Or your specific strategy
                             .withTimestampAssigner((ride, timestamp) -> ride.getEventTimeMillis()),
            "Bridged Legacy TaxiRide Source"
        );

        stream.print(); // Or continue your pipeline logic

        env.execute("Legacy Source Bridged Pipeline");
    }
}
```

### Conclusion and Recommendation

Implementing such a wrapper allows you to continue using your existing `SourceFunction` code while migrating to the `fromSource()` API. This can be a valuable intermediary step, preventing a complete rewrite of your entire codebase at once.

However, it's crucial to understand that this wrapper serves as a **temporary migration aid**. It does not unlock the full capabilities of the new `Source` API, such as dynamic parallelism, improved split discovery, and more robust checkpointing for distributed sources.

The long-term recommended approach is to:
1.  **Utilize Official Connectors:** If your source data comes from a common system (Kafka, Kinesis, etc.), use Flink's official new `Source` connectors (e.g., `KafkaSource`, `FileSource`).
2.  **Rewrite Custom Sources Natively:** For truly custom data sources, you should eventually rewrite them to natively implement the new `org.apache.flink.api.connector.source.Source` interface, creating proper `SplitEnumerator` and `SourceReader` components to fully leverage the modern API's benefits.