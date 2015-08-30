package com.nurkiewicz.progress;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
class EagerContainerStart {

	@Autowired
	private EmbeddedWebApplicationContext embeddedWebApplicationContext;

	@PostConstruct
	public void init() {
		embeddedWebApplicationContext.getEmbeddedServletContainer().start();
	}
}