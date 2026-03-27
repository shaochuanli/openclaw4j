package ai.openclaw.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SkillHub商店 (https://skillhub.tencent.com) 的HTTP客户端。
 *
 * <p>假设的API约定（参考常见技能市场设计）：
 * <ul>
 *   <li>GET  /api/v1/skills                 → 列出/搜索技能</li>
 *   <li>GET  /api/v1/skills/{id}            → 获取技能详情</li>
 *   <li>GET  /api/v1/skills/{id}/download   → 下载SKILL.md（或zip）</li>
 *   <li>GET  /api/v1/categories             → 列出分类</li>
 *   <li>GET  /api/v1/skills/featured        → 精选技能</li>
 * </ul>
 *
 * <p>所有响应格式为 { "ok": true, "data": ... }
 */
public class SkillHubClient {

    private static final Logger log = LoggerFactory.getLogger(SkillHubClient.class);

    /** 默认商店URL */
    public static final String DEFAULT_STORE_URL = "https://skillhub.tencent.com";

    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final String USER_AGENT = "openclaw4j/1.0.0 SkillHubClient";

    private final String baseUrl;
    private final ObjectMapper mapper;

    // ─── 数据模型 ──────────────────────────────────────────────────────────

    /**
     * 商店列表API返回的技能条目。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoreSkill {
        /** 商店唯一标识符 */
        public String id;
        /** 人类可读名称 */
        public String name;
        /** 简短描述 */
        public String description;
        /** 技能作者/发布者 */
        public String author;
        /** 当前版本 */
        public String version;
        /** 主页或仓库URL */
        public String homepage;
        /** 分类/标签 */
        public List<String> tags;
        /** 下载次数 */
        public long downloads;
        /** 评分（0-5） */
        public double rating;
        /** 评分数 */
        public int ratingCount;
        /** 图标/表情符号 */
        public String emoji;
        /** 支持的操作系统：win32, linux, darwin */
        public List<String> os;
        /** SKILL.md原始文件直接URL */
        public String skillMdUrl;
        /** zip归档直接URL（如果包含脚本） */
        public String zipUrl;
        /** 最后更新时间戳（epoch毫秒） */
        public long updatedAt;
        /** 是否为腾讯官方技能 */
        public boolean official;
    }

    /**
     * 商店搜索的分页列表响应。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoreSkillPage {
        /** 技能列表 */
        public List<StoreSkill> skills = new ArrayList<>();
        /** 总数 */
        public int total;
        /** 当前页码 */
        public int page;
        /** 每页大小 */
        public int pageSize;
        /** 是否有更多 */
        public boolean hasMore;
    }

    /**
     * 商店响应包装类。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoreResponse {
        /** 是否成功 */
        public boolean ok;
        /** 错误信息 */
        public String error;
        /** 数据 */
        public JsonNode data;
    }

    /**
     * 分类条目。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StoreCategory {
        /** 分类ID */
        public String id;
        /** 分类名称 */
        public String name;
        /** 分类描述 */
        public String description;
        /** 技能数量 */
        public int count;
        /** 表情符号 */
        public String emoji;
    }

    // ─── 构造函数 ──────────────────────────────────────────────────────────

    /**
     * 构造函数。
     *
     * @param baseUrl 商店基础URL
     * @param mapper  Jackson ObjectMapper
     */
    public SkillHubClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
    }

    /**
     * 使用默认URL构造客户端。
     *
     * @param mapper Jackson ObjectMapper
     */
    public SkillHubClient(ObjectMapper mapper) {
        this(DEFAULT_STORE_URL, mapper);
    }

    // ─── 公共API ───────────────────────────────────────────────────────────

    /**
     * 从商店搜索技能。
     *
     * @param query    搜索关键词（null表示浏览全部）
     * @param category 按分类过滤（null表示全部）
     * @param tag      按标签过滤（null表示全部）
     * @param page     页码（从1开始）
     * @param pageSize 每页条数（默认20）
     * @return 分页技能列表，出错时返回空页
     */
    public StoreSkillPage searchSkills(String query, String category, String tag, int page, int pageSize) {
        StringBuilder url = new StringBuilder(baseUrl).append("/api/v1/skills?");
        if (query != null && !query.isBlank()) {
            url.append("q=").append(urlEncode(query)).append("&");
        }
        if (category != null && !category.isBlank()) {
            url.append("category=").append(urlEncode(category)).append("&");
        }
        if (tag != null && !tag.isBlank()) {
            url.append("tag=").append(urlEncode(tag)).append("&");
        }
        url.append("page=").append(Math.max(1, page))
           .append("&pageSize=").append(Math.min(100, Math.max(1, pageSize)));

        try {
            JsonNode data = getJson(url.toString());
            if (data == null) return emptyPage();
            return mapper.treeToValue(data, StoreSkillPage.class);
        } catch (Exception e) {
            log.warn("searchSkills failed: {}", e.getMessage());
            return emptyPage();
        }
    }

    /**
     * 便捷方法：仅用关键词搜索。
     *
     * @param query 搜索关键词
     * @return 分页技能列表
     */
    public StoreSkillPage searchSkills(String query) {
        return searchSkills(query, null, null, 1, 20);
    }

    /**
     * 从商店获取精选/推荐技能。
     *
     * @return 精选技能列表
     */
    public List<StoreSkill> getFeaturedSkills() {
        try {
            JsonNode data = getJson(baseUrl + "/api/v1/skills/featured");
            if (data == null) return Collections.emptyList();
            StoreSkillPage page = mapper.treeToValue(data, StoreSkillPage.class);
            return page.skills != null ? page.skills : Collections.emptyList();
        } catch (Exception e) {
            log.warn("getFeaturedSkills failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据商店ID获取技能详情。
     *
     * @param skillId 商店技能ID
     * @return 技能信息（Optional包装）
     */
    public Optional<StoreSkill> getSkill(String skillId) {
        try {
            JsonNode data = getJson(baseUrl + "/api/v1/skills/" + urlEncode(skillId));
            if (data == null) return Optional.empty();
            return Optional.of(mapper.treeToValue(data, StoreSkill.class));
        } catch (Exception e) {
            log.warn("getSkill({}) failed: {}", skillId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 下载技能的SKILL.md原始内容。
     *
     * @param skillMdUrl SKILL.md的直接URL（来自StoreSkill.skillMdUrl）
     * @return SKILL.md原始文本，出错时返回null
     */
    public String downloadSkillMd(String skillMdUrl) {
        try (CloseableHttpClient http = buildHttpClient()) {
            HttpGet get = new HttpGet(skillMdUrl);
            get.setHeader("User-Agent", USER_AGENT);
            return http.execute(get, response -> {
                int status = response.getCode();
                if (status != 200) {
                    log.warn("downloadSkillMd HTTP {}: {}", status, skillMdUrl);
                    return null;
                }
                return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            });
        } catch (Exception e) {
            log.warn("downloadSkillMd failed ({}): {}", skillMdUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 下载技能zip归档到字节数组。
     *
     * @param zipUrl zip归档的直接URL
     * @return zip字节数组，出错时返回null
     */
    public byte[] downloadSkillZip(String zipUrl) {
        try (CloseableHttpClient http = buildHttpClient()) {
            HttpGet get = new HttpGet(zipUrl);
            get.setHeader("User-Agent", USER_AGENT);
            return http.execute(get, response -> {
                int status = response.getCode();
                if (status != 200) {
                    log.warn("downloadSkillZip HTTP {}: {}", status, zipUrl);
                    return null;
                }
                return EntityUtils.toByteArray(response.getEntity());
            });
        } catch (Exception e) {
            log.warn("downloadSkillZip failed ({}): {}", zipUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 从商店获取可用分类列表。
     *
     * @return 分类列表
     */
    public List<StoreCategory> getCategories() {
        try {
            JsonNode data = getJson(baseUrl + "/api/v1/categories");
            if (data == null) return Collections.emptyList();
            List<StoreCategory> cats = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    cats.add(mapper.treeToValue(node, StoreCategory.class));
                }
            }
            return cats;
        } catch (Exception e) {
            log.warn("getCategories failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 检查商店是否可达。
     *
     * @return 是否可达
     */
    public boolean isReachable() {
        try (CloseableHttpClient http = buildHttpClient()) {
            HttpGet get = new HttpGet(baseUrl + "/api/v1/health");
            get.setHeader("User-Agent", USER_AGENT);
            return http.execute(get, response -> response.getCode() < 500);
        } catch (Exception e) {
            log.debug("Store not reachable: {}", e.getMessage());
            return false;
        }
    }

    // ─── 内部辅助方法 ──────────────────────────────────────────────────────

    /**
     * 执行GET请求并返回商店响应中的"data"字段。
     * 如果响应未包装（没有{ok, data}），则直接返回根节点。
     *
     * @param url 请求URL
     * @return JSON数据节点，出错时返回null
     * @throws IOException IO异常
     */
    private JsonNode getJson(String url) throws IOException {
        try (CloseableHttpClient http = buildHttpClient()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("User-Agent", USER_AGENT);
            get.setHeader("Accept", "application/json");

            return http.execute(get, response -> {
                int status = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (status != 200) {
                    log.warn("SkillHub HTTP {} for {}: {}", status, url, body);
                    return null;
                }
                if (body == null || body.isBlank()) return null;

                JsonNode root = mapper.readTree(body);

                // 解包 { ok, data } 包装（如果存在）
                if (root.has("ok") && root.has("data")) {
                    if (!root.get("ok").asBoolean()) {
                        String err = root.has("error") ? root.get("error").asText() : "unknown error";
                        log.warn("SkillHub API error for {}: {}", url, err);
                        return null;
                    }
                    return root.get("data");
                }

                // 直接返回根节点（某些端点可能不使用包装）
                return root;
            });
        }
    }

    /**
     * 构建HTTP客户端。
     *
     * @return CloseableHttpClient实例
     */
    private CloseableHttpClient buildHttpClient() {
        return HttpClients.custom()
            .setDefaultRequestConfig(
                org.apache.hc.client5.http.config.RequestConfig.custom()
                    .setConnectionRequestTimeout(DEFAULT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setResponseTimeout(DEFAULT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
            .build();
    }

    /**
     * URL编码。
     *
     * @param s 待编码字符串
     * @return 编码后字符串
     */
    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 创建空页。
     *
     * @return 空的StoreSkillPage
     */
    private static StoreSkillPage emptyPage() {
        StoreSkillPage page = new StoreSkillPage();
        page.skills = Collections.emptyList();
        page.total = 0;
        page.hasMore = false;
        return page;
    }
}
