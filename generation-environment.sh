#!/bin/sh

WORKDIR=`pwd | rev | cut -d "/" -f1 | rev`

RED="\033[0;31m"
YELLOW="\033[0;33m"
GREEN="\033[0;32m"
BLUE="\033[0;94m"
RESET="\033[0;00m"

create_container_environment() {
  printf "${GREEN}Creating${RESET} new generation environment (container: ${BLUE}${CONTAINER_NAME}${RESET})...\n"
  docker run \
  --name $CONTAINER_NAME \
  -d \
  -e GRADLE_OPTS="-Dorg.gradle.native=false" \
  -v /$(pwd)/../output:/opt/output \
  -v /$(pwd):/opt/salt \
  -it \
  --workdir="//opt/salt" \
  uncharted/sparklet:2.0.0 \
  bash
}

run_container_environment() {
  printf "${GREEN}Resuming${RESET} existing generation environment (container: ${BLUE}${CONTAINER_NAME}${RESET})...\n"
  docker start $CONTAINER_NAME
}

stop_container_environment() {
  printf "${YELLOW}Stopping${RESET} generation environment (container: ${BLUE}${CONTAINER_NAME}${RESET})...\n"
  docker stop $CONTAINER_NAME
}

kill_container_environment() {
  printf "${RED}Destroying${RESET} generation environment (container: ${BLUE}${CONTAINER_NAME}${RESET})...\n"
  docker rm -fv $CONTAINER_NAME
}

verify_container_environment() {
  PRESENT=$(docker ps -a -q -f name=$CONTAINER_NAME)
  if [ -n "$PRESENT" ]; then
    run_container_environment
  else
    create_container_environment
  fi
}

set_container_name() {
  if [ "$1" != "" ]; then
    CONTAINER_NAME="$1"
  else
    echo "${RED}Must provide a container name${RESET}"
    exit 1
  fi
}

if [ "$1" = "stop" ]; then
  set_container_name "$2"
  stop_container_environment
elif [ "$1" = "rm" ]; then
  set_container_name "$2"
  kill_container_environment
else
  set_container_name "$1"
  verify_container_environment
fi
