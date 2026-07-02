package com.nodemetry.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "mqtt.enabled=false")
@ActiveProfiles("test")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}
}
