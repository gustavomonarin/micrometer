package io.micrometer.javalin.samples;

import io.javalin.Javalin;
import io.javalin.core.plugin.Plugin;
import io.javalin.http.ExceptionHandler;
import io.javalin.http.HandlerEntry;
import io.javalin.http.HandlerType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.http.DefaultHttpServletRequestTagsProvider;
import io.micrometer.core.instrument.binder.jetty.JettyConnectionMetrics;
import io.micrometer.core.instrument.binder.jetty.JettyServerThreadPoolMetrics;
import io.micrometer.core.instrument.binder.jetty.TimedHandler;
import io.micrometer.core.instrument.util.StringUtils;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.eclipse.jetty.server.Server;
import org.jetbrains.annotations.NotNull;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;

/**
 * See https://github.com/tipsy/javalin/pull/959 which adds improvements to MicrometerPlugin
 */
public class PrometheusSample {
    public static void main(String[] args) {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Javalin app = Javalin.create(config -> config.registerPlugin(new MicrometerPlugin(meterRegistry))).start(8080);

        // must manually delegate to Micrometer exception handler for excepton tags to be correct
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            MicrometerPlugin.EXCEPTION_HANDLER.handle(e, ctx);
            e.printStackTrace();
        });

        app.get("/", ctx -> ctx.result("Hello World"));
        app.get("/hello/:name", ctx -> ctx.result("Hello: " + ctx.pathParam("name")));
        app.get("/boom", ctx -> {
            throw new IllegalArgumentException("boom");
        });

        app.routes(() -> {
            path("hi", () -> {
                get(":name", ctx -> ctx.result("Hello: " + ctx.pathParam("name")));
            });
        });

        app.after("/hello/*", ctx -> {
            System.out.println("hello");
        });

        app.get("/prometheus", ctx -> ctx
                .contentType(TextFormat.CONTENT_TYPE_004)
                .result(meterRegistry.scrape()));
    }
}

class MicrometerPlugin implements Plugin {
    private static final String EXCEPTION_HEADER = "__micrometer_exception_name";

    private final MeterRegistry registry;
    private final Iterable<Tag> tags;

    public static ExceptionHandler<Exception> EXCEPTION_HANDLER = (e, ctx) -> {
        String simpleName = e.getClass().getSimpleName();
        ctx.header(EXCEPTION_HEADER, StringUtils.isNotBlank(simpleName) ? simpleName : e.getClass().getName());
        ctx.status(500);
    };

    public MicrometerPlugin(MeterRegistry registry) {
        this(registry, Tags.empty());
    }

    public MicrometerPlugin(MeterRegistry registry, Iterable<Tag> tags) {
        this.registry = registry;
        this.tags = tags;
    }

    @Override
    public void apply(@NotNull Javalin app) {
        Server server = app.server().server();

        app.exception(Exception.class, EXCEPTION_HANDLER);

        server.insertHandler(new TimedHandler(registry, tags, new DefaultHttpServletRequestTagsProvider() {
            @Override
            public Iterable<Tag> getTags(HttpServletRequest request, HttpServletResponse response) {
                String exceptionName = response.getHeader(EXCEPTION_HEADER);
                response.setHeader(EXCEPTION_HEADER, null);

                String uri = app.servlet().getMatcher()
                        .findEntries(HandlerType.GET, request.getPathInfo())
                        .stream()
                        .findAny()
                        .map(HandlerEntry::getPath)
                        .map(path -> path.equals("/") || StringUtils.isBlank(path) ? "root" : path)
                        .map(path -> response.getStatus() >= 300 && response.getStatus() < 400 ? "REDIRECTION" : path)
                        .map(path -> response.getStatus() == 404 ? "NOT_FOUND" : path)
                        .orElse("unknown");

                return Tags.concat(
                        super.getTags(request, response),
                        "uri", uri,
                        "exception", exceptionName == null ? "None" : exceptionName
                );
            }
        }));

        new JettyServerThreadPoolMetrics(server.getThreadPool(), tags).bindTo(registry);
        new JettyConnectionMetrics(registry, tags);
    }
}
