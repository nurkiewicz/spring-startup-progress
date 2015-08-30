package com.nurkiewicz.progress;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;

public class ProgressValve extends ValveBase {

	private static final Logger log = LoggerFactory.getLogger(ProgressValve.class);

	public ProgressValve() {
		ProgressBeanPostProcessor.observe().subscribe(
				beanName -> log.trace("Bean found: {}", beanName),
				t -> log.error("Failed", t),
				this::removeMyself);
	}

	private void removeMyself() {
		log.debug("Application started, de-registering");
		getContainer().getPipeline().removeValve(this);
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		try(InputStream loadingHtml = getClass().getResourceAsStream("loading.html")) {
			IOUtils.copy(loadingHtml, response.getResponse().getOutputStream());
		}
	}
}
