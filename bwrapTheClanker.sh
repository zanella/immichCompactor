#!/bin/bash

# TODO: re-set Java Home
# TODO: the opencode storage

CWD="$(pwd)";
bwrap --ro-bind / / \
      --tmpfs /home \
      --tmpfs /root \
      --tmpfs /tmp \
      --bind "$CWD" /home/workspace \
      --bind /home/rzanella/private/opencode_storage /home/opencode_home \
      --dev /dev \
      --bind /dev/pts /dev/pts \
      --share-net \
      --setenv HOME /home/opencode_home \
      --setenv JAVA_HOME /usr/lib/jvm/jre-25-openjdk \
      --chdir /home/workspace \
      bash -c "npx opencode";

#EOF

