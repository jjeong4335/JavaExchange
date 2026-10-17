#!/bin/bash
# Start simulated trading bots. Run server.sh first.
#   ./bot.sh              3 bots on AAPL
#   ./bot.sh 5            5 bots on AAPL
#   ./bot.sh 3 MSFT       3 bots on MSFT
COUNT="${1:-3}"
SYMBOL="${2:-AAPL}"
java -cp out:lib/h2-2.3.232.jar com.nyu.cs6103.exchange.bot.SimBot localhost 5555 "$COUNT" "$SYMBOL"
