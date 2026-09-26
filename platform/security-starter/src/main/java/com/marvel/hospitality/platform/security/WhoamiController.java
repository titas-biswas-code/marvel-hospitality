package com.marvel.hospitality.platform.security;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops endpoint, kept permanently: the identity, roles and properties this service derives from the token. */
@RestController
public class WhoamiController {

    /** What this service sees in the caller's token, after role conversion. */
    public record WhoamiResponse(String subject, String username, List<String> roles, List<String> properties) {
    }

    @Operation(summary = "Identity, roles and properties this service sees in the bearer token")
    @GetMapping("/whoami")
    public WhoamiResponse whoami(JwtAuthenticationToken authentication) {
        return new WhoamiResponse(
                authentication.getToken().getSubject(),
                authentication.getName(),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList(),
                PropertyAccess.entitledProperties(authentication.getToken()));
    }
}
