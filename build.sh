#!/bin/bash

./gradlew quarkusBuild -x test;

docker build -t immich-compactor .;
