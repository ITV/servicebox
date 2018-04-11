#!/bin/bash

#exit non-zero return code if a simple command exits with non-zero return code
set -e

echo "TRAVIS_PULL_REQUEST = $TRAVIS_PULL_REQUEST"
echo "TRAVIS_BRANCH = $TRAVIS_BRANCH"
echo "TRAVIS_TAG = $TRAVIS_TAG"
version=`cat version.sbt`
echo "version = $version"

sbt_cmd="sbt ++$TRAVIS_SCALA_VERSION"

if [[ $TRAVIS_TAG =~ ^v[0-9].* || ($TRAVIS_PULL_REQUEST == "false" && $TRAVIS_BRANCH == "master") ]]; then
  openssl aes-256-cbc -K $encrypted_1426a13332e0_key -iv $encrypted_1426a13332e0_iv -in ci/private.asc.enc -out ci/private.asc -d
  eval "$sbt_cmd clean +publishSigned"
fi;
