#!/bin/sh
# 构建 AntiFraudBlock（libxposed API 102 / LSPosed modern module）
#
# 依赖：javac(JDK11+) / aapt2 / d8 / zipalign / apksigner / android.jar(platform 34)
# 首次构建会从 Maven Central 拉 libxposed api-102.0.0.aar（没有国内镜像，约 30KB）
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/opt/android-sdk}
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
AAR=libs/libxposed-102.aar
XAPI=libs/aar/classes.jar

mkdir -p libs out

# 1) 拉依赖
if [ ! -f "$XAPI" ]; then
  echo "[0/6] fetch libxposed api 102"
  [ -f "$AAR" ] || curl -sSL -o "$AAR" \
    https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
  mkdir -p libs/aar && (cd libs/aar && unzip -o -q "../$(basename $AAR)")
fi

rm -rf build2
mkdir -p build2/classes

echo "[1/6] javac"
javac -encoding UTF-8 --release 11 -classpath "$AJAR:$XAPI" \
  -d build2/classes $(find module/src -name '*.java')

echo "[2/6] d8"
"$BT/d8" --lib "$AJAR" --classpath "$XAPI" --min-api 33 \
  --output build2 $(find build2/classes -name '*.class')

echo "[3/6] aapt2"
aapt2 compile --dir module/res -o build2/res.zip
aapt2 link -o build2/base.apk -I "$AJAR" --manifest module/AndroidManifest.xml \
  -R build2/res.zip --min-sdk-version 33 --target-sdk-version 34 --auto-add-overlay

echo "[4/6] package"
rm -f build2/unsigned.apk
cp build2/base.apk build2/unsigned.apk
rm -rf build2/stage && mkdir -p build2/stage
cp build2/classes.dex build2/stage/classes.dex
cp -r module/META-INF build2/stage/
(cd build2/stage && zip -q -r ../unsigned.apk classes.dex META-INF)

echo "[5/6] zipalign"
zipalign -f -p 4 build2/unsigned.apk build2/aligned.apk

echo "[6/6] sign"
# keystore 不入库：默认用 debug 证书，正式发布用环境变量覆盖
KS=${AFB_KS:-$HOME/.android/debug.keystore}
KS_PASS=${AFB_KS_PASS:-android}
KS_ALIAS=${AFB_KS_ALIAS:-androiddebugkey}
KS_KEYPASS=${AFB_KS_KEYPASS:-android}

apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" --out out/AntiFraudBlock-2.0.apk build2/aligned.apk

ls -la out/AntiFraudBlock-2.0.apk
echo "OK -> out/AntiFraudBlock-2.0.apk"
