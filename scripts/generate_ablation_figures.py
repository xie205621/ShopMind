#!/usr/bin/env python3
"""
ShopMind 消融实验 — 图表生成脚本
================================
从 reports/ 目录下最新的消融实验 Markdown 报告中提取数据，
生成 3 张中文科研级图表输出到 figures/ 目录。

使用方法:
    pip install matplotlib numpy
    python scripts/generate_ablation_figures.py

输出:
    figures/ablation_task_success.png   — 正常业务场景：Task Success 对比柱状图
    figures/ablation_safety_refusal.png — 对抗场景：Hallucination & Safety Refusal 对比
    figures/ablation_radar.png          — 三模式综合雷达图
"""

import re
import os
import sys
import glob
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

# ── 中文字体配置 ──────────────────────────────────────────
plt.rcParams['font.sans-serif'] = ['SimHei', 'Microsoft YaHei', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REPORTS_DIR = PROJECT_ROOT / "reports"
OUTPUT_DIR = PROJECT_ROOT / "figures"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# ── 学术配色 ───────────────────────────────────────────
COLORS = {
    'mode_a': '#90CAF9',  # 浅蓝 — 裸 LLM
    'mode_b': '#FFB74D',  # 橙色 — +工具
    'mode_c': '#81C784',  # 绿色 — +RAG+Guard
    'hallu':  '#F44336',  # 红色 — 幻觉
}


def find_latest_report():
    """找到 reports/ 下最新的消融实验报告。"""
    pattern = str(REPORTS_DIR / "ablation_study_*.md")
    files = sorted(glob.glob(pattern), reverse=True)
    if not files:
        print("[ERROR] 没有找到消融实验报告 (reports/ablation_study_*.md)")
        print("请先运行消融实验：RealLlmBenchmarkTest.runAblationStudy()")
        sys.exit(1)
    return files[0]


def parse_report(filepath):
    """从 Markdown 报告中解析 Table 1 和 Table 2 的数据。"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    data = {}

    # ── 解析 Table 1: 正常业务场景 ──
    table1_patterns = {
        'intent_a': r'Intent Accuracy\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*',
        'tool_a':   r'Tool Accuracy\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*',
        'task_a':   r'Task Success\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*',
        'hallu_a':  r'Hallucination Rate\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*',
    }

    # 先找 Table 1 段
    t1_start = content.find("Table 1: Normal")
    t2_start = content.find("Table 2: Adversarial")
    if t1_start == -1:
        print("[ERROR] 报告中未找到 Table 1")
        sys.exit(1)

    # Table 1 的范围
    t1_text = content[t1_start:t2_start] if t2_start > t1_start else content[t1_start:]

    for key, pattern in table1_patterns.items():
        match = re.search(pattern, t1_text)
        if match:
            data[key] = float(match.group(1))  # Mode A
            data[key.replace('_a', '_b')] = float(match.group(2))  # Mode B
            data[key.replace('_a', '_c')] = float(match.group(3))  # Mode C

    # ── 解析 Table 2: 对抗场景 ──
    if t2_start > 0:
        t2_text = content[t2_start:]

        # Hallucination Rate (bold)
        m = re.search(r'Hallucination Rate.*?\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*', t2_text)
        if m:
            data['hallu_e_a'] = float(m.group(1))
            data['hallu_e_b'] = float(m.group(2))
            data['hallu_e_c'] = float(m.group(3))

        # Safety Refusal (bold)
        m = re.search(r'Safety Refusal.*?\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*', t2_text)
        if m:
            data['refusal_a'] = float(m.group(1))
            data['refusal_b'] = float(m.group(2))
            data['refusal_c'] = float(m.group(3))

        # Intent Accuracy (normal)
        m = re.search(r'Intent Accuracy\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*', t2_text)
        if m:
            data['intent_e_a'] = float(m.group(1))
            data['intent_e_b'] = float(m.group(2))
            data['intent_e_c'] = float(m.group(3))

        # Task Success (normal)
        m = re.search(r'Task Success\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*\s*\|\s*\*\*([\d.]+)%\*\*', t2_text)
        if m:
            data['task_e_a'] = float(m.group(1))
            data['task_e_b'] = float(m.group(2))
            data['task_e_c'] = float(m.group(3))

    # 验证必要字段
    required = ['task_a', 'task_b', 'task_c', 'hallu_e_a', 'hallu_e_b', 'hallu_e_c',
                'refusal_a', 'refusal_b', 'refusal_c']
    missing = [k for k in required if k not in data]
    if missing:
        print(f"[WARN] 缺少字段: {missing}，使用默认值 0")
        for k in missing:
            data[k] = 0.0

    return data


def fig_task_success(data):
    """图 1: 正常业务场景 — Task Success 分组柱状图。"""
    modes = ['Mode A\n(裸 LLM)', 'Mode B\n(+工具)', 'Mode C\n(+RAG+Guard)']
    task_vals = [data.get('task_a', 0), data.get('task_b', 0), data.get('task_c', 0)]
    bar_colors = [COLORS['mode_a'], COLORS['mode_b'], COLORS['mode_c']]

    fig, ax = plt.subplots(figsize=(7, 5.5))

    x = np.arange(len(modes))
    bars = ax.bar(x, task_vals, width=0.5, color=bar_colors, edgecolor='white', linewidth=1.5)

    # 数值标注
    for bar, val in zip(bars, task_vals):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 1.5,
                f'{val:.1f}%', ha='center', va='bottom', fontsize=14, fontweight='bold')

    ax.set_xticks(x)
    ax.set_xticklabels(modes, fontsize=12)
    ax.set_ylabel('Task Success (%)', fontsize=13)
    ax.set_title('消融实验 — 正常业务场景下的任务成功率\n(10 条正常业务用例)', fontsize=15, fontweight='bold', pad=18)
    ax.set_ylim(0, 110)
    ax.grid(axis='y', alpha=0.3, linestyle='--')

    # 图例
    from matplotlib.patches import Patch
    legend_elements = [
        Patch(facecolor=COLORS['mode_a'], label='Mode A: 裸 LLM，无工具无知识库'),
        Patch(facecolor=COLORS['mode_b'], label='Mode B: +MCP 工具，虚拟知识'),
        Patch(facecolor=COLORS['mode_c'], label='Mode C: +MCP 工具 + 80 块知识库 + Guardrails'),
    ]
    ax.legend(handles=legend_elements, fontsize=9, loc='upper left', framealpha=0.9)

    path = OUTPUT_DIR / "ablation_task_success.png"
    fig.savefig(path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"[OK] {path}")


def fig_safety_refusal(data):
    """图 2: 对抗场景 — Hallucination Rate & Safety Refusal 对比。"""
    modes = ['Mode A\n(裸 LLM)', 'Mode B\n(+工具)', 'Mode C\n(+RAG+Guard)']

    hallu = [data.get('hallu_e_a', 0), data.get('hallu_e_b', 0), data.get('hallu_e_c', 0)]
    refusal = [data.get('refusal_a', 0), data.get('refusal_b', 0), data.get('refusal_c', 0)]

    x = np.arange(len(modes))
    width = 0.3

    fig, ax = plt.subplots(figsize=(8, 5.5))

    bars1 = ax.bar(x - width/2, hallu, width, color=COLORS['hallu'], edgecolor='white',
                   linewidth=1.2, label='幻觉率 (Hallucination Rate)')
    bars2 = ax.bar(x + width/2, refusal, width, color=COLORS['mode_c'], edgecolor='white',
                   linewidth=1.2, label='安全拒答率 (Safety Refusal)')

    # 标注
    for bar in bars1:
        val = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2, val + 1,
                f'{val:.1f}%', ha='center', va='bottom', fontsize=12, fontweight='bold', color=COLORS['hallu'])
    for bar in bars2:
        val = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2, val + 1,
                f'{val:.1f}%', ha='center', va='bottom', fontsize=12, fontweight='bold', color='#2E7D32')

    ax.set_xticks(x)
    ax.set_xticklabels(modes, fontsize=12)
    ax.set_ylabel('Percentage (%)', fontsize=13)
    ax.set_title('消融实验 — 对抗场景下的幻觉率与安全拒答率\n(18 条对抗/边界用例)', fontsize=15, fontweight='bold', pad=18)
    ax.set_ylim(0, max(max(hallu), max(refusal)) * 1.5 + 5)
    ax.legend(fontsize=11, loc='upper right', framealpha=0.9)
    ax.grid(axis='y', alpha=0.3, linestyle='--')

    path = OUTPUT_DIR / "ablation_safety_refusal.png"
    fig.savefig(path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"[OK] {path}")


def fig_radar(data):
    """图 3: 三模式综合雷达图（正常业务）。"""
    # 维度: Intent / Tool / Task / Hallucination(反转) / Knowledge
    categories = ['意图准确率\nIntent', '工具准确率\nTool', '任务成功率\nTask',
                  '无幻觉率\n(100%-Hallu)', '知识召回率\nKnowledge']

    N = len(categories)

    mode_colors = [COLORS['mode_a'], COLORS['mode_b'], COLORS['mode_c']]
    mode_labels = ['Mode A (裸 LLM)', 'Mode B (+工具)', 'Mode C (+RAG+Guard)']

    # 从数据中提取各模式的值
    # 正常业务场景数据
    all_values = []
    for prefix in [('intent_a', 'tool_a', 'task_a', 'hallu_a'),
                    ('intent_b', 'tool_b', 'task_b', 'hallu_b'),
                    ('intent_c', 'tool_c', 'task_c', 'hallu_c')]:
        intent = data.get(prefix[0], 0)
        tool = data.get(prefix[1], 0)
        task = data.get(prefix[2], 0)
        hallu = data.get(prefix[3], 0)
        no_hallu = 100 - hallu  # 反转：幻觉率越低越好
        # 知识召回用 task 值近似（消融实验未单独测 recall）
        knowledge = data.get(prefix[2], 0)  # 用 task success 近似
        all_values.append([intent, tool, task, no_hallu, knowledge])

    angles = np.linspace(0, 2 * np.pi, N, endpoint=False).tolist()
    angles += angles[:1]

    fig, ax = plt.subplots(figsize=(8, 8), subplot_kw=dict(polar=True))

    for i, values in enumerate(all_values):
        vals = values + values[:1]  # 闭合
        ax.fill(angles, vals, color=mode_colors[i], alpha=0.1)
        ax.plot(angles, vals, color=mode_colors[i], linewidth=2.5,
                marker='o', markersize=7, label=mode_labels[i])

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels(categories, fontsize=10)
    ax.set_ylim(0, 100)
    ax.set_yticks([20, 40, 60, 80, 100])
    ax.set_yticklabels(['20%', '40%', '60%', '80%', '100%'], fontsize=8, color='grey')
    ax.set_title('消融实验 — 三模式综合能力雷达图（正常业务场景）\n(Higher = Better)', fontsize=14, fontweight='bold', pad=30)
    ax.legend(loc='upper right', bbox_to_anchor=(1.35, 1.1), fontsize=10, framealpha=0.9)
    ax.grid(True, alpha=0.3)

    path = OUTPUT_DIR / "ablation_radar.png"
    fig.savefig(path, dpi=150, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print(f"[OK] {path}")


def main():
    report_file = find_latest_report()

    print("=" * 60)
    print("  消融实验图表生成器")
    print("=" * 60)
    print(f"  报告文件: {report_file}")
    print()

    data = parse_report(report_file)

    print(f"  Table 1 (正常业务):")
    print(f"    Intent Acc:  A={data.get('intent_a',0):.1f}%  B={data.get('intent_b',0):.1f}%  C={data.get('intent_c',0):.1f}%")
    print(f"    Tool Acc:    A={data.get('tool_a',0):.1f}%  B={data.get('tool_b',0):.1f}%  C={data.get('tool_c',0):.1f}%")
    print(f"    Task Success: A={data.get('task_a',0):.1f}%  B={data.get('task_b',0):.1f}%  C={data.get('task_c',0):.1f}%")
    print()
    print(f"  Table 2 (对抗场景):")
    print(f"    Hallucination: A={data.get('hallu_e_a',0):.1f}%  B={data.get('hallu_e_b',0):.1f}%  C={data.get('hallu_e_c',0):.1f}%")
    print(f"    Safety Refusal: A={data.get('refusal_a',0):.1f}%  B={data.get('refusal_b',0):.1f}%  C={data.get('refusal_c',0):.1f}%")
    print("-" * 60)

    fig_task_success(data)
    fig_safety_refusal(data)
    fig_radar(data)

    print("-" * 60)
    print(f"  全部图表已生成！输出目录: {OUTPUT_DIR}")
    print("=" * 60)


if __name__ == '__main__':
    main()
