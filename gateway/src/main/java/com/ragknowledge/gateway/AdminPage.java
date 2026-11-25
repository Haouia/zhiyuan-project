package com.ragknowledge.gateway;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminPage {
    private final Map<String, String> configuration;

    AdminPage(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${rag.oidc-client-id}") String clientId) {
        this.configuration = Map.of("issuer", issuer, "clientId", clientId);
    }

    @GetMapping({"/", "/admin"})
    ResponseEntity<Void> redirect() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/admin/")).build();
    }

    @GetMapping("/ask")
    ResponseEntity<Void> redirectQuestions() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/ask/")).build();
    }

    @GetMapping("/register")
    ResponseEntity<Void> redirectRegister() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/register/")).build();
    }

    @GetMapping(value = {"/admin/", "/ask/", "/register/"}, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> page() {
        Resource page = new ClassPathResource("static/admin/index.html");
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/admin/config.json")
    Map<String, String> configuration() {
        return configuration;
    }
}
