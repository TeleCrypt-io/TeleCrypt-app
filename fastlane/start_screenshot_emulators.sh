#!/bin/bash

ANDROID_PLATFORM=${ANDROID_PLATFORM:-35}

avdmanager create avd -n Nexus_5 -k "system-images;android-${ANDROID_PLATFORM};google_apis;arm64-v8a" -d "Nexus 5"
avdmanager create avd -n Nexus_7 -k "system-images;android-${ANDROID_PLATFORM};google_apis;arm64-v8a" -d "Nexus 7"
avdmanager create avd -n Nexus_10 -k "system-images;android-${ANDROID_PLATFORM};google_apis;arm64-v8a" -d "Nexus 10"

mkdir "emulator_logs"
emulator -avd Nexus_5 -port 5554 -no-window >> emulator_logs/nexus_5.log 2>&1 &
emulator -avd Nexus_7 -port 5556 -no-window >> emulator_logs/nexus_7.log 2>&1 &
emulator -avd Nexus_10 -port 5558 -no-window -prop persist.sys.orientation=landscape >> emulator_logs/nexus_10.log 2>&1 &
