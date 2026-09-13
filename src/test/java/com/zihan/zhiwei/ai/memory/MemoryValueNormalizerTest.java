package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryValueNormalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryValueNormalizer normalizer = new MemoryValueNormalizer(objectMapper);

    @Test
    void canonicalizesObjectKeysRecursivelyAndProducesStableSha256() throws Exception {
        var first = objectMapper.readTree("{\"z\":1.0,\"a\":{\"y\":2,\"x\":[3,{\"b\":true,\"a\":null}]}}");
        var reordered = objectMapper.readTree("{\"a\":{\"x\":[3,{\"a\":null,\"b\":true}],\"y\":2},\"z\":1.0}");

        assertThat(normalizer.canonicalJson(first))
                .isEqualTo("{\"a\":{\"x\":[3,{\"a\":null,\"b\":true}],\"y\":2},\"z\":1}");
        assertThat(normalizer.sha256(first)).isEqualTo(normalizer.sha256(reordered));
        assertThat(normalizer.sha256(first)).matches("[0-9a-f]{64}");
    }
}
