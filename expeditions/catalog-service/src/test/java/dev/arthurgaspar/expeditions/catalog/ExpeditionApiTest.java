package dev.arthurgaspar.expeditions.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import dev.arthurgaspar.expeditions.platform.events.EventCodec;
import dev.arthurgaspar.expeditions.platform.events.EventEnvelope;
import dev.arthurgaspar.expeditions.platform.events.Events;
import dev.arthurgaspar.expeditions.platform.events.Events.ExpeditionPublished;
import dev.arthurgaspar.expeditions.platform.events.Topics;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpeditionApiTest {

    private static final String BODY = """
            {"code":"%s","name":"Dark Crater","dungeon":"%s","difficulty":"STAR",
             "opensAt":"2026-12-01T18:00:00Z","parties":25,"composition":%s}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EventCodec codec;

    private org.springframework.test.web.servlet.ResultActions publish(String code, String dungeon, String composition)
            throws Exception {
        return mvc.perform(post("/api/expeditions").header("X-Adventurer", "wigglytuff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(code, dungeon, composition)));
    }

    @Test
    void publishingRecordsTheEventInTheOutbox() throws Exception {
        publish("dark-crater-1", "dark-crater", "{\"TANK\":1,\"HEALER\":1,\"DAMAGE\":2}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dungeonName").value("Dark Crater"))
                .andExpect(jsonPath("$.totalSlots").value(100));

        Map<String, Object> row = jdbc.queryForMap(
                "select topic, message_key, event_type, payload from outbox order by id desc limit 1");
        assertThat(row.get("topic")).isEqualTo(Topics.EXPEDITIONS);
        assertThat(row.get("event_type")).isEqualTo(Events.EXPEDITION_PUBLISHED);
        EventEnvelope envelope = codec.read((String) row.get("payload"));
        ExpeditionPublished event = codec.payload(envelope, ExpeditionPublished.class);
        assertThat(envelope.aggregateId()).isEqualTo(row.get("message_key")).isEqualTo(String.valueOf(event.id()));
        assertThat(event.parties()).isEqualTo(25);
        assertThat(event.composition()).containsEntry(dev.arthurgaspar.expeditions.platform.Role.DAMAGE, 2);

        mvc.perform(get("/api/expeditions/" + event.id())).andExpect(jsonPath("$.code").value("dark-crater-1"));
    }

    @Test
    void rejectsUnknownDungeonsBadCompositionsAndDuplicates() throws Exception {
        publish("nowhere-1", "nowhere", "{\"TANK\":1,\"DAMAGE\":1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("expedition.dungeon-unknown"));
        publish("solo-1", "dark-crater", "{\"DAMAGE\":1}")
                .andExpect(jsonPath("$.code").value("expedition.composition-invalid"));
        publish("dup-1", "sky-peak", "{\"TANK\":1,\"DAMAGE\":1}").andExpect(status().isCreated());
        publish("dup-1", "sky-peak", "{\"TANK\":1,\"DAMAGE\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("expedition.code-taken"));
    }

    @Test
    void publishingNeedsAnIdentity() throws Exception {
        mvc.perform(post("/api/expeditions").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("anon-1", "sky-peak", "{\"TANK\":2}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.required"));
    }
}
