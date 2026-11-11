#!/usr/bin/env bash
# 生成 Release 签名密钥并回填 rootProject/keystore.properties(两者都不入库)。
# 口令只在终端里输入一次,通过环境变量交给 keytool,不出现在命令行参数里。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${ROOT}/release.jks"
PROPERTIES="${ROOT}/keystore.properties"
ALIAS="${SONLET_KEY_ALIAS:-sonlet}"

if [[ -e "${KEYSTORE}" ]]; then
    echo "${KEYSTORE} 已存在,拒绝覆盖" >&2
    echo "更换签名密钥会让已安装的旧版本无法覆盖升级,请确认确实要换再手动删除。" >&2
    exit 1
fi

read -r -s -p "为 release.jks 设置口令: " password
echo
read -r -s -p "再次输入确认: " confirm
echo

if [[ "${password}" != "${confirm}" ]]; then
    echo "两次输入不一致" >&2
    exit 1
fi
if [[ ${#password} -lt 6 ]]; then
    echo "口令至少 6 个字符(keytool 的下限)" >&2
    exit 1
fi

STOREPASS="${password}" keytool -genkeypair \
    -keystore "${KEYSTORE}" \
    -storetype PKCS12 \
    -alias "${ALIAS}" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Sonlet, OU=Mobile, O=Sonlet, L=Shanghai, ST=Shanghai, C=CN" \
    -storepass:env STOREPASS \
    -keypass:env STOREPASS

chmod 600 "${KEYSTORE}"
(
    umask 077
    {
        printf '# Release 签名参数 —— 本文件已被 .gitignore 排除,不要提交\n'
        printf 'storeFile=release.jks\n'
        printf 'storePassword=%s\n' "${password}"
        printf 'keyAlias=%s\n' "${ALIAS}"
        printf 'keyPassword=%s\n' "${password}"
    } > "${PROPERTIES}"
)

echo
echo "已生成:"
echo "  ${KEYSTORE}"
echo "  ${PROPERTIES}"
echo
echo "下一步:"
echo "  1. 立即备份 release.jks 与口令,丢失后无法为已上架应用签名升级"
echo "  2. ./gradlew assembleRelease 验证产物为 app-release.apk"
echo "  3. 把 base64 -i release.jks 的结果与口令录入仓库 secrets(见 README)"
