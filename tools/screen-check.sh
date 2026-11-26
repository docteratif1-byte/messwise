#!/usr/bin/env bash
# Opens the test APK on the CI emulator in Urdu and in English and saves screenshots (fonts check).
set -x
APK=$(ls app/build/outputs/apk/preview/*.apk | head -1)
PKG=com.kodawick.messwise.test
ACT=com.kodawick.messwise.MainActivity
mkdir -p screens
adb install -r "$APK" || exit 0
shot() {  # $1 = app language, $2 = file name
  adb shell am force-stop $PKG
  adb shell cmd locale set-app-locales $PKG --locales "$1"
  adb shell am start -W -n $PKG/$ACT
  sleep 15
  adb exec-out screencap -p > "screens/$2.png"
}
shot ur urdu-login
shot en english-login
ls -la screens
