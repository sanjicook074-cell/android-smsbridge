#!/usr/bin/env bash
# 手工编译「短信桥」APK —— 不依赖 gradle / Android Studio。
#
# 为什么手写这套：
#   为 300 行代码装一整套 gradle + AGP 要下几百 MB，不划算。
#   APK 的本质就是 zip + 一个二进制 manifest + 一个 dex，这几步命令行就能完成：
#     aapt2  → 把 AndroidManifest.xml 编成二进制并起一个 apk 骨架
#     javac  → 编译 java（classpath 用 android.jar）
#     d8     → class 转 dex
#     zipalign + apksigner → 对齐并签名
#
# ── 你需要准备什么 ──
#   1. 一个 JDK（javac / keytool 都在里面）。JetBrains Runtime、Temurin、Oracle 都行。
#   2. Android SDK 的两小块：build-tools（要 aapt2 / zipalign / lib/d8.jar /
#      lib/apksigner.jar）和一个平台的 android.jar。
#      不想装 Android Studio 的话，可以只下 SDK 命令行工具再
#      `sdkmanager "build-tools;34.0.0" "platforms;android-34"`。
#
# ── 三个位置怎么告诉它 ──
#   命令行参数 > 环境变量 > 常见安装位置自动探测。比如：
#     JAVA_HOME=/path/to/jdk \
#     ANDROID_BT=/path/to/build-tools/34.0.0 \
#     ANDROID_JAR=/path/to/platforms/android-34/android.jar \
#     ./build.sh
#
# ── 令牌与地址 ──
#   令牌（收码服务的访问令牌，编译时烤进 APK）取用顺序：
#     环境变量 SMSBRIDGE_TOKEN > 项目根的 .smsbridge_token 文件 > 自动生成一个
#   电脑地址同理：SMSBRIDGE_HOST > 默认 192.168.1.100:8800（装好后在 App 界面上也能改）。
#   源码里永远只有 __TOKEN__ / __HOST__ 占位符，替换只发生在 build/ 的副本上 ——
#   所以真值不会漏进版本库。
#
# ⚠ 这里每个路径都要经 w() 转成 Windows 形式再喂给 .exe：
#   aapt2/javac/zipalign/keytool 是原生 Windows 程序，它们**不认** Git Bash 的 /c/xxx
#   （实测：直接传 /c/... 会被 aapt2 判成「系统找不到指定的文件」）。
#   在 Linux / macOS 上跑时 w() 是个恒等函数，不影响。
set -e

# bash 路径 → Windows 路径（形如 /c/foo → C:/foo）；非 Windows 平台原样返回
w() { printf '%s' "$1" | sed -E 's|^/([a-zA-Z])/|\1:/|'; }

PROJ="$(cd "$(dirname "$0")" && pwd)"
BUILD="$PROJ/build"
KS="$PROJ/keystore.jks"
OUT="$PROJ/smsbridge.apk"

# ---------- 定位 JDK ----------
find_jdk() {
  [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac.exe" -o -x "$JAVA_HOME/bin/javac" ] && { echo "$JAVA_HOME"; return; }
  # 常见安装位置（JetBrains Runtime / Temurin / Android Studio 自带的 JBR）
  local cand
  for cand in \
    "/c/Program Files/JetBrains"/*"/jbr" \
    "/c/Program Files/Eclipse Adoptium"/*/ \
    "/c/Program Files/Android/Android Studio/jbr" \
    "/usr/lib/jvm"/*/ ; do
    [ -x "$cand/bin/javac" ] || [ -x "$cand/bin/javac.exe" ] && { echo "$cand"; return; }
  done
}
JDK="$(find_jdk)"
[ -n "$JDK" ] || { echo "[x] 找不到 JDK。设 JAVA_HOME 或把 javac 放进 PATH。"; exit 1; }

# ---------- 定位 Android build-tools ----------
find_bt() {
  [ -n "$ANDROID_BT" ] && [ -d "$ANDROID_BT" ] && { echo "$ANDROID_BT"; return; }
  local root
  for root in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" \
              "/c/Users/$USER/AppData/Local/Android/Sdk" \
              "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$root/build-tools" ] || continue
    # 取版本号最大的那个
    ls -1 "$root/build-tools" | sort -V | tail -1 | sed "s|^|$root/build-tools/|"
    return
  done
}
BT="$(find_bt)"
[ -n "$BT" ] && [ -d "$BT" ] || {
  echo "[x] 找不到 build-tools（要 aapt2 / zipalign / lib/d8.jar / lib/apksigner.jar）。"
  echo "    设 ANDROID_BT=/path/to/build-tools/<版本>，或装 Android SDK 命令行工具后："
  echo "      sdkmanager \"build-tools;34.0.0\" \"platforms;android-34\""
  exit 1
}

# ---------- 定位 android.jar ----------
find_android_jar() {
  [ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] && { echo "$ANDROID_JAR"; return; }
  local root
  for root in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" \
              "/c/Users/$USER/AppData/Local/Android/Sdk" \
              "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$root/platforms" ] || continue
    # 取版本号最大的平台
    local p
    p="$(ls -1 "$root/platforms" | sort -V | tail -1)"
    [ -f "$root/platforms/$p/android.jar" ] && echo "$root/platforms/$p/android.jar"
    return
  done
}
ANDROID_JAR="$(find_android_jar)"
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || {
  echo "[x] 找不到 android.jar。设 ANDROID_JAR=/path/to/platforms/android-34/android.jar"
  exit 1
}

JAVA="$JDK/bin/java"
JAVAC="$JDK/bin/javac"
KEYTOOL="$JDK/bin/keytool"
[ -x "$JAVA.exe" ] && { JAVA="$JAVA.exe"; JAVAC="$JAVAC.exe"; KEYTOOL="$KEYTOOL.exe"; }
AAPT2="$BT/aapt2"; [ -x "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ZIPALIGN="$BT/zipalign"; [ -x "$ZIPALIGN.exe" ] && ZIPALIGN="$ZIPALIGN.exe"
D8_JAR="$BT/lib/d8.jar"
SIGNER_JAR="$BT/lib/apksigner.jar"

# ---------- 令牌与地址 ----------
TOKEN_FILE="$PROJ/.smsbridge_token"
if [ -z "$SMSBRIDGE_TOKEN" ]; then
  if [ -f "$TOKEN_FILE" ]; then
    SMSBRIDGE_TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
  else
    SMSBRIDGE_TOKEN="$(head -c 9 /dev/urandom | base64 | tr -d '/+=' | head -c 12)"
    printf '%s' "$SMSBRIDGE_TOKEN" > "$TOKEN_FILE"
    echo "[i] 没找到令牌，已生成一个并写进 $TOKEN_FILE"
  fi
fi
[ -n "$SMSBRIDGE_TOKEN" ] || { echo "[x] 令牌是空的"; exit 1; }

HOST="${SMSBRIDGE_HOST:-192.168.1.100:8800}"

# ---------- 找一个 python（第 ④ 步用它把 classes.dex 塞进 apk）----------
# 刻意用 python 的 zipfile 而不是 `zip` 命令：Windows 上不一定有 zip。
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
[ -n "$PY" ] || { echo "[x] 找不到 python（试过 python3 / python / py）。设 PYTHON=/path/to/python"; exit 1; }

# ---------- 前置检查 ----------
for f in "$JAVAC" "$AAPT2" "$ZIPALIGN" "$D8_JAR" "$SIGNER_JAR" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "[x] 缺文件：$f"; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/gen/com/smsbridge" "$BUILD/classes" "$BUILD/dex"

# ★ 源码清单自动枚举，别再手写文件名：
#   原来这两处 for 写死成 "MainActivity SmsReceiver PostTask"，
#   之后加一个类（MsgLog / Ticker）会**悄悄漏编** —— javac 不报错，
#   直到手机上运行才 NoClassDefFoundError。
JAVA_NAMES=$(cd "$PROJ/src/com/smsbridge" && ls *.java | sed 's/\.java$//')
[ -n "$JAVA_NAMES" ] || { echo "[x] src/com/smsbridge 下没找到 .java"; exit 1; }

# ---------- 0. 复制源码，替换 __TOKEN__ / __HOST__ ----------
# 刻意在副本上替换：源文件里永远只留占位符，不会被误提交进 git。
for f in $JAVA_NAMES; do
  sed -e "s|__TOKEN__|$SMSBRIDGE_TOKEN|g" -e "s|__HOST__|$HOST|g" \
    "$PROJ/src/com/smsbridge/$f.java" > "$BUILD/gen/com/smsbridge/$f.java"
done

echo "① aapt2 —— 编资源 + manifest，起 apk 骨架"
"$AAPT2" compile --dir "$(w "$PROJ/res")" -o "$(w "$BUILD/res.zip")"
"$AAPT2" link \
  -o "$(w "$BUILD/base.apk")" \
  -I "$(w "$ANDROID_JAR")" \
  --manifest "$(w "$PROJ/AndroidManifest.xml")" \
  -R "$(w "$BUILD/res.zip")" \
  --min-sdk-version 21 \
  --target-sdk-version 33 \
  --version-code 1 \
  --version-name 1.0

echo "② javac —— 编译 java"
SRCS=""
for f in $JAVA_NAMES; do
  SRCS="$SRCS $(w "$BUILD/gen/com/smsbridge/$f.java")"
done
# shellcheck disable=SC2086
"$JAVAC" \
  --release 8 \
  -g:none \
  -nowarn \
  -encoding UTF-8 \
  -classpath "$(w "$ANDROID_JAR")" \
  -d "$(w "$BUILD/classes")" \
  $SRCS

echo "③ d8 —— class 转 dex"
CLASSES=$(find "$BUILD/classes" -name '*.class' | sed -E 's|^/([a-zA-Z])/|\1:/|' | tr '\n' ' ')
[ -n "$CLASSES" ] || { echo "[x] 没编出 class"; exit 1; }
# shellcheck disable=SC2086
"$JAVA" -cp "$(w "$D8_JAR")" com.android.tools.r8.D8 \
  --min-api 21 \
  --lib "$(w "$ANDROID_JAR")" \
  --output "$(w "$BUILD/dex")" \
  $CLASSES

echo "④ 把 classes.dex 塞进 apk"
"$PY" - "$(w "$BUILD")" <<'PY'
import sys, zipfile, shutil, os
b = sys.argv[1]
shutil.copy(os.path.join(b, 'base.apk'), os.path.join(b, 'unsigned.apk'))
z = zipfile.ZipFile(os.path.join(b, 'unsigned.apk'), 'a', zipfile.ZIP_DEFLATED)
z.write(os.path.join(b, 'dex', 'classes.dex'), 'classes.dex')
z.close()
print('   classes.dex 已写入')
PY

echo "⑤ zipalign"
"$ZIPALIGN" -f -p 4 "$(w "$BUILD/unsigned.apk")" "$(w "$BUILD/aligned.apk")"

echo "⑥ 生成签名密钥（只做一次）"
# 自签名密钥，只够本机侧载用。口令是公开的 "android" —— 所以这个密钥不构成任何
# 身份保证，别拿它去发布到任何应用商店。
# ★ keystore.jks 已在 .gitignore 里：私钥入库 = 别人能签出你 App 的「更新」。
KS_PASS="${SMSBRIDGE_KS_PASS:-android}"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$(w "$KS")" -alias smsbridge \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=smsbridge, OU=dev, O=dev, L=Beijing, ST=Beijing, C=CN" \
    -storetype JKS 2>/dev/null
  echo "   已生成 $KS（自签，仅供本机侧载）"
else
  echo "   复用已有 keystore"
fi

echo "⑦ apksigner —— 签名"
"$JAVA" -cp "$(w "$SIGNER_JAR")" com.android.apksigner.ApkSignerTool sign \
  --ks "$(w "$KS")" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$(w "$OUT")" "$(w "$BUILD/aligned.apk")"

echo "⑧ 验签"
"$JAVA" -cp "$(w "$SIGNER_JAR")" com.android.apksigner.ApkSignerTool verify --print-certs "$(w "$OUT")" | head -6

echo
echo "=== 产物 ==="
ls -la "$OUT"
echo
echo "手机地址默认值：$HOST（装好后也能在 App 界面上改）"
echo "令牌：已烤进 APK，同时留在 $TOKEN_FILE（收码服务端要配成同一个）"
