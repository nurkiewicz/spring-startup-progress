# Displaying progress of Spring application startup in web browser

When you restart your *enterprise* application, what do your clients see when they open the web browser?

1. They see nothing, server doesn't respond yet so web browser displays `ERR_CONNECTION_REFUSED`

2. Web proxy (if any) in front of your application notices that it's down and display "friendly" error message

3. The website takes forever to load - it accepted socket connection and HTTP request but waits with response until application actually boots up

4. Your application is scaled out so that other nodes quickly pick up requests and no one notices (and session is replicated anyway)

5. ...or the application is so fast to start that no one notices any disruption (hey, plain Spring Boot *Hello world* app takes less than 3 seconds from hitting `java -jar ... [Enter]` to start serving requests). BTW check out [*SPR-8767: Parallel bean initialization during startup*](https://jira.spring.io/browse/SPR-8767).

It's definitely better to be in situation 4. and 5., but in this article we'll cover more robust handling of situations 1. and 3. 

Typical Spring Boot application starts web container (e.g. Tomcat) at the very end, when all beans are loaded (situation 1.) This is a very reasonable default as it prevents clients from reaching our endpoints until they are fully configured. However this means we cannot differentiate between application that starts up for several seconds and application that is down. So the idea is to have an application that shows some meaningful startup page while it loads, similar to web proxy showing "*Service unavailable*". However since such startup page is part of our application, it can potentially have greater insight into startup progress. We want to start Tomcat earlier in the initialization lifecycle, but serve special purpose startup page until Spring fully bootstraps. This special page should intercept every possible request - thus it sounds like a servlet filter.

# Starting Tomcat eagerly and early.

In Spring Boot servlet container is initialized via [`EmbeddedServletContainerFactory`](http://docs.spring.io/autorepo/docs/spring-boot/1.2.x/api/org/springframework/boot/context/embedded/EmbeddedServletContainerFactory.html) that creates an instance of [`EmbeddedServletContainer`](http://docs.spring.io/autorepo/docs/spring-boot/1.2.x/api/org/springframework/boot/context/embedded/EmbeddedServletContainer.html). We have an opportunity to intercept this process using [`EmbeddedServletContainerCustomizer`](http://docs.spring.io/autorepo/docs/spring-boot/1.2.x/api/org/springframework/boot/context/embedded/EmbeddedServletContainerCustomizer.html). The container is created early in the application lifecycle, but it's *started* much later, when whole context is done. So I thought I will simply call `start()` in my own customizer and that's it. Unfortunately [`ConfigurableEmbeddedServletContainer`](http://docs.spring.io/autorepo/docs/spring-boot/1.2.x/api/org/springframework/boot/context/embedded/ConfigurableEmbeddedServletContainer.html) doesn't expose such API, so I had to decorate `EmbeddedServletContainerFactory` like this:

	class ProgressBeanPostProcessor implements BeanPostProcessor {

		//...

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (bean instanceof EmbeddedServletContainerFactory) {
				return wrap((EmbeddedServletContainerFactory) bean);
			} else {
				return bean;
			}
		}

		private EmbeddedServletContainerFactory wrap(EmbeddedServletContainerFactory factory) {
			return new EmbeddedServletContainerFactory() {
				@Override
				public EmbeddedServletContainer getEmbeddedServletContainer(ServletContextInitializer... initializers) {
					final EmbeddedServletContainer container = factory.getEmbeddedServletContainer(initializers);
					log.debug("Eagerly starting {}", container);
					container.start();
					return container;
				}
			};
		}
	}

You might think that `BeanPostProcessor` is an overkill, but it will become very useful later on. What we do here is that if we encounter `EmbeddedServletContainerFactory` being requested from application context, we return a decorator that eagerly starts Tomcat. This leaves us with rather unstable setup, where Tomcat accepts connections to not yet initialized context. So let's put a servlet filter intercepting all requests until context is done.

# Intercepting request during startup

I started simply by adding [`FilterRegistrationBean`](http://docs.spring.io/autorepo/docs/spring-boot/1.2.x/api/org/springframework/boot/context/embedded/FilterRegistrationBean.html) to Spring context, hoping it would intercept incoming request until context is started. This was fruitless: I had to wait long second until filter was registered and ready, therefor from the user perspective the application was hanging. Later on I even tried registering filter directly in Tomcat using servlet API ([`javax.servlet.ServletContext.addFilter()`](https://docs.oracle.com/javaee/6/api/javax/servlet/ServletContext.html#addFilter(java.lang.String, javax.servlet.Filter))) but apparently whole `DispatcherServlet` had to be bootstrapped beforehand. Remember all I wanted was an extremely fast feedback from the application that it's about to initialize.

So I ended up with Tomcat's proprietary API: [`org.apache.catalina.Valve`](http://tomcat.apache.org/tomcat-8.0-doc/api/org/apache/catalina/Valve.html). `Valve` is similar to servlet filter, but it's part of Tomcat's architecture. Tomcat bundles multiple valves on its own to handle various container features like SSL, session clustering and `X-Forwarded-For` handling. Also [Logback Access](http://logback.qos.ch/access.html) uses this API so I'm not feeling that guilty. The Valve looks like this:

	package com.nurkiewicz.progress;

	import org.apache.catalina.connector.Request;
	import org.apache.catalina.connector.Response;
	import org.apache.catalina.valves.ValveBase;
	import org.apache.tomcat.util.http.fileupload.IOUtils;

	import javax.servlet.ServletException;
	import java.io.IOException;
	import java.io.InputStream;

	public class ProgressValve extends ValveBase {

		@Override
		public void invoke(Request request, Response response) throws IOException, ServletException {
			try (InputStream loadingHtml = getClass().getResourceAsStream("loading.html")) {
				IOUtils.copy(loadingHtml, response.getOutputStream());
			}
		}
	}

Valves typically delegate to next valve in chain, but this time we simply return static `loading.html` page for every single request. Registering such valve is surprisingly simple, Spring Boot has an API for that!

	if (factory instanceof TomcatEmbeddedServletContainerFactory) {
		((TomcatEmbeddedServletContainerFactory) factory).addContextValves(new ProgressValve());
	}

Custom valve turned out to be a great idea, it starts immediately with Tomcat and is fairly easy to use. However you might have noticed that we never give up serving `loading.html`, even after our application started. That's bad. There are multiple ways Spring context can signal initialization, e.g. with `ApplicationListener<ContextRefreshedEvent>`:

	@Component
	class Listener implements ApplicationListener<ContextRefreshedEvent> {

		private static final CompletableFuture<ContextRefreshedEvent> promise = new CompletableFuture<>();

		public static CompletableFuture<ContextRefreshedEvent> initialization() {
			return promise;
		}

		public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
			return bean;
		}

		@Override
		public void onApplicationEvent(ContextRefreshedEvent event) {
			promise.complete(event);
		}

	}

I know what you think, "`static`"? But inside `Valve` I don't want to touch Spring context at all, as it might introduce blocking or even deadlock if I ask for some bean at wrong point in time from random thread. When we complete `promise`, `Valve` deregisters itself:

	public class ProgressValve extends ValveBase {

		public ProgressValve() {
			Listener
					.initialization()
					.thenRun(this::removeMyself);
		}

		private void removeMyself() {
			getContainer().getPipeline().removeValve(this);
		}

		//...

	}

This is surprisingly clean solution: when `Valve` is no longer needed, rather than paying the cost on every single request, we simply remove it from the processing pipeline. I'm not going to demonstrate how and why it works, let's move directly to target solution.

# Monitoring progress

Monitoring progress of Spring application context startup is surprisingly simple. Also I'm amazed how "hackable" Spring framework is, as opposed to API- and spec-driven frameworks like EJB or JSF. In Spring I can simply implement [`BeanPostProcessor`](http://docs.spring.io/spring-framework/docs/4.2.x/javadoc-api/org/springframework/beans/factory/config/BeanPostProcessor.html) to be notified about each and every bean being created and initialized ([full source code](https://github.com/nurkiewicz/spring-startup-progress/blob/master/src/main/java/com/nurkiewicz/progress/ProgressBeanPostProcessor.java)):

	package com.nurkiewicz.progress;

	import org.springframework.beans.BeansException;
	import org.springframework.beans.factory.config.BeanPostProcessor;
	import org.springframework.context.ApplicationListener;
	import org.springframework.context.event.ContextRefreshedEvent;
	import rx.Observable;
	import rx.subjects.ReplaySubject;
	import rx.subjects.Subject;

	class ProgressBeanPostProcessor implements BeanPostProcessor, ApplicationListener<ContextRefreshedEvent> {

		private static final Subject<String, String> beans = ReplaySubject.create();

		public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			beans.onNext(beanName);
			return bean;
		}

		@Override
		public void onApplicationEvent(ContextRefreshedEvent event) {
			beans.onCompleted();
		}

		static Observable<String> observe() {
			return beans;
		}
	}

Every time new bean is initialized, I publish its name into RxJava's observable. When whole application is initialized, I complete `Observable`. This `Observable` can later be consumed by anyone, e.g. our custom `ProgressValve` ([full source code](https://github.com/nurkiewicz/spring-startup-progress/blob/master/src/main/java/com/nurkiewicz/progress/ProgressValve.java)):

	public class ProgressValve extends ValveBase {

		public ProgressValve() {
			super(true);
			ProgressBeanPostProcessor.observe().subscribe(
					beanName -> log.trace("Bean found: {}", beanName),
					t -> log.error("Failed", t),
					this::removeMyself);
		}

		@Override
		public void invoke(Request request, Response response) throws IOException, ServletException {
			switch (request.getRequestURI()) {
				case "/init.stream":
					final AsyncContext asyncContext = request.startAsync();
					streamProgress(asyncContext);
					break;
				case "/health":
				case "/info":
					response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
					break;
				default:
					sendHtml(response, "loading.html");
			}
		}

		//...

	}

`ProgressValve` is now way more complex, and we're not done yet. It can handle multiple different requests, for example I intentionally return 503 on `/health` and `/info` Actuator endpoints so that the application appears as if it was down during startup. All other requests except `init.stream` show familiar [`loading.html`](https://github.com/nurkiewicz/spring-startup-progress/blob/master/src/main/resources/com/nurkiewicz/progress/loading.html). `/init.stream` is special. It's a [server-sent events](https://en.wikipedia.org/wiki/Server-sent_events) endpoint that will push message every time new bean is initialized (sorry for a wall of code):

	private void streamProgress(AsyncContext asyncContext) throws IOException {
		final ServletResponse resp = asyncContext.getResponse();
		resp.setContentType("text/event-stream");
		resp.setCharacterEncoding("UTF-8");
		resp.flushBuffer();
		final Subscription subscription = ProgressBeanPostProcessor.observe()
				.map(beanName -> "data: " + beanName)
				.subscribeOn(Schedulers.io())
				.subscribe(
						event -> stream(event, asyncContext.getResponse()),
						e -> log.error("Error in observe()", e),
						() -> complete(asyncContext)
				);
		unsubscribeOnDisconnect(asyncContext, subscription);
	}

	private void complete(AsyncContext asyncContext) {
		stream("event: complete\ndata:", asyncContext.getResponse());
		asyncContext.complete();
	}

	private void unsubscribeOnDisconnect(AsyncContext asyncContext, final Subscription subscription) {
		asyncContext.addListener(new AsyncListener() {
			@Override
			public void onComplete(AsyncEvent event) throws IOException {
				subscription.unsubscribe();
			}

			@Override
			public void onTimeout(AsyncEvent event) throws IOException {
				subscription.unsubscribe();
			}

			@Override
			public void onError(AsyncEvent event) throws IOException {
				subscription.unsubscribe();
			}

			@Override
			public void onStartAsync(AsyncEvent event) throws IOException {}
		});
	}

	private void stream(String event, ServletResponse response) {
		try {
			final PrintWriter writer = response.getWriter();
			writer.println(event);
			writer.println();
			writer.flush();
		} catch (IOException e) {
			log.warn("Failed to stream", e);
		}
	}

This means we can track progress of Spring's application context startup using simple HTTP interface (!):

	$ curl -v localhost:8090/init.stream
	> GET /init.stream HTTP/1.1
	> User-Agent: curl/7.35.0
	> Host: localhost:8090
	> Accept: */*

	< HTTP/1.1 200 OK
	< Content-Type: text/event-stream;charset=UTF-8
	< Transfer-Encoding: chunked

	data: org.springframework.boot.autoconfigure.web.EmbeddedServletContainerAutoConfiguration$EmbeddedTomcat

	data: org.springframework.boot.autoconfigure.websocket.WebSocketAutoConfiguration$TomcatWebSocketConfiguration

	data: websocketContainerCustomizer

	data: org.springframework.boot.autoconfigure.web.ServerPropertiesAutoConfiguration

	data: toStringFriendlyJsonNodeToStringConverter

	data: org.hibernate.validator.internal.constraintvalidators.bv.NotNullValidator

	data: serverProperties

	data: org.springframework.boot.autoconfigure.web.ErrorMvcAutoConfiguration

	...

	data: beanNameViewResolver

	data: basicErrorController

	data: org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration$JpaWebConfiguration$JpaWebMvcConfiguration

This endpoint will stream in real-time (see also: [*Server-sent events with RxJava and SseEmitter*](http://www.nurkiewicz.com/2015/07/server-sent-events-with-rxjava-and.html)) every single bean name being initialized. Having such an amazing tool we'll build more robust (*reactive* - there, I said it) `loading.html` page.

# Fancy progress front-end

First we need to identify which Spring beans represent which *subsystems*, high-level components (or maybe even *bounded contexts*) in our system. I encoded this [inside HTML](https://github.com/nurkiewicz/spring-startup-progress/blob/master/src/main/resources/com/nurkiewicz/progress/loading.html#L21-L75) using `data-bean` custom attribute:

	<h2 data-bean="websocketContainerCustomizer" class="waiting">
	    Web socket support
	</h2>

	<h2 data-bean="messageConverters" class="waiting">
	    Spring MVC
	</h2>

	<h2 data-bean="metricFilter" class="waiting">
	    Metrics
	</h2>

	<h2 data-bean="endpointMBeanExporter" class="waiting">
	    Actuator
	</h2>

	<h2 data-bean="mongoTemplate" class="waiting">
	    MongoDB
	</h2>

	<h2 data-bean="dataSource" class="waiting">
	    Database
	</h2>

	<h2 data-bean="entityManagerFactory" class="waiting">
	    Hibernate
	</h2>

CSS `class="waiting"` means that a given module is not yet initialized, i.e. given bean didn't yet appear in SSE stream. Initially all components are in `"waiting"` state. I then subscribe to `init.stream` and change CSS class to reflect module state changes:

    var source = new EventSource('init.stream');
    source.addEventListener('message', function (e) {
        var h2 = document.querySelector('h2[data-bean="' + e.data + '"]');
        if(h2) {
            h2.className = 'done';
        }
    });

Simple, huh? Apparently one can write front-end without jQuery in pure JavaScript. When all beans are loaded, `Observable` is completed on the server side and SSE emits `event: complete`, let's handle that:

    source.addEventListener('complete', function (e) {
        window.location.reload();
    });

Because front-end is notified on application context startup, we can simply reload current page. At that point in time, our `ProgressValve` already deregistered itself, so reloading will open *true* application, not `loading.html` placeholder. Our job is done. Additionally I count how many beans started and knowing how many beans are in total (I hardcoded it in JavaScript, forgive me), I can calculate startup progress in percent. Picture is worth a thousand words, let this [screencast](https://www.youtube.com/watch?v=ct9lCULe4o0) show you the result we achieved.

Subsequent modules are starting up nicely and we no longer look at browser error. Progress measured in percents makes the whole startup progress feel very smooth. Last but not least when application started, we are automatically redirected. Hope you enjoyed this proof-of-concept, whole [working sample application](https://github.com/nurkiewicz/spring-startup-progress) is available on GitHub.
