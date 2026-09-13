package com.zihan.zhiwei.ai.memory;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class MemoryOwnerResolver {
 public String resolve(String requestedUserId, Authentication authentication) {
  if (authentication != null && authentication.isAuthenticated()
      && !(authentication instanceof AnonymousAuthenticationToken)) {
   String principal = authentication.getName();
   if (requestedUserId != null && !requestedUserId.isBlank() && !principal.equals(requestedUserId)) {
    throw new AccessDeniedException("authenticated principal does not own requested memory");
   }
   return principal;
  }
  if (requestedUserId == null || requestedUserId.isBlank()) {
   throw new IllegalArgumentException("userId is required when authentication is disabled");
  }
  return requestedUserId;
 }
}