package com.nurkiewicz.progress;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.stream.IntStream;

@Component
public class SlowBeans implements BeanDefinitionRegistryPostProcessor {

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
		IntStream
				.range(100, 110)
				.forEach(i -> registry.registerBeanDefinition(
						"slow-" + i,
						new RootBeanDefinition(SlowBean.class)));
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
	}
}

@Configuration
class EagerContainerStart {

	@Autowired
	private EmbeddedWebApplicationContext embeddedWebApplicationContext;

	@PostConstruct
	public void init() {
		embeddedWebApplicationContext.getEmbeddedServletContainer().start();
	}
}

class SlowBean {
	private static final RateLimiter limiter = RateLimiter.create(10);

	@PostConstruct
	public void init() {
		limiter.acquire();
	}

}