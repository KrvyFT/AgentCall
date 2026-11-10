# AgentCall 构建 / 测试 / 打包入口（Task 9 Part A）。
#
# 不下载任何二进制：Gradle 依赖走现有仓库配置（含机器级镜像），
# Android 工具只取本机 SDK build-tools（aapt2 / zipalign / apksigner），
# Node/Python 使用机器上已有解释器。Gradle 调用请按仓库约定用
# flock /tmp/agentcall-gradle.lock 串行化（见 docs/verification.md）。
#
# 目标：
#   make test            JVM 单测 + WebUI node 测试 + Python 打包测试 + shell 语法检查
#   make build           app release + bridge 运行时容器 + dist 打包
#   make package-check   校验 dist 结构、DEX、版本、签名、密钥泄漏与 SHA-256
#   make lint            :app:lintDebug（完整验收矩阵的一部分，不包含在 test 内）

SHELL := /bin/bash
PYTHON ?= python3
NODE ?= node
GRADLE ?= ./gradlew
DIST ?= dist
GRADLE_FLAGS ?= --console=plain

.PHONY: test test-jvm test-js test-python test-scripts lint build build-app build-bridge package package-check clean-dist

test: test-jvm test-js test-python test-scripts

test-jvm:
	$(GRADLE) :core:testDebugUnitTest :bridge:testDebugUnitTest :app:testDebugUnitTest $(GRADLE_FLAGS)

test-js:
	$(NODE) --test module/webroot/tests/*.test.mjs

test-python:
	$(PYTHON) -m unittest discover -s tools/tests -v
	$(PYTHON) -m py_compile tools/package.py tools/check_package.py

test-scripts:
	sh -n module/customize.sh module/service.sh module/uninstall.sh module/bin/agentcallctl

lint:
	$(GRADLE) :app:lintDebug $(GRADLE_FLAGS)

build: build-app build-bridge package

build-app:
	$(GRADLE) :app:assembleRelease $(GRADLE_FLAGS)

build-bridge:
	$(GRADLE) :bridge:packageRuntime $(GRADLE_FLAGS)

package:
	$(PYTHON) tools/package.py --dist $(DIST)

package-check:
	$(PYTHON) tools/check_package.py --dist $(DIST)

clean-dist:
	rm -rf $(DIST)
