#!/bin/bash
# Build everything and run all five test harnesses.
set -e
H2=lib/h2-2.3.232.jar
if [ ! -f "$H2" ]; then
  echo "Missing $H2 -- download it from:"
  echo "  https://github.com/h2database/h2database/releases/download/version-2.3.232/h2-2.3.232.jar"
  exit 1
fi
rm -rf out && mkdir -p out
javac -cp $H2 -d out $(find src -name '*.java')
echo "=== Matching engine ==="
java -cp out com.nyu.cs6103.exchange.server.EngineTest
echo
echo "=== Database layer ==="
java -cp out:$H2 com.nyu.cs6103.exchange.server.db.DbTest
echo
echo "=== Network end-to-end ==="
java -cp out:$H2 com.nyu.cs6103.exchange.server.NetworkTest
echo
echo "=== Client view models (headless) ==="
java -Djava.awt.headless=true -cp out com.nyu.cs6103.exchange.client.UiTest
echo
echo "=== Simulated bot market ==="
java -cp out:$H2 com.nyu.cs6103.exchange.bot.SimBotTest
