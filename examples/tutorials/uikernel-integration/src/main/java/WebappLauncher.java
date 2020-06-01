import com.google.gson.Gson;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.di.annotation.Inject;
import io.activej.di.annotation.Provides;
import io.activej.di.module.Module;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncHttpServer;
import io.activej.http.AsyncServlet;
import io.activej.http.RoutingServlet;
import io.activej.http.StaticServlet;
import io.activej.http.loader.StaticLoader;
import io.activej.launcher.Launcher;
import io.activej.service.ServiceGraphModule;
import io.activej.uikernel.UiKernelServlets;

import java.util.concurrent.Executor;

import static io.activej.config.ConfigConverters.ofInteger;
import static io.activej.config.ConfigConverters.ofString;
import static io.activej.di.module.Modules.combine;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class WebappLauncher extends Launcher {
	private static final int DEFAULT_PORT = 8080;
	private static final String DEFAULT_PATH_TO_RESOURCES = "/static";

	@Inject
	AsyncHttpServer server;

	@Provides
	Gson gson() {
		return new Gson();
	}

	@Provides
	Config config() {
		return Config.ofClassPathProperties("configs.properties");
	}

	@Provides
	Eventloop eventloop() {
		return Eventloop.create();
	}

	@Provides
	Executor executor() {
		return newSingleThreadExecutor();
	}

	@Provides
	StaticLoader staticLoader(Executor executor, Config config) {
		return StaticLoader.ofClassPath(executor, config.get(ofString(), "resources", DEFAULT_PATH_TO_RESOURCES));
	}

	@Provides
	AsyncServlet servlet(StaticLoader staticLoader, Gson gson, PersonGridModel model, Config config) {
		StaticServlet staticServlet = StaticServlet.create(staticLoader)
				.withIndexHtml();
		AsyncServlet usersApiServlet = UiKernelServlets.apiServlet(model, gson);

		return RoutingServlet.create()
				.map("/*", staticServlet)              // serves request if no other servlet matches
				.map("/api/users/*", usersApiServlet); // our rest crud servlet that would serve the grid
	}

	@Provides
	AsyncHttpServer server(Eventloop eventloop, Config config, AsyncServlet servlet) {
		return AsyncHttpServer.create(eventloop, servlet)
				.withListenPort(config.get(ofInteger(), "port", DEFAULT_PORT));
	}

	@Override
	protected Module getModule() {
		return combine(
				ServiceGraphModule.create(),
				ConfigModule.create()
						.withEffectiveConfigLogger());
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {
		WebappLauncher launcher = new WebappLauncher();
		launcher.launch(args);
	}
}
