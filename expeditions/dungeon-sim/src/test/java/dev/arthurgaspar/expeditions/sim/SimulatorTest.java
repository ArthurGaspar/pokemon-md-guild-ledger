package dev.arthurgaspar.expeditions.sim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class SimulatorTest {

    private static final String PARTY = "{\"expeditionId\":1,\"partyId\":2,\"members\":[\"a\",\"b\",\"c\",\"d\"]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    Simulator simulator;

    @BeforeEach
    void calm() {
        simulator.chaos(new Chaos(0, 0, 0, 0, 0, 0, false));
    }

    private ResultActions run(String key) throws Exception {
        return mvc.perform(post("/api/runs").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(PARTY));
    }

    @Test
    void aCalmDungeonIsCleared() throws Exception {
        run("run-00000001").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CLEARED"))
                .andExpect(jsonPath("$.floorsCleared").value(Simulator.FLOORS));
    }

    @Test
    void retryingARunGivesTheSameAnswer() throws Exception {
        simulator.chaos(new Chaos(0, 0, 0, 0, 0, 0.5, false));
        String first = run("run-00000002").andReturn().getResponse().getContentAsString();
        run("run-00000002").andExpect(content().json(first));
    }

    @Test
    void faintingIsAnOutcomeNotAnError() throws Exception {
        simulator.chaos(new Chaos(0, 0, 0, 0, 0, 1, false));
        run("run-00000003").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAINTED"));
    }

    @Test
    void errorsAndOutagesAre503() throws Exception {
        simulator.chaos(new Chaos(0, 0, 1, 0, 0, 0, false));
        run("run-00000004").andExpect(status().isServiceUnavailable());
        simulator.chaos(new Chaos(0, 0, 0, 0, 0, 0, true));
        run("run-00000004").andExpect(status().isServiceUnavailable());
    }

    @Test
    void chaosCanBeChangedWhileRunning() throws Exception {
        mvc.perform(put("/api/chaos").contentType(MediaType.APPLICATION_JSON).content("""
                        {"latencyMs":0,"jitterMs":0,"errorRate":0,"slowRate":0,"slowMs":0,"faintRate":0,"outage":true}"""))
                .andExpect(jsonPath("$.outage").value(true));
        run("run-00000005").andExpect(status().isServiceUnavailable());
        mvc.perform(put("/api/chaos").contentType(MediaType.APPLICATION_JSON).content("""
                        {"latencyMs":0,"jitterMs":0,"errorRate":2,"slowRate":0,"slowMs":0,"faintRate":0,"outage":false}"""))
                .andExpect(status().isBadRequest());
    }
}
