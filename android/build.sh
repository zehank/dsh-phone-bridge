#!/usr/bin/env bash
# 一条命令出 APK：aapt2 → javac → d8 → zip → zipalign → apksigner。
# 不依赖 Gradle / Android Studio，只用 SDK 里那几个可执行文件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WS="$(cd "$ROOT/../../../.." && pwd)"
TC="${PB_TOOLCHAIN:-$WS/.dsh/android}"

JDK="$TC/jdk/Contents/Home"
BT="$TC/bt/android-14"
AJAR="$TC/platform/android-34/android.jar"

for p in "$JDK/bin/javac" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$p" ] || { echo "缺工具链：$p" >&2; exit 1; }
done
export PATH="$JDK/bin:$PATH"

OUT="$ROOT/build"
APPSRC="$ROOT/app"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/res"

echo "1/5 资源"
"$BT/aapt2" compile --dir "$APPSRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$APPSRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 33 \
  --version-code 1 \
  --version-name 1.0 \
  "$OUT/res.zip"

echo "2/5 javac"
find "$APPSRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -source 8 -target 8 -nowarn -classpath "$AJAR:$ROOT/libs/zxing-core.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'source value 8\|target value 8\|bootstrap class path' || true
[ -f "$OUT/classes/dev/zehan/phonebridge/MainActivity.class" ] || { echo "javac 没产出 class" >&2; exit 1; }

echo "3/5 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$AJAR" --min-api 26 --release --output "$OUT/dex" @"$OUT/classes.txt" "$ROOT/libs/zxing-core.jar"

echo "4/5 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

echo "5/5 对齐 + 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/phonebridge.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$ROOT/phonebridge.apk" | head -3

ls -lh "$ROOT/phonebridge.apk"
echo "OK → $ROOT/phonebridge.apk"
