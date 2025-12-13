# Gemini Code Assistant Project Context: Flink Training

This document provides context for the `flink-training` project, a set of exercises for learning Apache Flink.

## Project Overview

This project contains a series of hands-on exercises to learn Apache Flink. The exercises cover fundamental concepts such as stream filtering, stateful enrichment, windowed analytics, and the use of `ProcessFunction` and timers.

The project is structured as a multi-module Gradle project. Each module corresponds to a specific Flink exercise:
- `common`: Contains common data types and data generators used across the exercises.
- `ride-cleansing`: An exercise on filtering a stream of taxi ride events.
- `rides-and-fares`: An exercise on stateful enrichment by joining two streams.
- `hourly-tips`: An exercise on windowed analytics.
- `long-ride-alerts`: An exercise on using `ProcessFunction` and timers.

The exercises are provided in Java. Each exercise has a corresponding JUnit test for validation. The solution for each exercise is also provided in the `src/solution` directory of each module.

## Building and Running

The project is built using Gradle. The provided Gradle wrapper script (`gradlew`) should be used.

### Building the project

To build the entire project, run the following command from the root directory:

```bash
./gradlew build
```

### Running the exercises

Each exercise is a standalone application with a `main` method. They can be run directly from an IDE.

To run the exercises from the command line, you can use the `run` tasks provided by Gradle. To see a list of all runnable tasks, use:
```bash
./gradlew printRunTasks
```

This will print a list of tasks such as `./gradlew :ride-cleansing:runjavaExercise`.

### Running the tests

To run the tests for the entire project, use:

```bash
./gradlew test
```

To run tests for a specific subproject:
```bash
./gradlew :<subproject>:test
```
For example:
```bash
./gradlew :ride-cleansing:test
```

## Development Conventions

### Code style

The project uses `spotless` to enforce code style. The style is based on Google Java Format. To apply the formatting, run:

```bash
./gradlew spotlessApply
```

### Dependencies

The project uses Flink version `1.17.0`. The dependencies are managed in the `build.gradle` file. The `shadowJar` plugin is used to create fat jars with all the necessary dependencies.

### Training Guideline
Phase 1: The Fundamentals - Your First Flink Job

  Goal: Understand the basic anatomy of a Flink DataStream program.

  Exercise: ride-cleansing

   1. Read the Code: Start by reading RideCleansingExercise.java. This will show you the basic structure of a Flink job:
       * Getting a StreamExecutionEnvironment.
       * Adding a Source to ingest data.
       * Applying a simple transformation (filter).
       * Adding a Sink to output the results.

   2. Implement the Exercise: Your task is to implement the NYCFilter. This is a great first step because it's a stateless transformation, which is the simplest type. Try to solve it
      yourself first.

   3. Study the Solution: Once you've given it a try, look at ride-cleansing/src/solution/java/.../RideCleansingSolution.java. Compare it with your attempt.

   4. Run and Test: Run the RideCleansingIntegrationTest. Set breakpoints and debug the code to see how the data flows through the pipeline. This will help you understand how Flink
      executes jobs locally.

  Phase 2: Introducing State - The Heart of Flink

  Goal: Learn how to manage state in a Flink application. This is one of Flink's most powerful features.

  Exercise: rides-and-fares

   1. Understand the Problem: This exercise requires you to join two streams (TaxiRide and TaxiFare). Since events can arrive out of order, you need to store one side of the join in state
      until the other side arrives.

   2. Key Concepts to Learn:
       * RichCoFlatMapFunction: A function that can process two different input streams.
       * ValueState: A type of managed state that stores a single value.
       * How to register and use state within a Flink function.

   3. Implement and Study: As before, try to implement the logic yourself. Then, study the solution to see how it handles state and the out-of-order nature of the streams.

  Phase 3: Working with Time - Windows and Watermarks

  Goal: Understand how Flink processes data based on time.

  Exercise: hourly-tips

   1. Event Time vs. Processing Time: This exercise will introduce you to the concept of event time, which is crucial for getting correct results in real-world stream processing.

   2. Key Concepts to Learn:
       * Event Time: Using timestamps embedded in the data itself.
       * Watermarks: Flink's mechanism for tracking the progress of event time.
       * Windows: Grouping events into finite buckets for processing (in this case, tumbling windows).

   3. Implement and Study: This exercise will challenge you to think about time in a new way. The solution will show you how to define windows and work with watermarks.

  Phase 4: Advanced Control - ProcessFunction and Timers

  Goal: Gain fine-grained control over your stream processing logic.

  Exercise: long-ride-alerts

   1. The Power of `ProcessFunction`: This is the most low-level and powerful API in the DataStream API. It gives you access to state, timers, and the raw events themselves.

   2. Key Concepts to Learn:
       * KeyedProcessFunction: A ProcessFunction that operates on a keyed stream.
       * Timers: The ability to register a callback to be executed at some point in the future (in either event time or processing time). This is essential for complex event processing,
         such as detecting patterns or implementing timeouts.

   3. Implement and Study: This is the most advanced exercise. The solution will demonstrate a common pattern: storing an event in state and setting a timer to act on it later.

  Phase 5: Go Beyond the Training

  Once you have completed all the exercises, you can continue your learning with the following:

   * Experiment: Modify the exercises. For example, in hourly-tips, try using a sliding window instead of a tumbling window. In long-ride-alerts, change the logic to detect a different
     pattern.
   * Create Your Own Challenge: Come up with a new use case and try to implement it using the provided data sources. For example, you could try to calculate the average speed of taxis for
     each ride.
   * Explore the Flink Documentation: The official Flink documentation is an excellent resource. Now that you have practical experience, the documentation will make a lot more sense.
   * Contribute: If you feel confident, you could even try to add a new exercise to this project. Check out the CONTRIBUTING.md file for guidelines.
