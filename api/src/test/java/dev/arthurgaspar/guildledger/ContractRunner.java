package dev.arthurgaspar.guildledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DynamicTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plays {@code shared/contract/scenario.json} against the API. The web client's in-browser engine runs the same
 * file (web/src/engine/contract.test.ts), which keeps the GitHub Pages build honest about what the API does.
 */
final class ContractRunner {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9:]+)}");

    private final MockMvc mvc;
    private final JsonMapper json;
    private final Map<String, JsonNode> saved = new HashMap<>();

    ContractRunner(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    List<DynamicTest> steps() throws Exception {
        JsonNode scenario;
        try (InputStream in = getClass().getResourceAsStream("/contract/scenario.json")) {
            scenario = json.readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        int i = 1;
        for (JsonNode step : scenario.get("steps")) {
            String label = String.format("%02d %s", i++, step.get("name").asString());
            tests.add(DynamicTest.dynamicTest(label, () -> run(step)));
        }
        return tests;
    }

    private void run(JsonNode step) throws Exception {
        HttpMethod method = HttpMethod.valueOf(step.get("method").asString());
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, interpolate(step.get("path").asString()));
        if (step.hasNonNull("as")) {
            JsonNode token = saved.get("token:" + step.get("as").asString());
            assertThat(token).as("token for " + step.get("as")).isNotNull();
            req.header("Authorization", "Bearer " + token.asString());
        }
        if (step.hasNonNull("body")) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(substitute(step.get("body"))));
        }
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String text = res.getContentAsString();
        JsonNode body = text.isBlank() ? null : json.readTree(text);

        JsonNode expect = step.get("expect");
        assertThat(res.getStatus()).as("status; body: " + text).isEqualTo(expect.get("status").asInt());
        if (expect.has("code")) {
            assertThat(body).as("problem body").isNotNull();
            assertThat(body.path("code").asString()).as("error code; body: " + text).isEqualTo(expect.get("code").asString());
        }
        if (expect.has("json")) {
            for (var e : expect.get("json").properties()) {
                JsonNode actual = select(body, e.getKey());
                assertSame(e.getKey(), e.getValue(), actual, text);
            }
        }
        if (step.has("save")) {
            for (var e : step.get("save").properties()) {
                JsonNode value = select(body, e.getValue().asString());
                if (value == null || value.isNull()) {
                    fail("nothing at " + e.getValue() + " to save as " + e.getKey() + "; body: " + text);
                }
                saved.put(e.getKey(), value);
            }
        }
    }

    private static void assertSame(String path, JsonNode expected, JsonNode actual, String body) {
        if (actual == null) {
            fail(path + " is missing; body: " + body);
        }
        if (expected.isNumber() && actual.isNumber()) {
            assertThat(actual.decimalValue()).as(path).isEqualByComparingTo(expected.decimalValue());
        } else {
            assertThat(actual).as(path + "; body: " + body).isEqualTo(expected);
        }
    }

    /** Minimal JSON path: {@code $}, {@code .name}, {@code [n]}, and a trailing {@code #} for length. */
    static JsonNode select(JsonNode root, String path) {
        if (root == null) {
            return null;
        }
        boolean length = path.endsWith("#");
        String p = length ? path.substring(0, path.length() - 1) : path;
        JsonNode node = root;
        Matcher m = Pattern.compile("\\.([A-Za-z0-9_]+)|\\[(\\d+)]").matcher(p.substring(1));
        while (m.find() && node != null) {
            node = m.group(1) != null ? node.get(m.group(1)) : node.get(Integer.parseInt(m.group(2)));
        }
        if (node == null) {
            return null;
        }
        return length ? JsonMapper.shared().getNodeFactory().numberNode(node.size()) : node;
    }

    private String interpolate(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonNode v = saved.get(m.group(1));
            assertThat(v).as("saved value " + m.group(1)).isNotNull();
            m.appendReplacement(out, Matcher.quoteReplacement(v.isNumber() ? v.numberValue().toString() : v.asString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A string that is exactly "{var}" becomes the saved value with its JSON type; other strings interpolate. */
    private JsonNode substitute(JsonNode node) {
        if (node.isString()) {
            Matcher whole = PLACEHOLDER.matcher(node.asString());
            if (whole.matches()) {
                return saved.get(whole.group(1));
            }
            return json.getNodeFactory().stringNode(interpolate(node.asString()));
        }
        if (node.isArray()) {
            ArrayNode out = json.createArrayNode();
            node.forEach(child -> out.add(substitute(child)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = json.createObjectNode();
            node.properties().forEach(e -> out.set(e.getKey(), substitute(e.getValue())));
            return out;
        }
        return node;
    }
}
