#!/bin/bash
# usage: ./monitoring/run_remote.sh <run_name> <threads> <total_swipes>
RUN=$1
THREADS=$2
TOTAL=$3
KEY=~/Desktop/neu/6650/cs6650-lab1-key.pem

mkdir -p results-v2/$RUN

ssh -i $KEY ec2-user@$CLIENT \
  "cd ~ && rm -f results-v2/* && SKITRAK_HOST=$ALB THREADS=$THREADS TOTAL_SWIPES=$TOTAL java -jar client.jar | tee results-v2/client_output.txt"

scp -i $KEY "ec2-user@$CLIENT:~/results-v2/*" results-v2/$RUN/
echo "results saved to results-v2/$RUN/"