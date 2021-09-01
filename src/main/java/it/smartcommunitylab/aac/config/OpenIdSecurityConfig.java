package it.smartcommunitylab.aac.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import it.smartcommunitylab.aac.oauth.auth.InternalOpaqueTokenIntrospector;

/*
 * Security context for API endpoints
 * 
 * Builds a stateless context with JWT/OAuth2 auth.
 * We actually use Bearer tokens and validate by fetching tokens from store
 */

@Configuration
@Order(12)
public class OpenIdSecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    private InternalOpaqueTokenIntrospector tokenIntrospector;

    /*
     * Configure a separated security context for API
     */
    @Override
    public void configure(HttpSecurity http) throws Exception {
        // match only token endpoints
        http.requestMatcher(getRequestMatcher())
                .authorizeRequests((authorizeRequests) -> authorizeRequests
                        .anyRequest().hasAnyAuthority("ROLE_USER", "ROLE_CLIENT"))
                // TODO add support for passing token in request body via custom filter
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver())
                        .opaqueToken(opaqueToken -> opaqueToken
                                .introspector(tokenIntrospector)))
                // disable request cache, we override redirects but still better enforce it
                .requestCache((requestCache) -> requestCache.disable())
                .exceptionHandling()
                // use 401
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedPage("/accesserror")
                .and()
                .csrf()
                .disable();

        // we don't want a session for these endpoints, each request should be evaluated
        http.sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    }

    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        // support token in form body
        resolver.setAllowFormEncodedBodyParameter(true);

        return resolver;
    }

    public RequestMatcher getRequestMatcher() {
        List<RequestMatcher> antMatchers = Arrays.stream(OPENID_URLS).map(u -> new AntPathRequestMatcher(u))
                .collect(Collectors.toList());

        return new OrRequestMatcher(antMatchers);

    }

    public static final String[] OPENID_URLS = {
            "/userinfo"
    };

}
