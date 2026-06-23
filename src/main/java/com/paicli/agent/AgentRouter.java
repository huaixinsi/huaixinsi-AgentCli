package com.paicli.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic router for choosing the default agent execution path.
 *
 * Explicit slash commands still win in the CLI. This router only handles normal
 * user input when no mode has been forced by /plan or /team.
 */
public final class AgentRouter {

    private AgentRouter() {
    }

    public enum ExecutionMode {
        REACT("react", "ReAct"),
        PLAN("plan", "Plan-and-Execute"),
        TEAM("team", "Multi-Agent");

        private final String snapshotMode;
        private final String displayName;

        ExecutionMode(String snapshotMode, String displayName) {
            this.snapshotMode = snapshotMode;
            this.displayName = displayName;
        }

        public String snapshotMode() {
            return snapshotMode;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record RouteDecision(ExecutionMode mode, int score, boolean parallelCandidate, List<String> reasons) {
        public RouteDecision {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }

        public String summary() {
            if (reasons.isEmpty()) {
                return mode.displayName() + " (score=" + score + ")";
            }
            return mode.displayName() + " (score=" + score + ", reasons=" + String.join(", ", reasons) + ")";
        }
    }

    public static RouteDecision route(String input) {
        if (input == null || input.isBlank()) {
            return new RouteDecision(ExecutionMode.REACT, 0, false, List.of());
        }

        String normalized = normalize(input);
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (hasMutationIntent(normalized)) {
            score += 2;
            reasons.add("change");
        }
        if (hasSequentialIntent(normalized)) {
            score += 2;
            reasons.add("multi_step");
        }
        if (hasBroadScope(normalized)) {
            score += 2;
            reasons.add("broad_scope");
        }
        if (hasValidationOrDelivery(normalized)) {
            score += 1;
            reasons.add("verify_or_ship");
        }
        if (looksLongOrStructured(input)) {
            score += 1;
            reasons.add("structured_input");
        }

        boolean parallelCandidate = hasParallelIntent(normalized);
        if (parallelCandidate) {
            score += 2;
            reasons.add("parallel_candidate");
        }

        if (isSimpleQuestion(normalized) && score <= 2) {
            return new RouteDecision(ExecutionMode.REACT, score, parallelCandidate, reasons);
        }

        ExecutionMode mode;
        if (score >= 6 && parallelCandidate) {
            mode = ExecutionMode.TEAM;
        } else if (score >= 3) {
            mode = ExecutionMode.PLAN;
        } else {
            mode = ExecutionMode.REACT;
        }

        return new RouteDecision(mode, score, parallelCandidate, reasons);
    }

    private static String normalize(String input) {
        return input.toLowerCase(Locale.ROOT)
                .replace('\r', '\n')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean hasMutationIntent(String text) {
        return containsAny(text,
                "实现", "新增", "添加", "修改", "改造", "修复", "重构", "删除", "生成", "创建",
                "补充", "完善", "接入", "实现", "push", "commit", "implement", "add ", "modify",
                "fix", "refactor", "create", "update", "write");
    }

    private static boolean hasSequentialIntent(String text) {
        return containsAny(text,
                "先", "然后", "再", "最后", "一步一步", "分步骤", "多步骤", "顺序", "流程",
                "first", "then", "after that", "finally", "step by step");
    }

    private static boolean hasBroadScope(String text) {
        return containsAny(text,
                "项目", "代码库", "模块", "多个文件", "多文件", "全局", "架构", "入口", "链路",
                "project", "codebase", "module", "modules", "multiple files", "architecture", "workflow");
    }

    private static boolean hasValidationOrDelivery(String text) {
        return containsAny(text,
                "测试", "验证", "文档", "提交", "推送", "发布", "部署", "回归", "验收",
                "test", "verify", "docs", "document", "commit", "push", "release", "deploy");
    }

    private static boolean hasParallelIntent(String text) {
        boolean explicitParallel = containsAny(text,
                "同时", "并行", "分别", "各自", "互不依赖", "独立", "多模块", "两个模块",
                "parallel", "at the same time", "independent", "separately");
        boolean multiArea = containsAny(text, " 和 ", " 与 ", "、", " and ")
                && containsAny(text, "模块", "文件", "测试", "文档", "module", "file", "test", "docs");
        return explicitParallel || multiArea;
    }

    private static boolean isSimpleQuestion(String text) {
        return containsAny(text,
                "解释", "说明", "是什么", "为什么", "怎么看", "总结", "阅读", "看一下",
                "explain", "what is", "why", "summarize", "read");
    }

    private static boolean looksLongOrStructured(String input) {
        String normalizedNewlines = input.replace("\r\n", "\n").replace('\r', '\n');
        return normalizedNewlines.split("\n").length >= 3 || input.length() >= 160;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
