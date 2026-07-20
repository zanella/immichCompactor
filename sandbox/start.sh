#!/bin/bash

docker compose up -d;

xdg-open "https://docs.immich.app/install/post-install";

xdg-open "http://localhost:2283"
#EOF
