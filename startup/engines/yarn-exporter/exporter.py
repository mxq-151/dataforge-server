#!/usr/bin/env python3
"""最小 YARN RM/NM REST → Prometheus 导出器（仅标准库）。

YARN 3.3.x 的 RM/NM Web 服务只提供 JSON，没有 Prometheus 端点；
本进程把 RM /ws/v1/cluster/metrics 与 NM /ws/v1/node/info 转成文本指标，
供 VictoriaMetrics 以普通 scrape job 抓取。

环境变量：
  YARN_RM_URL  默认 http://dataforge-allinone:8088
  YARN_NM_URL  默认 http://dataforge-allinone:8042
  LISTEN_PORT  默认 18090
"""
import json
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RM_URL = os.environ.get("YARN_RM_URL", "http://dataforge-allinone:8088").rstrip("/")
NM_URL = os.environ.get("YARN_NM_URL", "http://dataforge-allinone:8042").rstrip("/")
PORT = int(os.environ.get("LISTEN_PORT", "18090"))

RM_FIELDS = {
    "appsSubmitted": ("yarn_cluster_apps_submitted_total", "counter"),
    "appsCompleted": ("yarn_cluster_apps_completed_total", "counter"),
    "appsPending": ("yarn_cluster_apps_pending", "gauge"),
    "appsRunning": ("yarn_cluster_apps_running", "gauge"),
    "appsFailed": ("yarn_cluster_apps_failed_total", "counter"),
    "appsKilled": ("yarn_cluster_apps_killed_total", "counter"),
    "availableMB": ("yarn_cluster_memory_available_bytes", "gauge"),
    "allocatedMB": ("yarn_cluster_memory_allocated_bytes", "gauge"),
    "totalMB": ("yarn_cluster_memory_total_bytes", "gauge"),
    "pendingMB": ("yarn_cluster_memory_pending_bytes", "gauge"),
    "availableVirtualCores": ("yarn_cluster_vcores_available", "gauge"),
    "allocatedVirtualCores": ("yarn_cluster_vcores_allocated", "gauge"),
    "totalVirtualCores": ("yarn_cluster_vcores_total", "gauge"),
    "containersAllocated": ("yarn_cluster_containers_allocated", "gauge"),
    "containersPending": ("yarn_cluster_containers_pending", "gauge"),
    "containersReserved": ("yarn_cluster_containers_reserved", "gauge"),
    "activeNodes": ("yarn_cluster_nodes_active", "gauge"),
    "lostNodes": ("yarn_cluster_nodes_lost", "gauge"),
    "unhealthyNodes": ("yarn_cluster_nodes_unhealthy", "gauge"),
    "totalNodes": ("yarn_cluster_nodes_total", "gauge"),
    "rebootedNodes": ("yarn_cluster_nodes_rebooted", "gauge"),
    "utilizedMBPercent": ("yarn_cluster_memory_utilized_percent", "gauge"),
}


MB_FIELDS = {"availableMB", "allocatedMB", "totalMB", "pendingMB"}


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=5) as resp:
        return json.load(resp)


def render():
    lines = []
    try:
        metrics = fetch_json(RM_URL + "/ws/v1/cluster/metrics")["clusterMetrics"]
        for src, (name, kind) in RM_FIELDS.items():
            if src in metrics:
                value = metrics[src]
                if src in MB_FIELDS:
                    value = int(value) * 1024 * 1024
                lines.append("# TYPE %s %s" % (name, kind))
                lines.append("%s %s" % (name, value))
    except Exception as exc:  # 抓取失败时显式置 0，避免沿用陈旧值
        lines.append("# yarn rm scrape error: %s" % exc)
        lines.append("yarn_cluster_up 0")
    else:
        lines.append("yarn_cluster_up 1")

    try:
        info = fetch_json(NM_URL + "/ws/v1/node/info")["nodeInfo"]
        host = info.get("nodeHostName") or "unknown"
        lines.append("yarn_nodemanager_up{host=\"%s\"} 1" % host)
        lines.append("yarn_nodemanager_healthy{host=\"%s\"} %d"
                     % (host, 1 if info.get("nodeHealthy") else 0))
        lines.append("yarn_nodemanager_memory_allocated_bytes{host=\"%s\"} %s"
                     % (host, int(info.get("totalPmemAllocatedContainersMB", 0)) * 1024 * 1024))
        lines.append("yarn_nodemanager_vcores_allocated{host=\"%s\"} %s"
                     % (host, info.get("totalVCoresAllocatedContainers", 0)))
    except Exception as exc:
        lines.append("# yarn nm scrape error: %s" % exc)
        lines.append("yarn_nodemanager_up 0")

    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/metrics"):
            body = render().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"yarn-exporter: GET /metrics\n")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
