#!/usr/bin/env bash
# בונה את SendEasyBT.jar למכשירי נוקיה (J2ME: CLDC 1.0, MIDP 2.0, JSR-82).
# דורש JDK 8 (javac עם -source/-target 1.3). ProGuard מבצע preverify ומוריד את גרסת ה-class ל-1.3.
# שימוש: VERSION=0.1.5 ./build.sh   (הפלט: dist/SendEasyBT-<גרסה>.jar)
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-0.1.0}"
M2=https://repo1.maven.org/maven2
rm -rf build dist && mkdir -p libs build/classes dist

fetch() { [ -f "libs/$2" ] || curl -sfL "$M2/$1" -o "libs/$2"; }
fetch org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar cldc.jar
fetch org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar midp.jar
fetch net/sf/bluecove/bluecove/2.1.0/bluecove-2.1.0.jar bluecove.jar
fetch net/sf/proguard/proguard-base/6.2.2/proguard-base-6.2.2.jar proguard.jar

javac -encoding UTF-8 -source 1.3 -target 1.3 -nowarn \
  -bootclasspath libs/cldc.jar:libs/midp.jar -classpath libs/bluecove.jar \
  -d build/classes src/il/sendeasy/bt/*.java

java -cp libs/proguard.jar proguard.ProGuard \
  -injars build/classes -outjars build/app.jar \
  -libraryjars libs/cldc.jar -libraryjars libs/midp.jar -libraryjars libs/bluecove.jar \
  -microedition -target 1.3 -dontobfuscate -dontoptimize -dontwarn \
  -keep "public class il.sendeasy.bt.SendEasyMIDlet"

cat > build/manifest.mf <<EOF
MIDlet-Name: SendEasyBT
MIDlet-Vendor: send-easy-bt
MIDlet-Version: $VERSION
MIDlet-1: SendEasyBT,,il.sendeasy.bt.SendEasyMIDlet
MicroEdition-Configuration: CLDC-1.0
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions: javax.microedition.io.Connector.bluetooth.client, javax.microedition.io.Connector.bluetooth.server
EOF
jar ufm build/app.jar build/manifest.mf
cp build/app.jar "dist/SendEasyBT-$VERSION.jar"
ls -l dist
