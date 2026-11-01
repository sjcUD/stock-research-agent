#!/usr/bin/env bash
# P11复现入口：凭据由调用方加载，脚本不读取/打印.env，不自动运行付费测试。
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-offline}"
shift || true
case "$mode" in
  offline)
    # 可追加-Dstock.eval.dataset=/path/to/public.json；输出均在忽略目录。
    mvn -pl stock-agent-evaluation -am test -Dtest=P11OfflineAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false \
      "-Dstock.eval.report=$PWD/evaluation-reports" "$@"
    ;;
  live)
    # 用户显式调用live后才付费：供应商配置由环境变量注入。
    P11_INTEGRATION=true mvn -pl stock-agent-web -am test -Dtest=P11CompletionLiveIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false "-Dstock.eval.report=$PWD/evaluation-reports" "$@"
    ;;
  blind)
    if [[ $# -ne 2 ]]; then echo '用法: scripts/evaluate.sh blind 机器报告.json 评审1.csv,评审2.csv' >&2; exit 2; fi
    mvn -pl stock-agent-evaluation -am test -Dtest=BlindReviewReportTest -Dsurefire.failIfNoSpecifiedTests=false \
      "-Dstock.eval.machine=$1" "-Dstock.eval.reviews=$2" "-Dstock.eval.review.output=$PWD/evaluation-reports/human-machine-comparison.json"
    ;;
  *) echo '用法: scripts/evaluate.sh {offline|live|blind}' >&2; exit 2 ;;
esac
