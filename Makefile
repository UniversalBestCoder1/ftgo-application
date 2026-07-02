# ──────────────────────────────────────────────────────────────────────────────
# FTGO — E2E Test Makefile
# ──────────────────────────────────────────────────────────────────────────────
#
# 快速上手:
#   make e2e              # 完整流程: 启动服务 → 等待健康 → 跑测试 → 保存结果 → 停止
#   make e2e-test         # 仅跑测试 (服务需已启动)
#   make e2e-up           # 启动所有服务
#   make e2e-wait         # 等待所有容器健康
#   make e2e-results      # 打印最新测试报告路径
#   make e2e-down         # 停止并清除所有卷
#   make e2e-logs         # 跟踪服务日志
#   make help             # 显示此帮助
#
# 前置条件:
#   1. Docker & Docker Compose 已安装
#   2. 服务镜像已构建: make e2e-build  (编译 jar + docker build, 首次必须)
#   3. 根目录存在 .env 文件 (已在版本库中)
# ──────────────────────────────────────────────────────────────────────────────

SHELL         := /bin/bash
.SHELLFLAGS   := -euo pipefail -c
.DEFAULT_GOAL := help

# ── 变量 ──────────────────────────────────────────────────────────────────────
DC            := docker compose
ENV_FILE      := .env
DC_FULL       := $(DC) --env-file $(ENV_FILE) -f docker-compose.yml
GRADLE        := ./gradlew
E2E_MODULE    := :ftgo-end-to-end-tests
REPORT_ROOT   := test-reports/e2e

# E2E 需要构建的应用服务 (排除 kafka-gui/zipkin 等预构建镜像)
# dynamodblocal-init 已修复 Python 2.7→3，必须包含
E2E_BUILD_SERVICES := ftgo-consumer-service ftgo-order-service ftgo-kitchen-service \
                      ftgo-restaurant-service ftgo-accounting-service ftgo-delivery-service \
                      ftgo-order-history-service ftgo-api-gateway \
                      dynamodblocal dynamodblocal-init

# E2E 运行所需的服务 (排除 kafka-gui/zipkin 等纯观测工具, 避免拉取外部镜像超时)
E2E_UP_SERVICES := zookeeper kafka mysql cdc-service \
                   dynamodblocal dynamodblocal-init \
                   ftgo-consumer-service ftgo-order-service ftgo-kitchen-service \
                   ftgo-restaurant-service ftgo-accounting-service ftgo-delivery-service \
                   ftgo-order-history-service ftgo-api-gateway

# 等待服务健康的超时 (秒)
WAIT_TIMEOUT  ?= 360
# E2E 测试所需最少健康容器数 (基础设施4 + 应用服务8 = 12, 保守取8)
HEALTHY_MIN   ?= 8

# ── 帮助 ──────────────────────────────────────────────────────────────────────
.PHONY: help
help:
	@echo ""
	@echo "  FTGO E2E 测试命令"
	@echo "  ──────────────────────────────────────────"
	@grep -E '^## ' Makefile | sed 's/^## /  /'
	@echo ""

# ── 主流程 ────────────────────────────────────────────────────────────────────

## e2e               完整流程 (up → wait → test → results → down)
.PHONY: e2e
e2e:
	@$(MAKE) -s e2e-up
	@$(MAKE) -s e2e-wait
	@$(MAKE) e2e-test; EXIT=$$?; \
		$(MAKE) -s e2e-down; \
		exit $$EXIT

# ── 子目标 ────────────────────────────────────────────────────────────────────

## e2e-build         编译所有服务 bootJar，再构建应用 Docker 镜像
## e2e-build         跳过 generateClientStubs + dynamodblocal-init (python2.7 EOL, E2E 不需要)
.PHONY: e2e-build
e2e-build:
	$(GRADLE) bootJar -x generateClientStubs -x test
	$(DC_FULL) build $(E2E_BUILD_SERVICES)

## e2e-up            用 docker-compose.yml 启动 E2E 所需服务 (后台运行)
.PHONY: e2e-up
e2e-up:
	@echo "▶ 启动服务..."
	$(DC_FULL) up -d --no-build $(E2E_UP_SERVICES)
	@echo "  容器已启动，等待健康检查..."

## e2e-wait          等待服务健康 (最长 WAIT_TIMEOUT 秒)
.PHONY: e2e-wait
e2e-wait:
	@echo "⏳ 等待至少 $(HEALTHY_MIN) 个容器健康 (超时 $(WAIT_TIMEOUT)s)..."
	@elapsed=0; \
	while true; do \
		healthy=$$($(DC_FULL) ps 2>/dev/null | grep -c "(healthy)" || true); \
		echo "  $${elapsed}s — 健康容器: $${healthy}/$(HEALTHY_MIN)"; \
		[ "$$healthy" -ge "$(HEALTHY_MIN)" ] && break; \
		[ "$$elapsed" -ge "$(WAIT_TIMEOUT)" ] && { \
			echo ""; \
			echo "✖ 超时：服务未在 $(WAIT_TIMEOUT)s 内就绪" >&2; \
			$(DC_FULL) ps; \
			exit 1; \
		}; \
		sleep 10; \
		elapsed=$$((elapsed + 10)); \
	done
	@echo "✔ 服务就绪"

## e2e-test          运行 E2E 测试，保存报告到 test-reports/e2e/<timestamp>/
.PHONY: e2e-test
e2e-test:
	@mkdir -p $(REPORT_ROOT)
	@echo "▶ 运行 E2E 测试..."
	@set +e; \
	$(GRADLE) $(E2E_MODULE):cleanTest $(E2E_MODULE):test \
		--info \
		2>&1 | tee $(REPORT_ROOT)/last-run.log; \
	GRADLE_EXIT=$${PIPESTATUS[0]}; \
	$(MAKE) -s _save-results; \
	echo ""; \
	if [ "$$GRADLE_EXIT" -eq 0 ]; then \
		echo "✔ E2E 测试全部通过"; \
	else \
		echo "✖ E2E 测试有失败项，请查看报告" >&2; \
	fi; \
	exit $$GRADLE_EXIT

## e2e-results       打印最新测试报告路径
.PHONY: e2e-results
e2e-results:
	@LATEST=$$(ls -td $(REPORT_ROOT)/20*/ 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then \
		echo "尚无已保存的测试报告 (先运行 make e2e-test)"; \
	else \
		echo ""; \
		echo "  最新报告目录 : $$LATEST"; \
		echo "  HTML 报告    : $${LATEST}html/index.html"; \
		echo "  JUnit XML    : $${LATEST}xml/"; \
		echo "  运行日志     : $(REPORT_ROOT)/last-run.log"; \
	fi

## e2e-down          停止所有容器并清除卷
.PHONY: e2e-down
e2e-down:
	@echo "▶ 停止服务..."
	$(DC_FULL) down -v
	@echo "✔ 已停止"

## e2e-logs          跟踪所有服务日志
.PHONY: e2e-logs
e2e-logs:
	$(DC_FULL) logs --tail=100 -f

## e2e-ps            查看各容器状态
.PHONY: e2e-ps
e2e-ps:
	$(DC_FULL) ps

# ── 内部目标 (不对用户暴露) ──────────────────────────────────────────────────
.PHONY: _save-results
_save-results:
	@STAMP=$$(date +%Y-%m-%d_%H-%M-%S); \
	DEST=$(REPORT_ROOT)/$$STAMP; \
	mkdir -p $$DEST; \
	cp -r ftgo-end-to-end-tests/build/test-results/test  $$DEST/xml  2>/dev/null || true; \
	cp -r ftgo-end-to-end-tests/build/reports/tests/test $$DEST/html 2>/dev/null || true; \
	echo ""; \
	echo "──────────────────────────────────────────"; \
	echo "  测试报告已保存:"; \
	echo "  $$DEST/html/index.html  (HTML 报告)"; \
	echo "  $$DEST/xml/             (JUnit XML)"; \
	echo "──────────────────────────────────────────"
