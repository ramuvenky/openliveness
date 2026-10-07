#!/bin/sh
# Creates the relay JWT signing key pair in ./keys (git ignored).
set -e
mkdir -p keys
openssl ecparam -name prime256v1 -genkey -noout -out keys/relay-ec.pem
# jose importPKCS8 needs PKCS8, so convert the SEC1 key
openssl pkcs8 -topk8 -nocrypt -in keys/relay-ec.pem -out keys/relay-private.pem
openssl ec -in keys/relay-ec.pem -pubout -out keys/relay-public.pem
rm keys/relay-ec.pem
echo "keys written to ./keys"
