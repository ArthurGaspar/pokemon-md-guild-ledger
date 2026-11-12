package dev.arthurgaspar.guildledger;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

/** The shared scenario on H2 in PostgreSQL mode. {@link PostgresContractTest} repeats it on real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractScenarioTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TestFactory
    List<DynamicTest> scenario() throws Exception {
        return new ContractRunner(mvc, json).steps();
    }
}
