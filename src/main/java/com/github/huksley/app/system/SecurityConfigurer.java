package com.github.huksley.app.system;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.intercept.RunAsUserToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.compression.GzipCompressionCodec;
import lombok.Data;

/**
 * Setup security and JWT auth.
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(securedEnabled = true)
@Component
public class SecurityConfigurer extends WebSecurityConfigurerAdapter {
    private static final int JWT_TOKEN_TIMEOUT = 7200;

    private static final String SYSTEM_ROLES = "user,admin";

    private static final String SYSTEM_USERNAME = "system";

    Logger log = LoggerFactory.getLogger(getClass());
    
    public static final String HEADER_AUTH = "X-Auth-Token";
    public static final String COOKIE_AUTH = "AuthToken";
    public static final String ROLE_PREFIX = "ROLE_";
    public static final String ROLE_AUTH_TOKEN = "ROLE_AUTH_TOKEN";
    public static final String ROLE_AUTH_SYSTEM = "ROLE_AUTH_SYSTEM";
    public static final String ROLE_AUTH_PASSWORD = "ROLE_AUTH_PASSWORD";
    
    @Autowired
    ApplicationEventPublisher eventPublisher;
    
    @Autowired
    Environment env;

    @Data
    public static class LocalUser {
        private String login;
        private List<String> roles;
    }
    
    LocalUser systemUser;
    
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent ev) {
        MDC.def().log(log).info("Application ready {}", ev);
        prepareUsers();
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Bean
    @Scope("prototype")
    public SystemAuthorityProvider getSystemAuthorityProvider() {
        return new SystemAuthorityProvider() {          
            @Override
            public Authentication getSystemAuthority(Object invoker) {
                LocalUser u = systemUser;
                if (u != null) {            
                    ArrayList<GrantedAuthority> roles = new ArrayList<>();
                    for (String r: u.getRoles()) {
                        roles.add(new SimpleGrantedAuthority(ROLE_PREFIX + r));
                    }
                    
                    // Add role to indicate user trust
                    roles.add(new SimpleGrantedAuthority(ROLE_AUTH_SYSTEM));            
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(u.getLogin(), "[IMPLIED]", roles);
                    MDC.def().log(log).info("Prepared {} for {}", auth, invoker);
                    return auth;
                } else {
                    throw new IllegalArgumentException("No system authority defined!");
                }
            }
        };
    }
    
    public void prepareUsers() {
        String system = env.getProperty("system.user", SYSTEM_USERNAME);
        
        if (systemUser == null) {
            // Add bootstrap user
            LocalUser u = new LocalUser();
            u.setLogin(system);
            u.setRoles(new ArrayList<>(Arrays.asList(env.getProperty("system.roles", SYSTEM_ROLES).split("\\,[ ]*"))));
            systemUser = u;
        }
    }
    
    /**
     * Создать нужные заголовки для вызова API под пользователем системы
     */
    public RestTemplate createAuthRestTemplate(Object invoker) {
    	String encryptionPassword = env.getProperty("JWT_PASSWORD", env.getProperty("jwt.password"));
    	if (encryptionPassword != null) {
    	    SystemAuthorityProvider p = getSystemAuthorityProvider();
            Authentication systemAuthority = p.getSystemAuthority(this);
			String tok = createToken(encryptionPassword, systemAuthority, env.getProperty("server.session.timeout", Integer.class, JWT_TOKEN_TIMEOUT) * 1000);
	    	RestTemplate rest = new RestTemplate(new SimpleClientHttpRequestFactory() {
	    		@Override
	    		protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
	    			HttpURLConnection conn = super.openConnection(url, proxy);
	    			log.info("Added system auth token for {}, invoker {}", url, invoker);
	    			conn.setRequestProperty(HEADER_AUTH, tok);
					return conn;
	    		}
	    	});
	    	return rest;
    	} else {
    		throw new IllegalStateException("JWT authentication not configured!");
    	}
    }

    SimpleClientHttpRequestFactory createFactory() {
        return new SimpleClientHttpRequestFactory() {
            @Override
            protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
                String encryptionPassword = env.getProperty("JWT_PASSWORD", env.getProperty("jwt.password"));
                if (encryptionPassword != null) {
                    SystemAuthorityProvider p = getSystemAuthorityProvider();
                    Authentication systemAuthority = p.getSystemAuthority(this);
                    String tok = createToken(encryptionPassword, systemAuthority, env.getProperty("server.session.timeout", Integer.class, JWT_TOKEN_TIMEOUT) * 1000);
                    HttpURLConnection conn = super.openConnection(url, proxy);
                    log.info("Added system auth token for {}", url);
                    conn.setRequestProperty(HEADER_AUTH, tok);
                    return conn;
                } else {
                    throw new IllegalStateException("JWT authentication not configured!");
                }
            }
        };
    }

    /**
     * Creates RestTemplateBuilder with system auth enabled.
     */
    @Bean
    @Qualifier("systemAuth")
    public RestTemplateBuilder restTemplateBuilder() {
    	RestTemplateBuilder builder = new RestTemplateBuilder();
    	builder = builder.requestFactory(() -> createFactory());
    	log.info("Created systemAuth RestTemplateBuilder: {}", builder);
        return builder;
    }
    
    /**
     * Создать JWT токен который будет содержать логин (subject) и роли пользователя (payload).
     */
    public String createToken(String secretKey, Authentication auth, long timeout) {
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;
        Date now = new Date();
        StringBuilder roles = new StringBuilder();
        for (GrantedAuthority a: auth.getAuthorities()) {
            if (roles.length() > 0) {
                roles.append(", ");
            }
            roles.append(a.getAuthority());
        }
        byte[] apiKeySecretBytes = secretKey.getBytes(Charset.forName("ISO-8859-1"));
        SecretKeySpec signingKey = new SecretKeySpec(apiKeySecretBytes, signatureAlgorithm.getJcaName());
        JwtBuilder builder = Jwts.builder().setId(UUID.randomUUID().toString())
                .setIssuedAt(now)
                .setSubject(auth.getName())
                .setAudience(roles.toString())
                .signWith(signatureAlgorithm, signingKey)
                .compressWith(new GzipCompressionCodec());
        long exp = System.currentTimeMillis() + timeout;
		builder.setExpiration(new Date(exp));
        String jwt = builder.compact();
        return jwt + ":" + exp;
    }
    
    /**
     * Авторизация с помощью токена
     */
    public static class TokenAuthentication extends RunAsUserToken {
		private static final long serialVersionUID = 1L;

		/**
    	 * TTL (ms)
    	 */
    	private final long expiration;
    	
    	/**
    	 * JWT token
    	 */
    	private final String token;
    	
		public TokenAuthentication(String login, Collection<? extends GrantedAuthority> authorities, long expiration, String token) {
			super(login, login, null, authorities, null);
			this.expiration = expiration;
			this.token = token;
		}

		public long getExpiration() {
			return expiration;
		}

		public String getToken() {
			return token;
		}

		public void auth(HttpHeaders headers) {
			headers.add(HEADER_AUTH, token);
		}

		public void auth(HttpURLConnection conn) {
			conn.setRequestProperty(HEADER_AUTH, token);
		}
    }
    
    /**
     * Восстанавливает пользователя из JWT токена.
     */
    public Authentication restoreToken(String secretKey, String jwt) {
    	String tok = jwt;
    	// Strip expiration
    	if (tok.indexOf(":") > 0) {
    		tok = tok.substring(0, tok.indexOf(":"));
    	}
        Claims token = (Claims) Jwts.parser()
            .setAllowedClockSkewSeconds(5)
            .setSigningKey(secretKey.getBytes(Charset.forName("ISO-8859-1")))
            .parse(tok).getBody();
        long exp = token.getExpiration() != null ? (token.getExpiration().getTime() - System.currentTimeMillis()) : 0;
		log.info("Auth token subject {} expiration in {} ms", token.getSubject(), exp);
        List<GrantedAuthority> authorities = new ArrayList<>();
        String roles = token.getAudience();
        for (StringTokenizer tk = new StringTokenizer(roles, ", "); tk.hasMoreTokens();) {
            String role = tk.nextToken().trim();
            // Don`t add password role
            if (!role.equals(ROLE_AUTH_PASSWORD)) {
                authorities.add(new SimpleGrantedAuthority(role));
            }
        }
        
        // Add token role
        SimpleGrantedAuthority authToken = new SimpleGrantedAuthority(ROLE_AUTH_TOKEN);
        if (!authorities.contains(authToken)) {
        	authorities.add(authToken);
        }
        
        RunAsUserToken auth = new TokenAuthentication(token.getSubject(), authorities, exp, jwt);
        return auth;
    }
   
    /**
     * Осуществляет установку токена после успешной авторизации вводом имени и пароля.
     */
    @Bean
    @ConditionalOnProperty({ "jwt.password", "JWT_PASSWORD" })
    public FilterRegistrationBean createTokenUpdate() {
        String encryptionPassword = env.getProperty("JWT_PASSWORD", env.getProperty("jwt.password"));
        
        Filter f = new Filter() {
            Logger log = LoggerFactory.getLogger(getClass());
        
            public void init(FilterConfig config) throws ServletException {
            }
    
            public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws java.io.IOException, ServletException {
                HttpServletRequest request = (HttpServletRequest) req;
                HttpServletResponse response = (HttpServletResponse) resp;
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                
                if (auth != null && !(auth instanceof AnonymousAuthenticationToken) && response != null) {
                	SimpleGrantedAuthority authToken = new SimpleGrantedAuthority(ROLE_AUTH_TOKEN);
                	if (auth.getAuthorities().contains(authToken)) {
                		// Don`t create token for Token auth
                	} else {                	
	                	String cookieToken = null;
	                	Cookie[] cl = request.getCookies();
	                	if (cl != null) {
	                		for (Cookie ck: cl) {
	                			if (ck.getName().equals(COOKIE_AUTH)) {
	                				cookieToken  = ck.getValue();
	                				break;
	                			}
	                		}
	                	}
	                	
	                	String headerToken = request.getHeader(HEADER_AUTH);
	                	
	                	boolean tokenExpired = false;
	                	if (headerToken != null && headerToken.indexOf(":") > 0) {
							long exp = Long.parseLong(headerToken.substring(headerToken.indexOf(":") + 1));
							long now = System.currentTimeMillis();
							if (exp < now) {
								log.trace("Token is in the past: {} <> {}", exp, now);
								tokenExpired = true;
							}
						}

	                	if (cookieToken != null && cookieToken.indexOf(":") > 0) {
							long exp = Long.parseLong(cookieToken.substring(cookieToken.indexOf(":") + 1));
							long now = System.currentTimeMillis();
							if (exp < now) {
								log.trace("Token is in the past: {} <> {}", exp, now);
								tokenExpired = true;
							}
						}

	                	// Only recreate cookie if there is no cookie
	                	if ((headerToken == null && cookieToken == null) || tokenExpired) {
		                	String token = createToken(encryptionPassword, auth, env.getProperty("server.session.timeout", Integer.class, 7200) * 1000);
		                    log.info("Setting user {} auth token {}", auth.getName(), token);
		                    response.setHeader(HEADER_AUTH, token);
		                    Cookie ck = new Cookie(COOKIE_AUTH, token);
		                    ck.setPath("/");
		                    response.addCookie(ck);
	                	}
                	}
                }
                
                chain.doFilter(request, response);
            }

            public void destroy() {
            }
        };

        FilterRegistrationBean reg = new FilterRegistrationBean();
        reg.setFilter(f);
        // HARDCODED so all filters will be processed
        reg.setUrlPatterns(Collections.singleton("/auth/success"));
        return reg;
    }
    
    /**
     * Осуществляет прозрачную авторизацию c использованием JWT токена если передали X-Auth-Token.
     * Должен быть включен в HttpSecurity как addFilterBefore UsernamePasswordAuthentication.
     */
    public AbstractAuthenticationProcessingFilter createTokenAuthFilter(ApplicationEventPublisher eventPublisher) {
        String encryptionPassword = env.getProperty("JWT_PASSWORD", env.getProperty("jwt.password"));
        
        AbstractAuthenticationProcessingFilter f = new AbstractAuthenticationProcessingFilter(new AntPathRequestMatcher("/**")) {
            @Override
            protected boolean requiresAuthentication(HttpServletRequest request, HttpServletResponse response) {
            	Authentication auth = SecurityContextHolder.getContext().getAuthentication(); 
                if ((auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) && encryptionPassword != null && super.requiresAuthentication(request, response)) {
                    String tok = request.getHeader(HEADER_AUTH);
					if (tok == null) {
                        Cookie[] ckl = request.getCookies();
                        if (ckl != null) {
	                        for (Cookie ck: ckl) {
	                            if (ck.getName().equals(COOKIE_AUTH)) {
	                            	tok = ck.getValue();
	                            	break;
	                            }
	                        }
                        }
                    }
					
					if (tok != null) {
						// Don`t accept expired tokens
						if (tok.indexOf(":") > 0) {
							long exp = Long.parseLong(tok.substring(tok.indexOf(":") + 1));
							long now = System.currentTimeMillis();
							if (exp < now) {
								log.trace("Token is in the past: {} <> {}", exp, now);
			                    Cookie ck = new Cookie(COOKIE_AUTH, "");
			                    ck.setPath("/");
			                    ck.setMaxAge(0);
			                    response.addCookie(ck);
								return false;
							}
						}
						
						return true;
					}
                }
                
                return false;
            }
            
            @Override
            public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException, IOException, ServletException {
                SecurityContext ctx = SecurityContextHolder.getContext();
                if (ctx == null) {
                    throw new NullPointerException("ctx");
                }
                
                String jwt = request.getHeader(HEADER_AUTH);
                boolean cookie = false;
                
                if (jwt == null) {
                    Cookie[] ckl = request.getCookies();
                    if (ckl != null) {
	                    for (Cookie ck: ckl) {
	                        if (ck.getName().equals(COOKIE_AUTH)) {
	                            jwt = ck.getValue();
	                            cookie = true;
	                            break;
	                        }
	                    }
                    }
                }
                
                log.info("Attempting token auth {} existing auth {}", jwt, ctx.getAuthentication());
                if (jwt != null) {
                    try {
                        Authentication auth = restoreToken(encryptionPassword, jwt);
                        log.info("Successfull token auth {}", auth.getName());
                        return auth;
                    } catch (ExpiredJwtException e) {
                        // Срок действия токена истек
                    	if (cookie) {
	                    	Cookie ck = new Cookie(COOKIE_AUTH, "");
		                    ck.setPath("/");
		                    ck.setMaxAge(0);
		                    response.addCookie(ck);
                    	}
                    	// FIXME: Redirect to login?
                        throw new CredentialsExpiredException(e.getMessage(), e);
                    } catch (JwtException e) {
                        // Любая другая ошибка (неправильный формат?)
                    	if (cookie) {
	                    	Cookie ck = new Cookie(COOKIE_AUTH, "");
		                    ck.setPath("/");
		                    ck.setMaxAge(0);
		                    response.addCookie(ck);
                    	}
                    	// FIXME: Redirect to login?
                        throw new BadCredentialsException(e.getMessage(), e);
                    }
                } else {
                    throw new BadCredentialsException("No cookie nor header found for auth!");
                }
            }

            @Override
            protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication auth) throws IOException, ServletException {
                super.successfulAuthentication(request, response, chain, auth);
                chain.doFilter(request, response);
            }
        };
        
        f.setAuthenticationSuccessHandler(new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
                eventPublisher.publishEvent(new AuthenticationSuccessEvent(authentication));
            }
        });
        
        // We will call later chain.doFilter() AFTER auth
        f.setContinueChainBeforeSuccessfulAuthentication(false);
        // Generates InteractiveAuthenticationSuccessEvent but we generate our own AuthenticationSuccessEvent
        f.setApplicationEventPublisher(null);
        f.setAuthenticationManager(getAuthenticationManager());
        return f;
    }

    public AuthenticationManager getAuthenticationManager() {
        try {
            AuthenticationManager b = authenticationManagerBean();
            return b;
        } catch (Exception e) {
            throw new IllegalStateException("Can`t get authenticationManagerBean: " + e, e);
        }
    }
    
    @EventListener
    public void onSuccessLogin(AuthenticationSuccessEvent ev) {
        log.info("Authenticated: {}", ev.getAuthentication());
    }
    
    /**
     * Авторизация по принципу - все что не разрешено, все запрещено.
     */
	@Override
	protected void configure(HttpSecurity http) throws Exception {
	    http.csrf().disable();

	    boolean insecure = env.getProperty("security.insecure", Boolean.class, false);
        if (insecure) {
            // Permit all, no auth needed
            http.authorizeRequests().anyRequest().permitAll();
        } else {
    	    // Static resources
            http.authorizeRequests().antMatchers("/").permitAll();
            http.authorizeRequests().antMatchers("/index.html").permitAll();
            http.authorizeRequests().antMatchers("/robots.txt").permitAll();
            http.authorizeRequests().antMatchers("/favicon.png").permitAll();
    
            // Swagger UI
            http.authorizeRequests().antMatchers("/swagger-ui.html").permitAll();
            http.authorizeRequests().antMatchers("/webjars/**").permitAll();
            http.authorizeRequests().antMatchers("/webjars-locator.js").permitAll();
            http.authorizeRequests().antMatchers("/swagger-resources/**").permitAll();
            http.authorizeRequests().antMatchers("/v2/api-docs").permitAll();
    	    
    	    // Management
    	    http.authorizeRequests().antMatchers("/management/health").permitAll();
            http.authorizeRequests().antMatchers("/management/**").hasAnyAuthority("ROLE_ADMIN");
            
            http.authorizeRequests().antMatchers("/api/**").hasAnyAuthority("ROLE_USER");
            http.authorizeRequests().antMatchers("/system/**").hasAnyAuthority("ROLE_USER");
            
            // Auth single endpoint
            http.authorizeRequests().antMatchers("/auth/**").permitAll();

            // Resources
            http.authorizeRequests().antMatchers("/generated/**").permitAll();
            http.authorizeRequests().antMatchers("/vendor/**").permitAll();

            // Deny all the rest
            http.authorizeRequests().anyRequest().denyAll();
        }
            
        http.formLogin().loginPage("/auth/login");
        http.formLogin().loginProcessingUrl("/auth/authenticate");
        http.formLogin().defaultSuccessUrl("/auth/success", true);
        http.addFilterBefore(createTokenAuthFilter(eventPublisher), UsernamePasswordAuthenticationFilter.class);
        http.logout()
        	.logoutUrl("/auth/logout")
        	.logoutSuccessUrl(env.getProperty("forward.logout.finish", "/auth/"))
        	.deleteCookies("AuthToken")
        	.invalidateHttpSession(true)
        	.addLogoutHandler(new LogoutHandler() {				
				@Override
				public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
					// Remove any cookies, if required
				}
			});
	}
}