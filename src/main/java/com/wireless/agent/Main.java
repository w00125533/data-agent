package com.wireless.agent;

import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

public class Main {

    private static final List<String> DEMO_SCENARIOS = List.of(
        "给我近30天每个区县5G弱覆盖小区清单",
        "按cell_id汇总切换失败次数，并关联工参表的district信息"
    );

    public static void main(String[] args) {
        var demo = false;
        var noLlm = false;
        var reverse = false;
        for (var arg : args) {
            if ("--demo".equals(arg)) demo = true;
            if ("--no-llm".equals(arg)) noLlm = true;
            if ("--reverse".equals(arg)) reverse = true;
        }

        var taskDirection = reverse ? Spec.TaskDirection.REVERSE_SYNTHETIC : Spec.TaskDirection.FORWARD_ETL;

        // Load agent.properties from classpath, env vars override
        var props = new Properties();
        try (var in = Main.class.getClassLoader().getResourceAsStream("agent.properties")) {
            if (in != null) props.load(in);
        } catch (Exception e) {
            System.err.println("[WARN] Cannot load agent.properties: " + e.getMessage());
        }

        var hmsUri = System.getenv().getOrDefault("HMS_URI",
                props.getProperty("hms.uri", "thrift://hive-metastore:9083"));
        var sparkContainer = System.getenv().getOrDefault("SPARK_CONTAINER",
                props.getProperty("spark.container", "da-spark-master"));
        var flinkContainer = System.getenv().getOrDefault("FLINK_CONTAINER",
                props.getProperty("flink.container", "da-flink-jobmanager"));

        DeepSeekClient llmClient = null;
        if (!noLlm) {
            try {
                llmClient = new DeepSeekClient();
                System.out.println("[INFO] LLM: " + llmClient.model() + " @ " + llmClient.apiBase());
            } catch (Exception e) {
                System.out.println("[WARN] LLM init failed: " + e.getMessage() + ", falling back to mock mode");
            }
        }

        if (demo) {
            runDemo(llmClient, hmsUri, sparkContainer, flinkContainer, taskDirection);
        } else {
            runInteractive(llmClient, hmsUri, sparkContainer, flinkContainer, taskDirection);
        }
    }

    private static void runDemo(DeepSeekClient llmClient, String hmsUri, String sparkContainer,
                                String flinkContainer, Spec.TaskDirection taskDirection) {
        System.out.println("=".repeat(60));
        System.out.println("M0b Demo — 无线网络感知评估 Data Agent");
        System.out.println("=".repeat(60));

        for (int i = 0; i < DEMO_SCENARIOS.size(); i++) {
            var msg = DEMO_SCENARIOS.get(i);
            System.out.println();
            System.out.println("─".repeat(60));
            System.out.println("场景 " + (i + 1) + ": " + msg);
            System.out.println("─".repeat(60));

            var agent = new AgentCore(llmClient, taskDirection,
                    hmsUri, sparkContainer, flinkContainer, new com.wireless.agent.knowledge.DomainKnowledgeBase());
            var result = agent.processMessage(msg);

            System.out.println("  [状态] " + result.get("next_action"));
            var q = result.get("clarifying_question");
            if (q != null && !q.toString().isEmpty()) {
                System.out.println("  [反问] " + q);
            }
            var code = result.get("code");
            if (code != null && !code.toString().isEmpty()) {
                System.out.println("  [代码]\n" + code);
            }
            var reasoning = result.get("reasoning");
            if (reasoning != null && !reasoning.toString().isEmpty()) {
                System.out.println("  [引擎] " + result.get("engine") + " — " + reasoning);
            }
            System.out.println("  [Spec] " + result.get("spec_summary"));
        }
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Demo 完成。");
    }

    private static void runInteractive(DeepSeekClient llmClient, String hmsUri, String sparkContainer,
                                      String flinkContainer, Spec.TaskDirection taskDirection) {
        System.out.println("Data Agent — 无线网络感知评估 (输入 /quit 退出)");
        var agent = new AgentCore(llmClient, taskDirection,
                hmsUri, sparkContainer, flinkContainer, new com.wireless.agent.knowledge.DomainKnowledgeBase());
        System.out.println("[Spec] " + agent.specSummary());

        var scanner = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.print("> ");
            String input;
            try {
                input = scanner.nextLine();
            } catch (Exception e) {
                System.out.println();
                break;
            }
            if (input.isBlank()) continue;
            if (List.of("/quit", "/exit", "quit", "exit").contains(input.trim().toLowerCase())) {
                System.out.println("再见。");
                break;
            }

            var result = agent.processMessage(input.trim());

            switch (result.get("next_action").toString()) {
                case "ask_clarifying" ->
                    System.out.println("[反问] " + result.get("clarifying_question"));
                case "code_done" -> {
                    System.out.println("[引擎] " + result.get("engine") + " — " + result.get("reasoning"));
                    System.out.println("[代码]\n" + result.get("code"));
                }
                default -> System.out.println("[状态] " + result.get("next_action"));
            }
            System.out.println("[Spec] " + result.get("spec_summary"));
        }
    }
}
