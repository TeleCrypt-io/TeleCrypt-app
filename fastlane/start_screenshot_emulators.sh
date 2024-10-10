#!/bin/bash

ANDROID_PLATFORM=${ANDROID_PLATFORM:-35}

avdmanager create avd -n Nexus_5 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 5"
avdmanager create avd -n Nexus_7 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 7"
avdmanager create avd -n Nexus_10 -k "system-images;android-${ANDROID_PLATFORM};google_apis;x86_64" -d "Nexus 10"

emulator -avd Nexus_5 -port 5554 -no-window &
emulator -avd Nexus_7 -port 5556 -no-window &
emulator -avd Nexus_10 -port 5558 -no-window -prop persist.sys.orientation=landscape &
