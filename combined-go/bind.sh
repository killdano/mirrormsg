#!/bin/sh
# Builds the Go bridges into one AAR and copies it to app/libs/mirrorbridge.aar,
# where app/build.gradle.kts expects it.
#
# Needs Go (the version in go.mod), gomobile and gobind at the
# golang.org/x/mobile version go.mod names, and an Android NDK
# (ANDROID_NDK_HOME). Every package the app imports as fi.mirrormsg.<name>
# must be listed here.
set -eu
cd "$(dirname "$0")"
gomobile bind -target "android/arm64" -androidapi 26 -javapkg "fi.mirrormsg" \
  -o out/mirrorbridge.aar \
  fi.mirrormsg/mirrorbridge \
  fi.mirrormsg/fbmessagebridge \
  fi.mirrormsg/whatsappbridge \
  fi.mirrormsg/telegrambridge \
  fi.mirrormsg/discordbridge \
  fi.mirrormsg/igbridge \
  fi.mirrormsg/signalbridge \
  fi.mirrormsg/xbridge \
  fi.mirrormsg/mediaroute
mkdir -p ../app/libs
cp out/mirrorbridge.aar ../app/libs/mirrorbridge.aar
