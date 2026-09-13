package com.zihan.zhiwei.ai.memory;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
class MemoryOwnerResolverTest {
 @Test void authenticatedPrincipalIsAuthoritative() {
  var resolver = new MemoryOwnerResolver();
  var auth = UsernamePasswordAuthenticationToken.authenticated("alice", "n/a", java.util.List.of());
  assertThat(resolver.resolve("alice", auth)).isEqualTo("alice");
  assertThatThrownBy(() -> resolver.resolve("bob", auth)).isInstanceOf(AccessDeniedException.class);
 }
 @Test void unauthenticatedModeUsesNonBlankRequestedOwnerOnly() {
  var resolver = new MemoryOwnerResolver();
  assertThat(resolver.resolve("demo", null)).isEqualTo("demo");
  assertThatThrownBy(() -> resolver.resolve(" ", null)).isInstanceOf(IllegalArgumentException.class);
 }
}