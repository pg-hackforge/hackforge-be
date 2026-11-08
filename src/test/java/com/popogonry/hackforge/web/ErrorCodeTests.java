package com.popogonry.hackforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ErrorCodeTests {

	/** Java에만 있는 코드는 클라이언트가 모르는 코드다. spec에 먼저 추가한다. */
	@Test
	@SuppressWarnings("unchecked")
	void everyErrorCodeIsInOpenApiSpec() throws Exception {
		Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("openapi.yaml")));
		Map<String, Object> code = dig(spec, "components", "schemas", "Problem", "properties", "code");
		List<String> specCodes = (List<String>) code.get("enum");

		assertThat(specCodes).containsAll(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dig(Map<String, Object> map, String... keys) {
		for (String key : keys) {
			map = (Map<String, Object>) map.get(key);
		}
		return map;
	}

}
