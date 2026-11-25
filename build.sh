#!/bin/bash

./gradlew clean quarkusBuild -x test;

docker build -t immich-compactor .;
