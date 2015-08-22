package com.nurkiewicz.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class ProgressBeanPostProcessor implements BeanPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(ProgressBeanPostProcessor.class);

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		log.info("Bean started: {}", beanName);
		return bean;
	}
}
