package com.a22.platform.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class XmlJsonConverter {
    private final ObjectMapper mapper;

    public XmlJsonConverter() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public XmlJsonConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String xmlToJson(String xml) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            Matcher matcher = Pattern.compile("<(\\w+)>(.*?)</\\1>", Pattern.DOTALL).matcher(xml);
            while (matcher.find()) {
                map.put(matcher.group(1), matcher.group(2).trim());
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return xml;
        }
    }

    public String jsonToXml(String json, String rootTag) {
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            String tag = (rootTag == null || rootTag.isBlank()) ? "root" : rootTag;
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + tag + ">\n");
            map.forEach((k, v) -> sb.append("  <").append(k).append(">").append(v).append("</").append(k).append(">\n"));
            sb.append("</").append(tag + ">");
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    public List<Map<String, Object>> recordsFromXml(InputStream inputStream) {
        try {
            String xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, Object>> records = new ArrayList<>();
            
            // 策略1: 查找所有 <row> 标签
            Matcher rowMatcher = Pattern.compile("<row\\b[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(xml);
            while (rowMatcher.find()) {
                Map<String, Object> row = parseFlatElement(rowMatcher.group(1));
                if (!row.isEmpty()) records.add(row);
            }
            if (!records.isEmpty()) return records;

            // 策略2: 查找根元素，然后解析其直接子元素作为记录
            Matcher rootMatcher = Pattern.compile("^\\s*<([\\w:-]+)[^>]*>(.*)</\\1>\\s*$", Pattern.DOTALL).matcher(xml);
            if (rootMatcher.find()) {
                String body = rootMatcher.group(2).trim();
                // 使用更精确的正则匹配顶层子元素
                // 关键：使用贪婪匹配来获取完整的顶层元素
                Pattern childPattern = Pattern.compile("<([\\w:-]+)[^>]*>.*?</\\1>", Pattern.DOTALL);
                Matcher childMatcher = childPattern.matcher(body);
                while (childMatcher.find()) {
                    String element = childMatcher.group(0);
                    Map<String, Object> row = parseNestedElement(element);
                    if (!row.isEmpty()) records.add(row);
                }
            }
            
            // 策略3: 如果还是没有记录，尝试直接解析整个XML
            if (records.isEmpty()) {
                Pattern anyRecordPattern = Pattern.compile("<([\\w:-]+)[^>]*>\\s*(<\\w+>[^<]*</\\w+>\\s*)+\\s*</\\1>", Pattern.DOTALL);
                Matcher anyMatcher = anyRecordPattern.matcher(xml);
                while (anyMatcher.find()) {
                    String element = anyMatcher.group(0);
                    Map<String, Object> row = parseNestedElement(element);
                    if (!row.isEmpty()) records.add(row);
                }
            }
            
            return records;
        } catch (Exception e) {
            return List.of();
        }
    }

    // 解析包含嵌套元素的XML块
    private Map<String, Object> parseNestedElement(String xmlElement) {
        Map<String, Object> row = new LinkedHashMap<>();
        // 提取标签名
        Matcher tagMatcher = Pattern.compile("^<([\\w:-]+)[^>]*>").matcher(xmlElement);
        if (!tagMatcher.find()) return row;
        String tagName = tagMatcher.group(1);
        
        // 提取内容（去掉外层标签）
        String content = xmlElement.substring(tagMatcher.end());
        int lastClose = content.lastIndexOf("</" + tagName + ">");
        if (lastClose > 0) {
            content = content.substring(0, lastClose);
        }
        
        // 解析直接子元素
        Matcher fieldMatcher = Pattern.compile("<([\\w:-]+)[^>]*>(.*?)</\\1>", Pattern.DOTALL).matcher(content);
        while (fieldMatcher.find()) {
            String value = fieldMatcher.group(2).trim();
            // 确保是叶子节点（值中不包含嵌套标签）
            if (!value.contains("<") || isSimpleContent(value)) {
                row.put(fieldMatcher.group(1), cleanValue(value));
            }
        }
        return row;
    }
    
    private boolean isSimpleContent(String value) {
        // 检查是否只是简单的文本内容（可能包含换行等）
        return !value.matches(".*<[\\w:-]+[^>]*>.*");
    }
    
    private String cleanValue(String value) {
        // 去除XML实体和多余空白
        return value.replaceAll("\\s+", " ").trim()
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'");
    }

    private Map<String, Object> parseFlatElement(String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        Matcher fieldMatcher = Pattern.compile("<([\\w:-]+)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL).matcher(content);
        while (fieldMatcher.find()) {
            String value = fieldMatcher.group(2).trim();
            if (!value.contains("<")) row.put(fieldMatcher.group(1), value);
        }
        return row;
    }
}
