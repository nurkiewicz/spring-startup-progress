package com.nurkiewicz.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.embedded.EmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;

public class ProgressBeanPostProcessor implements BeanPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(ProgressBeanPostProcessor.class);

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		log.info("Bean started: {} of type {}", beanName, bean.getClass());
		return wrapIfServletContainerFacotory(bean);
	}

	private Object wrapIfServletContainerFacotory(Object bean) {
		if (bean instanceof EmbeddedServletContainerFactory) {
			return wrap((EmbeddedServletContainerFactory) bean);
		} else {
			return bean;
		}
	}

	private EmbeddedServletContainerFactory wrap(EmbeddedServletContainerFactory factory) {
		log.debug("Returning eager wrapper over {}", factory);
		return initializers -> {
			final EmbeddedServletContainer container = factory.getEmbeddedServletContainer(initializers);
			log.debug("Eagerly starting {}", container);
			container.start();
			return container;
		};
	}
}
