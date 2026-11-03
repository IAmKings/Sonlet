#!/usr/bin/env bash
# Sonlet 模型资产获取脚本(PRD §11.1)
# 下载官方 SenseVoice INT8 归档 → SHA-256 校验 → 解压 model.int8.onnx / tokens.txt 到 assets
# 固定测试样本(zh/en)到 androidTest assets。任何校验失败立即中止,禁止使用未校验文件。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${REPO_ROOT}/.cache/downloads"
MODEL_DIR="${REPO_ROOT}/app/src/main/assets/sensevoice"
TEST_WAV_DIR="${REPO_ROOT}/app/src/androidTest/assets/test_wavs"

ARCHIVE_NAME="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${ARCHIVE_NAME}"
MIRROR_URL="https://huggingface.co/k2-fsa/sherpa-onnx-models/resolve/main/asr-models/${ARCHIVE_NAME}"
ARCHIVE_SHA256="7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"

MODEL_SHA256="c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
TOKENS_SHA256="f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"

mkdir -p "${CACHE_DIR}" "${MODEL_DIR}" "${TEST_WAV_DIR}"
ARCHIVE_PATH="${CACHE_DIR}/${ARCHIVE_NAME}"

verify_sha256() {
    local file="$1" expected="$2"
    local actual
    actual="$(shasum -a 256 "${file}" | awk '{print $1}')"
    if [[ "${actual}" != "${expected}" ]]; then
        echo "SHA-256 校验失败: ${file}" >&2
        echo "  期望: ${expected}" >&2
        echo "  实际: ${actual}" >&2
        exit 1
    fi
}

extract_file() {
    local member="$1" dest="$2"
    tar xjf "${ARCHIVE_PATH}" -C "${CACHE_DIR}" "${ARCHIVE_NAME%.tar.bz2}/${member}"
    mv "${CACHE_DIR}/${ARCHIVE_NAME%.tar.bz2}/${member}" "${dest}"
}

if [[ ! -f "${ARCHIVE_PATH}" ]] || [[ "$(shasum -a 256 "${ARCHIVE_PATH}" | awk '{print $1}')" != "${ARCHIVE_SHA256}" ]]; then
    echo "下载模型归档: ${ARCHIVE_URL}"
    rm -f "${ARCHIVE_PATH}"
    curl -fL --retry 3 --retry-delay 2 -C - -o "${ARCHIVE_PATH}" "${ARCHIVE_URL}" \
        || curl -fL --retry 3 --retry-delay 2 -C - -o "${ARCHIVE_PATH}" "${MIRROR_URL}"
    verify_sha256 "${ARCHIVE_PATH}" "${ARCHIVE_SHA256}"
else
    echo "归档已存在且校验通过,跳过下载"
fi

echo "提取模型文件…"
extract_file "model.int8.onnx" "${MODEL_DIR}/model.int8.onnx"
extract_file "tokens.txt" "${MODEL_DIR}/tokens.txt"
verify_sha256 "${MODEL_DIR}/model.int8.onnx" "${MODEL_SHA256}"
verify_sha256 "${MODEL_DIR}/tokens.txt" "${TOKENS_SHA256}"

echo "提取固定测试样本(zh/en)…"
extract_file "test_wavs/zh.wav" "${TEST_WAV_DIR}/zh.wav"
extract_file "test_wavs/en.wav" "${TEST_WAV_DIR}/en.wav"

echo "完成。模型已就绪:"
ls -la "${MODEL_DIR}" "${TEST_WAV_DIR}"
