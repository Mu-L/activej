package io.activej.rpc;

import io.activej.common.MemSize;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.di.annotation.Eager;
import io.activej.di.annotation.Named;
import io.activej.di.annotation.Provides;
import io.activej.di.module.Module;
import io.activej.eventloop.Eventloop;
import io.activej.launcher.Launcher;
import io.activej.promise.Promise;
import io.activej.rpc.server.RpcServer;
import io.activej.service.ServiceGraphModule;

import static io.activej.config.ConfigConverters.*;
import static io.activej.di.module.Modules.combine;
import static io.activej.eventloop.FatalErrorHandlers.rethrowOnAnyError;

public class RpcBenchmarkServer extends Launcher {
	private final static int SERVICE_PORT = 25565;

	@Provides
	@Named("server")
	Eventloop eventloopServer() {
		return Eventloop.create()
				.withFatalErrorHandler(rethrowOnAnyError());
	}

	@Provides
	@Eager
	public RpcServer rpcServer(@Named("server") Eventloop eventloop, Config config) {
		return RpcServer.create(eventloop)
				.withStreamProtocol(
						config.get(ofMemSize(), "rpc.defaultPacketSize", MemSize.kilobytes(256)),
						MemSize.bytes(128),
						config.get(ofBoolean(), "rpc.compression", false))
				.withListenPort(config.get(ofInteger(), "rpc.server.port"))
				.withMessageTypes(Integer.class)
				.withHandler(Integer.class, Integer.class, req -> Promise.of(req * 2));

	}

	@Provides
	Config config() {
		return Config.create()
				.with("rpc.server.port", "" + SERVICE_PORT)
				.overrideWith(Config.ofSystemProperties("config"));
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
		RpcBenchmarkServer benchmark = new RpcBenchmarkServer();
		benchmark.launch(args);
	}
}
