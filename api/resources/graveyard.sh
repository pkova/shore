#!/bin/bash
set -e
pkill screen
sleep 5
tar -Scvzf /root/urbit/lissyr-picdep.tar.gz /root/urbit/lissyr-picdep
aws s3 cp /root/urbit/lissyr-picdep.tar.gz s3://shore-graveyard
