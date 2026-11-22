#!/bin/bash
set -e
export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.20+101
export PATH=$JAVA_HOME/bin:$PATH
echo "== host $(hostname) =="
echo "== proxy: ${HTTPS_PROXY:-none} / ${https_proxy:-none} =="
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
kotlin.compiler.execution.strategy=in-process
org.gradle.parallel=false
org.gradle.workers.max=1
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.nonProxyHosts=localhost|127.0.0.1
systemProp.http.nonProxyHosts=localhost|127.0.0.1
EOF
SDK=/root/android-sdk
if [ ! -d "$SDK/platforms/android-36" ]; then
  echo "== installing android sdk =="
  mkdir -p "$SDK/cmdline-tools"
  curl -sSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/clt.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platform-tools" "platforms;android-36" "build-tools;35.0.0" > /tmp/sdk.log 2>&1
  unset JAVA_TOOL_OPTIONS
fi
printf 'sdk.dir=%s\n' "$SDK" > /workspace/local.properties
cd /workspace
echo "== gradle build start =="
./gradlew :app:assembleDebug 2>&1 | tail -25
APK=app/build/outputs/apk/debug/app-debug.apk
ls -la "$APK"
cp "$APK" /workspace/Mika-0.1.50-debug.apk
cp "$APK" /data/user/Mika-0.1.50-debug.apk 2>/dev/null || true
sha256sum /workspace/Mika-0.1.50-debug.apk
echo "== ALL DONE =="
