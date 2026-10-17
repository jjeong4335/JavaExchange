#!/bin/bash
# Build and run the matching-engine throughput benchmark.
# Usage: ./bench.sh [orders-per-round] [measured-rounds]   (defaults: 1000000 10)
set -e
rm -rf out && mkdir -p out
javac -cp lib/h2-2.3.232.jar -d out $(find src -name '*.java')
java -cp out com.nyu.cs6103.exchange.server.EngineBenchmark "$@"
