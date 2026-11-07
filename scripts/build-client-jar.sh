#!/usr/bin/env bash
# Build fat .jar cho Parental Control Client desktop — tương đương artifact
# IntelliJ "parental-network-control:jar" (.idea/artifacts/parental_network_control_jar.xml):
#   - Main-Class: com.parental.client.app.ClientApp (parental-server/META-INF/MANIFEST.MF)
#   - classes của parental-server + parental-common + parental-client
#   - TOÀN BỘ dependency runtime extract vào gốc jar (jackson, oshi, jna,
#     sqlite-jdbc, slf4j, flatlaf) — chạy được: java -jar parental-network-control.jar
# Dùng khi không mở IntelliJ (build từ CLI trên Windows/Linux/macOS).
set -euo pipefail

# Chạy từ mọi thư mục — luôn về project root (thư mục chứa script cha)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

OUT_DIR="out/artifacts/parental_network_control_jar"
# Override được qua env: JAR_NAME=parental-network-control.new.jar bash scripts/build-client-jar.sh
JAR_NAME="${JAR_NAME:-parental-network-control.jar}"
MVN="${MVN:-mvn}"

# mvn không có trong PATH → thử Maven bundled trong IntelliJ (Windows)
if ! command -v "$MVN" >/dev/null 2>&1; then
  for candidate in "/c/Program Files/JetBrains/IntelliJ IDEA"*/plugins/maven/lib/maven3/bin/mvn.cmd; do
    if [ -f "$candidate" ]; then MVN="$candidate"; break; fi
  done
fi
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -f "$MVN" ]; then
  echo "[FATAL] Không tìm thấy 'mvn' trong PATH lẫn Maven bundled IntelliJ." >&2
  exit 1
fi

# jar không có trong PATH → thử JDK 17 chuẩn Windows
if ! command -v jar >/dev/null 2>&1; then
  for candidate in "/c/Program Files/Java"/jdk*/bin "/c/Program Files/Common Files/Oracle/Java/javapath"; do
    if [ -x "$candidate/jar.exe" ] || [ -x "$candidate/jar" ]; then
      export PATH="$candidate:$PATH"
      break
    fi
  done
fi
if ! command -v jar >/dev/null 2>&1; then
  echo "[FATAL] Không tìm thấy lệnh 'jar' (cần JDK trong PATH)." >&2
  exit 1
fi
JAR_CMD="$(command -v jar)"

echo "[1/4] mvn package -DskipTests (common + server + client)..."
"$MVN" -q package -DskipTests

echo "[2/4] Chuẩn bị thư mục output: $OUT_DIR"
mkdir -p "$OUT_DIR"
# File cũ có thể đang bị JVM giữ khóa (app đang chạy) — bỏ qua lỗi rm
rm -f "$OUT_DIR/$JAR_NAME" "$OUT_DIR/$JAR_NAME.tmp" 2>/dev/null || true

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[3/4] Đóng gói classes + dependencies..."
JAR_FILE="$OUT_DIR/$JAR_NAME.tmp"
# Ghi MANIFEST trước (Main-Class) — jar cfe cập nhật entry có sẵn
mkdir -p "$WORK/meta"
cp parental-server/META-INF/MANIFEST.MF "$WORK/meta/MANIFEST.MF"

jar cfe "$JAR_FILE" com.parental.client.app.ClientApp -C "$WORK/meta" MANIFEST.MF
  # Classes 3 module (giữ thứ tự: server trước để MANIFEST không bị ghi đè semantics)
  jar ufe "$JAR_FILE" com.parental.client.app.ClientApp \
      -C parental-server/target/classes . \
      -C parental-common/target/classes . \
      -C parental-client/target/classes .
  # Dependencies runtime từ Maven local repo — extract vào gốc jar (unpack)
  for dep in \
      com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar \
      com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar \
      com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar \
      org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar \
      org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar \
      com/github/oshi/oshi-core/6.6.5/oshi-core-6.6.5.jar \
      net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar \
      net/java/dev/jna/jna-platform/5.15.0/jna-platform-5.15.0.jar \
      org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar \
      com/formdev/flatlaf/3.5.1/flatlaf-3.5.1.jar; do
    if [ -f "${HOME}/.m2/repository/$dep" ]; then
      unzip -oq "${HOME}/.m2/repository/$dep" -d "$WORK/ext"
      rm -f "$WORK/ext/META-INF/MANIFEST.MF" "$WORK/ext/META-INF/MANIFEST.SF" \
            "$WORK/ext/META-INF/\*.SF" "$WORK/ext/META-INF/\*.DSA" "$WORK/ext/META-INF/\*.RSA"
    else
      echo "  [WARN] Thiếu dependency: $dep (jar sẽ thiếu lib này)"
    fi
  done
  # Đóng nội dung đã extract vào jar
  jar uf "$JAR_FILE" -C "$WORK/ext" .

mv "$JAR_FILE" "$OUT_DIR/$JAR_NAME"

echo "[4/4] Hoàn tất:"
ls -la "$OUT_DIR"
echo "Chạy thử: java -jar \"$OUT_DIR/$JAR_NAME\" [serverHost] [serverPort]"
