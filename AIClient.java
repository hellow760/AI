import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * AI 命令行客户端，支持读取本地文件并让 AI 提取关键词。
 */
public class AIClient {

    private static HttpClient httpClient;
    private static ObjectMapper objectMapper;
    private static String apiUrl;
    private static String apiKey;
    private static String model;
    private static int maxTokens;
    private static double temperature;
    private static String systemPrompt;

    // 对话历史
    private static List<Map<String, String>> conversationHistory;

    // 文件读取最大字符数（避免 token 超限）
    private static final int MAX_FILE_CHARS = 8000;

    public static void main(String[] args) {
        try {
            loadConfig();
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            objectMapper = new ObjectMapper();
            initConversationHistory();
            startConsole();
        } catch (Exception e) {
            System.err.println("初始化失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** 加载配置（同原逻辑） */
    private static void loadConfig() throws IOException {
        Properties props = new Properties();
        try (InputStream input = AIClient.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                props.load(input);
                System.out.println("已加载配置文件 config.properties");
            } else {
                System.out.println("未找到 config.properties，将使用环境变量或默认值");
            }
        }

        apiUrl = System.getenv("AI_API_URL");
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = props.getProperty("api.url", "https://api.openai.com/v1/chat/completions");
        }

        apiKey = System.getenv("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = props.getProperty("api.key");
        }

        model = System.getenv("AI_MODEL");
        if (model == null || model.isBlank()) {
            model = props.getProperty("api.model", "gpt-3.5-turbo");
        }

        String maxTokensStr = System.getenv("AI_MAX_TOKENS");
        if (maxTokensStr == null || maxTokensStr.isBlank()) {
            maxTokensStr = props.getProperty("max.tokens", "1000");
        }
        maxTokens = Integer.parseInt(maxTokensStr);

        String tempStr = System.getenv("AI_TEMPERATURE");
        if (tempStr == null || tempStr.isBlank()) {
            tempStr = props.getProperty("temperature", "0.7");
        }
        temperature = Double.parseDouble(tempStr);

        systemPrompt = System.getenv("AI_SYSTEM_PROMPT");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = props.getProperty("system.prompt");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 API Key，请设置环境变量 AI_API_KEY 或在 config.properties 中填写 api.key");
        }

        System.out.println("=== 当前配置 ===");
        System.out.println("API 地址: " + apiUrl);
        System.out.println("模型: " + model);
        System.out.println("Max Tokens: " + maxTokens);
        System.out.println("Temperature: " + temperature);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            System.out.println("系统提示: " + systemPrompt);
        }
        System.out.println("================");
    }

    private static void initConversationHistory() {
        conversationHistory = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            conversationHistory.add(sysMsg);
        }
    }

    private static void addMessage(String role, String content) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        conversationHistory.add(msg);
    }

    private static void clearHistory() {
        conversationHistory.clear();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            conversationHistory.add(sysMsg);
        }
        System.out.println("对话历史已清空。");
    }

    /**
     * 读取文本文件内容，返回字符串（自动处理 UTF-8，若文件过大则截断）
     * @param filePath 文件路径
     * @return 文件内容（可能被截断并附加提示）
     * @throws IOException 文件读取错误
     */
    private static String readFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + filePath);
        }
        String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        if (content.length() > MAX_FILE_CHARS) {
            content = content.substring(0, MAX_FILE_CHARS) + 
                    "\n... (文件过长，已截断至前" + MAX_FILE_CHARS + "个字符)";
        }
        return content;
    }

    /** 调用 AI，发送消息并获取回复（自动加入历史） */
    private static String callAI(String userMessage) throws IOException, InterruptedException {
        addMessage("user", userMessage);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);
        root.put("stream", false);

        ArrayNode messages = objectMapper.createArrayNode();
        for (Map<String, String> msg : conversationHistory) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.get("role"));
            msgNode.put("content", msg.get("content"));
            messages.add(msgNode);
        }
        root.set("messages", messages);

        String requestBody = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();

        if (statusCode == 200) {
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            if (jsonNode.has("error")) {
                String errorMsg = jsonNode.get("error").get("message").asText();
                throw new IOException("API 返回错误: " + errorMsg);
            }
            String assistantReply = jsonNode
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();
            if (assistantReply.isBlank()) {
                throw new IOException("API 响应中没有找到有效内容");
            }
            addMessage("assistant", assistantReply);
            return assistantReply;
        } else {
            String errorDetail = "";
            try {
                JsonNode errorNode = objectMapper.readTree(responseBody).path("error");
                if (errorNode.isObject()) {
                    errorDetail = errorNode.path("message").asText();
                }
            } catch (Exception ignored) {}
            throw new IOException("HTTP " + statusCode + " – " + errorDetail + " | " + responseBody);
        }
    }

    /** 处理文件命令：读取文件，构造关键词提取请求，调用 AI 并输出结果 */
    private static void handleFileCommand(String filePath) throws IOException, InterruptedException {
        // 1. 读取文件内容
        String fileContent = readFileContent(filePath);
        System.out.println("📄 文件读取成功，大小：" + fileContent.length() + " 字符");

        // 2. 构造提示词（要求 AI 提取关键词）
        String prompt = String.format(
                "请从以下文本中提取最重要的5～10个关键词（名词或短语），用中文逗号分隔，不要添加额外解释。\n\n文本内容：\n```\n%s\n```",
                fileContent
        );

        // 3. 调用 AI（callAI 内部会自动把 prompt 加入历史并返回回复）
        System.out.print("🤖 AI 正在提取关键词... ");
        String reply = callAI(prompt);
        System.out.println("\n📌 提取到的关键词：\n" + reply);
    }

    /** 命令行交互主循环 */
    private static void startConsole() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("\n✨ AI 对话已启动 ✨");
            System.out.println("命令:");
            System.out.println("  /clear       – 清空对话历史");
            System.out.println("  /file <路径> – 读取文件并让 AI 提取关键词");
            System.out.println("  /exit        – 退出程序");
            System.out.println("直接输入文本可与 AI 正常对话。\n");

            while (true) {
                System.out.print("你: ");
                String input = scanner.nextLine().trim();
                if (input.isBlank()) {
                    continue;
                }

                if (input.equalsIgnoreCase("/exit")) {
                    System.out.println("再见！");
                    break;
                } else if (input.equalsIgnoreCase("/clear")) {
                    clearHistory();
                    continue;
                } else if (input.toLowerCase().startsWith("/file ")) {
                    // 提取文件路径
                    String filePath = input.substring(6).trim();
                    if (filePath.isEmpty()) {
                        System.out.println("用法: /file <文件绝对路径或相对路径>");
                        continue;
                    }
                    try {
                        handleFileCommand(filePath);
                    } catch (IOException e) {
                        System.err.println("文件处理失败: " + e.getMessage());
                        // 如果调用失败，需要移除可能已被 callAI 加入的错误历史（如果有）
                        // 但 handleFileCommand 内部如果失败，callAI 尚未调用或已经回滚？简单处理：不清除历史，因为未成功添加消息
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("操作中断: " + e.getMessage());
                    }
                    continue;
                }

                // 普通对话
                try {
                    System.out.print("AI: ");
                    String reply = callAI(input);
                    System.out.println(reply);
                } catch (Exception e) {
                    // 调用失败时移除刚添加的用户消息
                    if (!conversationHistory.isEmpty() && "user".equals(conversationHistory.get(conversationHistory.size() - 1).get("role"))) {
                        conversationHistory.remove(conversationHistory.size() - 1);
                    }
                    System.err.println("\n调用失败: " + e.getMessage());
                    System.err.println("请检查网络、API 配置或稍后重试。");
                }
            }
        }
    }
}