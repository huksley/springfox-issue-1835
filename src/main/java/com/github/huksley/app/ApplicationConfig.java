package com.github.huksley.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.web.context.support.ServletRequestHandledEvent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.TimeZone;

/**
 * Generic application configuration and beans. This file should be in root package.
 */
@Configuration
@EnableAsync
@EnableScheduling
@ComponentScan
@EntityScan
public class ApplicationConfig {
	Logger log = LoggerFactory.getLogger(getClass());

	@Autowired
	protected ApplicationEventPublisher publisher;

    /**
     * Force using UTC timezone throughout the app.
     * Good for consistent datetime and timezone representation throughout the app.
     */
    static {
        if (!"false".equals(System.getenv("FORCE_UTC"))) {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        }
    }

    /**
     * Listener for every event in Spring Framework,
     * during development only to understand interesting events to track aftewards
     */
	@EventListener
	protected void onEvent(Object ev) {
	    if (ev instanceof ServletRequestHandledEvent) {
	        // Don`t care
	    } else {
	        log.info("Got event {}", ev);
	    }
	}
	
	@EventListener
    protected void onEvent(ContextClosedEvent ev) {
        log.info("Context stopped {}", ev);
    }
}