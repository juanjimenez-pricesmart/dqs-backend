package com.dqs.api.controller;

import com.dqs.api.HealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The liveness probe. One line, and the deploy checks it. */
class HealthControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();

    @Test
    @DisplayName("the health endpoint answers plainly, with no dependency behind it")
    void healthAnswers() throws Exception {
        // No database, no upstream: it has to answer even when they are down,
        // which is the whole point of a liveness probe.
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("DQS Backend Running"));
    }
}
