package com.zihan.zhiwei.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("部署配置")
class DeploymentConfigurationTest {

    @Test
    @DisplayName("Compose 可解析且应用服务收到依赖凭据与安全密钥")
    void composePassesCredentialsToApplications() throws Exception {
        Path composeFile = Path.of("docker-compose.yml");
        Map<String, Object> compose;
        try (var reader = Files.newBufferedReader(composeFile)) {
            compose = new Yaml().load(reader);
        }

        Map<String, Object> services = map(compose.get("services"));
        assertServiceEnvironment(services, "zhiwei");
        assertServiceEnvironment(services, "zhiwei-mcp");
    }

    @Test
    @DisplayName("镜像构建链包含 Maven Wrapper 且 Docker 配置文件完整")
    void imageBuildChainIsComplete() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String mcpDockerfile = Files.readString(Path.of("Dockerfile.mcp"));
        String dockerIgnore = Files.readString(Path.of(".dockerignore"));

        assertThat(dockerfile)
                .contains("COPY .mvn .mvn")
                .contains("COPY mvnw pom.xml ./")
                .contains("./mvnw -DskipTests package")
                .contains("/actuator/health")
                .contains("USER app");
        assertThat(dockerIgnore).contains("target").contains(".env");
        assertThat(mcpDockerfile)
                .contains("target/zhiwei-*-mcp.jar")
                .contains("/health/ready")
                .contains("USER app");
        assertThat(Files.exists(Path.of(".mvn", "wrapper", "maven-wrapper.properties"))).isTrue();
        assertThat(Files.exists(Path.of("src", "main", "resources", "application-docker.yml"))).isTrue();

        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile());
        String pomText = document.getDocumentElement().getTextContent();
        assertThat(pomText)
                .contains("repackage-mcp")
                .contains("com.zihan.zhiwei.ZhiweiMcpServerApplication")
                .contains("mcp");
    }

    private static void assertServiceEnvironment(Map<String, Object> services, String serviceName) {
        Map<String, Object> service = map(services.get(serviceName));
        Map<String, Object> environment = map(service.get("environment"));

        assertThat(environment)
                .containsKeys("MYSQL_PASSWORD", "POSTGRES_PASSWORD", "REDIS_PASSWORD",
                        "RABBITMQ_PASSWORD", "ZHIWEI_API_KEYS");
        assertThat(String.valueOf(environment.get("ZHIWEI_API_KEYS")))
                .contains("ZHIWEI_API_KEYS:?");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
