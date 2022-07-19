#!/bin/bash
set -e
dd if=/dev/zero of=/swapfile bs=128M count=16
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
mkdir ~/urbit
cd ~/urbit
curl -JLO https://urbit.org/install/linux64/latest
tar zxvf ./linux64.tgz --strip=1
setcap 'cap_net_bind_service=+ep' ~/urbit/urbit
screen -d -m ./urbit -p 13454 -w %s -G '%s'
