#!/bin/bash
# Start the exchange server on port 5555 (or $1).
java -cp out:lib/h2-2.3.232.jar com.nyu.cs6103.exchange.server.ExchangeServer "${1:-5555}"
