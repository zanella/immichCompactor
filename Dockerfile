FROM fedora:44 AS builder

EXPOSE 8080

RUN dnf upgrade -y

RUN dnf install libjxl -y

RUN dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-44.noarch.rpm -y
#https://mirrors.rpmfusion.org/nonfree/fedora/rpmfusion-nonfree-release-$(rpm -E %fedora).noarch.rpm
RUN dnf install HandBrake -y

RUN dnf install java-25-openjdk -y

# TODO: jar

# TODO: volume for the sqlite file

# TODO: CMD