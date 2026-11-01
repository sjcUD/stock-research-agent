#!/usr/bin/env bash
# Compose操作只作用于显式项目名，停止保留卷；不读取或输出.env中的密钥。
set -euo pipefail
stock_project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stock_project_name="${STOCK_AGENT_COMPOSE_PROJECT:-stock-research-agent}"
stock_env_file="${STOCK_AGENT_ENV_FILE:-$stock_project_root/.env}"
if [[ ! "$stock_project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "项目名只能包含小写字母、数字、下划线和连字符" >&2; exit 2
fi
usage() {
  echo "用法: ./scripts/dev.sh start|check|stop|clean|help"
  echo "start 构建并等待就绪；check 检查；stop 停止并保留数据"
  echo "clean 必须同时传 --delete-data --confirm=${stock_project_name}，将删除本项目卷"
}
stock_action="${1:-help}"
if [[ "$stock_action" == help ]]; then usage; exit 0; fi
case "$stock_action" in start|check|stop|clean) ;; *) usage >&2;exit 2;; esac
# 删除数据需要明确指定项目名称，默认调用不会触发任何Docker变更。
if [[ "$stock_action" == clean ]] && [[ "${2:-}" != --delete-data || "${3:-}" != "--confirm=$stock_project_name" ]]; then
  usage >&2; exit 2
fi
command -v docker >/dev/null || { echo "请先安装并启动Docker/OrbStack" >&2;exit 1; }
docker compose version >/dev/null
if [[ ! -f "$stock_env_file" ]]; then
  if [[ "$stock_action" == start ]]; then
    echo "找不到本地配置文件。请从.env.example复制为.env并配置模型。" >&2;exit 1
  fi
  # 停止/状态查询不要求保留密钥文件；不重建服务时可安全使用无密钥模板解析结构。
  stock_env_file="$stock_project_root/.env.example"
fi
compose() { docker compose --project-name "$stock_project_name" --env-file "$stock_env_file" -f "$stock_project_root/compose.yaml" "$@"; }
cd "$stock_project_root"
case "$stock_action" in
  start)
    compose config --quiet
    compose up --detach --build --wait --wait-timeout 240
    compose ps
    echo "访问地址: http://$(compose port app 8080)"
    ;;
  check)
    compose ps
    compose exec -T app java -Xmx32m -cp /app/probe io.github.sjcud.stockagent.deployment.HealthProbe http://127.0.0.1:8080/actuator/health/readiness
    ;;
  stop) compose down ;;
  clean) compose down --volumes ;;
esac
